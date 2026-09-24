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
        return if (text.length > 20_000) {
            text.take(20_000) + "\n... [truncated, ${text.length} total chars]"
        } else text
    }

    /**
     * Full rewrite of a file. Protected against accidental giant writes.
     * For edits to existing files, prefer [editFile].
     */
    private fun writeFile(args: List<String>): String {
        if (args.size < 2) return "write_file: expected (path, content)"
        val path = args[0]
        val content = args[1]
        if (looksLikePlaceholder(path)) return "write_file: path \"" + path + "\" looks like a placeholder. Use a concrete path."

        if (content.length > MAX_WRITE_CHARS) {
            return buildString {
                append("write_file: REFUSED — content is ${content.length} chars, ")
                append("limit is $MAX_WRITE_CHARS. ")
                append("Do NOT rewrite whole files. To modify an existing file, ")
                append("use edit_file(path, old_string, new_string) with a small ")
                append("unique fragment. For brand-new files keep them small. ")
                append("If the file is large, inspect it with read_file / search_text, ")
                append("then patch it with edit_file.")
            }
        }

        val f = resolvePath(path)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return "Wrote ${content.length} chars to $path"
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
            return "edit_file: expected (path, old_string, new_string[, \"all\"])"
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
        private const val MAX_WRITE_CHARS = 500_000

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