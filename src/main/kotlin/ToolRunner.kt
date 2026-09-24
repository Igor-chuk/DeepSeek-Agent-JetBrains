package ru.ichuk.deepseek

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Parses and executes `[TOOL: name(args)]` requests coming from the DeepSeek web chat.
 * Returns a string that will be fed back into the chat as the tool result.
 */
class ToolRunner(private val project: Project) {

    data class ToolCall(val name: String, val args: List<String>)

    /**
     * Finds every `[TOOL: name(arg1, arg2, ...)]` occurrence in `text`,
     * but ONLY returns calls whose name is in [KNOWN_TOOLS]. This prevents
     * placeholders like `[TOOL: name(...)]` or `[TOOL: имя_инструмента(...)]`
     * that the model sometimes writes in its explanation from being executed.
     */
    fun parseToolCalls(text: String): List<ToolCall> {
        val re = Regex("""\[TOOL:\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(([\s\S]*?)\)\s*]""")
        return re.findAll(text)
            .map { match ->
                val name = match.groupValues[1]
                val rawArgs = match.groupValues[2]
                ToolCall(name, splitArgs(rawArgs))
            }
            .filter { it.name in KNOWN_TOOLS }
            .toList()
    }

    /**
     * Splits raw arguments on commas that are not inside quotes and unescapes
     * standard sequences inside quotes (`\n`, `\t`, `\r`, `\"`, `\\`).
     */
    private fun splitArgs(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val args = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length && inQuotes -> {
                    when (val next = raw[i + 1]) {
                        'n'  -> sb.append('\n')
                        't'  -> sb.append('\t')
                        'r'  -> sb.append('\r')
                        '"'  -> sb.append('"')
                        '\'' -> sb.append('\'')
                        '`'  -> sb.append('`')
                        '\\' -> sb.append('\\')
                        else -> { sb.append('\\'); sb.append(next) }
                    }
                    i += 2
                    continue
                }
                c == '"' -> { inQuotes = !inQuotes; i += 1; continue }
                c == ',' && !inQuotes -> {
                    args.add(sb.toString().trim()); sb.clear(); i += 1; continue
                }
                else -> { sb.append(c); i += 1 }
            }
        }
        if (sb.isNotEmpty()) args.add(sb.toString().trim())
        return args
    }

    /**
     * Executes a single tool call and returns the textual result.
     */
    fun execute(call: ToolCall): String {
        thisLogger().info("[tool] executing ${call.name} args=${call.args}")
        return try {
            when (call.name) {
                "read_file"   -> readFile(call.args)
                "write_file"  -> writeFile(call.args)
                "edit_file"   -> editFile(call.args)
                "list_files"  -> listFiles(call.args)
                "bash"        -> runBash(call.args)
                "search_text" -> searchText(call.args)
                else          -> "Unknown tool: ${call.name}."
            }
        } catch (e: Exception) {
            thisLogger().warn("[tool] failed: ${e.message}", e)
            "Error executing ${call.name}: ${e.message}"
        }
    }

    // ---- tools -----------------------------------------------------------

    private fun resolvePath(path: String): File {
        val base = project.basePath ?: return File(path)
        val f = File(path)
        return if (f.isAbsolute) f else File(base, path)
    }

    private fun looksLikePlaceholder(path: String): Boolean {
        val p = path.trim()
        if (p.isEmpty()) return true
        val lowered = p.lowercase()
        return when {
            p == "..." || p == ".." || p == "." -> true
            p == "/" -> true
            lowered == "path" || lowered == "file" || lowered == "filename" -> true
            lowered == "your_path" || lowered == "your_file" || lowered == "your-path" -> true
            lowered == "placeholder" || lowered == "placeholder.txt" -> true
            lowered.startsWith("<") && lowered.endsWith(">") -> true
            lowered.startsWith("path/") || lowered.startsWith("path\\") -> true
            lowered.startsWith("your_") || lowered.startsWith("your-") -> true
            p.startsWith("\\") -> true
            !p.any { it.isLetterOrDigit() } -> true
            else -> false
        }
    }

    private fun readFile(args: List<String>): String {
        val path = args.getOrNull(0) ?: return "read_file: missing path. Specify a concrete file, e.g. read_file(\"package.json\")."
        if (looksLikePlaceholder(path)) return "read_file: path \"" + path + "\" looks like a placeholder, not a real path. Call list_files(\".\") or search_text to find a real file first."
        val f = resolvePath(path)
        if (!f.exists()) return "File not found: $path"
        if (!f.isFile) return "Not a file: $path"

        val text = f.readText()
        val rangeArg = args.getOrNull(1)

        // Диапазон строк: read_file("path", "120-180")
        if (!rangeArg.isNullOrBlank()) {
            val m = Regex("""^\s*(\d+)\s*-\s*(\d+)\s*$""").find(rangeArg)
            if (m != null) {
                val start = m.groupValues[1].toInt().coerceAtLeast(1)
                val end = m.groupValues[2].toInt().coerceAtLeast(start)
                val lines = text.split('\n')
                val from = (start - 1).coerceAtMost(lines.size)
                val to = end.coerceAtMost(lines.size)
                return "read_file: $path lines $start-$end (total ${lines.size} lines):\n" +
                    lines.subList(from, to).joinToString("\n")
            }
        }

        if (text.length > 20_000) {
            val totalLines = text.count { it == '\n' } + 1
            return buildString {
                append("read_file: $path is ${text.length} chars, $totalLines lines. ")
                append("Showing first 20 000. ")
                append("To read more, use read_file(\"$path\", \"startLine-endLine\").\n")
                append("--- beginning ---\n")
                append(text.take(20_000))
                append("\n... [truncated]")
            }
        }
        return text
    }

    /**
     * Full rewrite of a file. Protected against accidental giant writes.
     * For edits to existing files, prefer [editFile].
     */
    private fun writeFile(args: List<String>): String {
        if (args.size < 2) {
            val got = args.joinToString(", ") { "\"" + it.take(40) + "\"" }
            return "write_file: expected (path, content[, \"append\"]) but got ${args.size} arg(s): [$got]. " +
                "Wrap every argument in double quotes and separate with commas."
        }
        val path = args[0]
        val content = args[1]
        val mode = args.getOrNull(2)?.lowercase() ?: "overwrite"
        if (looksLikePlaceholder(path)) return "write_file: path \"" + path + "\" looks like a placeholder. Use a concrete path."

        if (content.length > MAX_WRITE_CHARS) {
            return buildString {
                append("write_file: REFUSED — content is ${content.length} chars, ")
                append("limit is $MAX_WRITE_CHARS. ")
                append("Do NOT rewrite whole files. To modify an existing file, ")
                append("use edit_file(path, old_string, new_string) with a small ")
                append("unique fragment. For large NEW files, split into chunks: ")
                append("write_file(path, chunk1) then write_file(path, chunk2, \"append\") etc. ")
                append("Or use edit_file for targeted changes.")
            }
        }

        val f = resolvePath(path)
        f.parentFile?.mkdirs()
        when (mode) {
            "append" -> {
                // Если файл не пустой и не заканчивается на \n — добавляем \n перед новым контентом,
                // чтобы чанки не слипались в одну строку.
                if (f.exists() && f.length() > 0L) {
                    val tail = f.readText().takeLast(1)
                    if (tail != "\n") f.appendText("\n")
                }
                f.appendText(content)
            }
            else -> f.writeText(content)
        }
        return "Wrote ${content.length} chars to $path (mode=$mode)"
    }

    /**
     * Targeted replacement in an existing file:
     *   edit_file("path", "old_string", "new_string")        — replace first occurrence
     *   edit_file("path", "old_string", "new_string", "all") — replace all occurrences
     *
     * Refuses if `old_string` is empty, not found, or (without "all") ambiguous.
     */
    private fun editFile(args: List<String>): String {
        if (args.size < 3) {
            val got = args.joinToString(", ") { "\"" + it.take(40) + "\"" }
            return "edit_file: expected (path, old_string, new_string[, \"all\"]) but got ${args.size} arg(s): [$got]. " +
                "Wrap every argument in double quotes and separate with commas."
        }
        val path = args[0]
        val oldStr = args[1]
        val newStr = args[2]
        val replaceAll = args.getOrNull(3)?.equals("all", ignoreCase = true) == true

        if (oldStr.isEmpty()) return "edit_file: old_string must not be empty"
        if (looksLikePlaceholder(path)) return "edit_file: path \"" + path + "\" looks like a placeholder. Use a concrete path."

        val f = resolvePath(path)
        if (!f.exists()) return "File not found: $path"
        if (!f.isFile) return "Not a file: $path"

        val original = f.readText()
        val count = countOccurrences(original, oldStr)

        if (count == 0) {
            return "edit_file: old_string not found in $path. " +
                    "Make sure you copied it exactly (whitespace and quotes matter)."
        }
        if (count > 1 && !replaceAll) {
            return "edit_file: old_string appears $count times in $path. " +
                    "Either include more surrounding context to make it unique, " +
                    "or pass \"all\" as the 4th argument."
        }

        val updated = if (replaceAll) {
            original.replace(oldStr, newStr)
        } else {
            original.replaceFirst(oldStr, newStr)
        }
        f.writeText(updated)

        val applied = if (replaceAll) count else 1
        return "Edited $path: replaced $applied occurrence(s); " +
                "${oldStr.length} -> ${newStr.length} chars per occurrence."
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx != -1) {
            count++
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return count
    }

    private fun listFiles(args: List<String>): String {
        val path = args.getOrNull(0) ?: "."
        if (looksLikePlaceholder(path) && path != "." && path != "./") return "list_files: path \"" + path + "\" looks like a placeholder. Use \".\" for the project root."
        val f = resolvePath(path)
        if (!f.exists()) return "Directory not found: $path"
        if (!f.isDirectory) return "Not a directory: $path"
        return f.listFiles()?.joinToString("\n") { it.name + if (it.isDirectory) "/" else "" }
            ?: "(empty)"
    }

    private fun runBash(args: List<String>): String {
        val cmd = args.getOrNull(0) ?: return "bash: missing command"

        // Жёсткий стоп-лист на опасные команды. Это не полная защита —
        // обойти можно (base64, скрипты, aliases). Но от лобовых rm -rf /
        // и подобных уберегает.
        val dangerous = listOf(
            Regex("""\brm\s+(-\w+\s+)*rf\s+/(\s|$|\*)"""),           // rm -rf / и rm -rf /*
            Regex("""\brm\s+(-\w+\s+)*rf\s+~"""),                     // rm -rf ~
            Regex("""\brm\s+(-\w+\s+)*rf\s+\${'$'}HOME"""),           // rm -rf $HOME
            Regex("""\bmkfs(\.[a-z0-9]+)?\b"""),                       // mkfs, mkfs.ext4
            Regex("""\bdd\b.*\bof=/dev/"""),                           // dd of=/dev/sda
            Regex(""">\s*/dev/sd[a-z]"""),                              // > /dev/sda
            Regex(""":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;?\s*:"""),    // fork bomb
            Regex("""\bchmod\s+-R\s+000\s+/(\s|$)"""),                  // chmod -R 000 /
            Regex("""\bchown\s+-R\s+.*\s+/(\s|$)"""),                   // chown -R /
            Regex("""\bshutdown\b|\breboot\b|\bhalt\b|\bpoweroff\b"""),
            Regex("""\bkill\s+-9\s+-1\b"""),
            Regex("""\bcrontab\s+-r\b"""),
            Regex("""\bmv\s+.*\s+/dev/null\b"""),                      // отправить файлы в никуда
        )
        for (rx in dangerous) {
            if (rx.containsMatchIn(cmd)) {
                thisLogger().warn("[tool] bash BLOCKED dangerous command: $cmd")
                return "bash: REFUSED — command matches a dangerous pattern. " +
                        "Если это действительно нужно — попроси пользователя выполнить это вручную."
            }
        }

        val pb = ProcessBuilder("bash", "-c", cmd)
        pb.directory(project.basePath?.let { File(it) })
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        return "exit=$exit\n$output"
    }

    private fun searchText(args: List<String>): String {
        val needle = args.getOrNull(0) ?: return "search_text: missing needle"
        val base = project.basePath?.let { File(it) } ?: return "no project base"
        val hits = mutableListOf<String>()
        base.walkTopDown()
            .onEnter { it.name != ".git" && it.name != "node_modules" && it.name != "build" && it.name != ".next" }
            .filter { it.isFile && it.length() < 1_000_000 }
            .forEach { f ->
                f.useLines { lines ->
                    lines.forEachIndexed { idx, line ->
                        if (line.contains(needle)) {
                            hits.add("${f.relativeTo(base)}:${idx + 1}: $line")
                        }
                    }
                }
            }
        return if (hits.isEmpty()) "No matches for: $needle"
        else hits.take(100).joinToString("\n") + if (hits.size > 100) "\n... (${hits.size - 100} more)" else ""
    }

    companion object {
        /** Hard cap on a single write_file call. Beyond this we suggest edit_file. */
        private const val MAX_WRITE_CHARS = 5_000_000

        /** Only these tool names are actually executed. */
        private val KNOWN_TOOLS = setOf(
            "read_file",
            "write_file",
            "edit_file",
            "list_files",
            "bash",
            "search_text",
        )
    }
}