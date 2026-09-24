package ru.ichuk.deepseek

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Парсит и выполняет вызовы [TOOL: name(args)], поступающие из веб-чата DeepSeek.
 */
class ToolRunner(private val project: Project) {

    data class ToolCall(val name: String, val args: List<String>)

    // ---------- Парсер ----------

    private class Parsed(val args: List<String>, val end: Int)

    fun parseToolCalls(text: String): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        val marker = "[TOOL:"
        var idx = 0

        while (idx < text.length && calls.size < MAX_CALLS_PER_MESSAGE) {
            val start = text.indexOf(marker, idx)
            if (start < 0) break

            val nameStart = start + marker.length
            val parenOpen = text.indexOf('(', nameStart)
            if (parenOpen < 0) break

            val name = text.substring(nameStart, parenOpen).trim()
            if (name !in KNOWN_TOOLS || name.length > 32) {
                idx = nameStart
                continue
            }

            val parsed = parseArgs(text, parenOpen + 1)
            if (parsed == null) {
                idx = parenOpen + 1
                continue
            }

            calls.add(ToolCall(name, parsed.args))
            idx = parsed.end
        }
        return calls
    }

    /**
     * Читает аргументы до парной ')' на нулевом уровне вложенности.
     * Поддерживает одинарные и двойные кавычки, экранирование, скобки и запятые
     * внутри строк. Содержимое кавычек НЕ тримится (важно для edit_file).
     */
    private fun parseArgs(text: String, from: Int): Parsed? {
        val args = ArrayList<String>()
        val buf = StringBuilder()
        val pendingWs = StringBuilder()
        var quote: Char? = null
        var depth = 0
        var hasArg = false
        var closed = -1
        var i = from

        fun flush() {
            args.add(buf.toString())
            buf.setLength(0)
            pendingWs.setLength(0)
        }

        while (i < text.length) {
            val c = text[i]

            if (quote != null) {
                if (c == '\\' && i + 1 < text.length) {
                    when (val n = text[i + 1]) {
                        'n' -> buf.append('\n')
                        't' -> buf.append('\t')
                        'r' -> buf.append('\r')
                        '\\' -> buf.append('\\')
                        '"' -> buf.append('"')
                        '\'' -> buf.append('\'')
                        '`' -> buf.append('`')
                        else -> { buf.append('\\'); buf.append(n) }
                    }
                    i += 2
                    continue
                }
                if (c == quote) { quote = null; i++; continue }
                buf.append(c); i++
                continue
            }

            when {
                c == '"' || c == '\'' -> { pendingWs.setLength(0); quote = c; hasArg = true; i++ }
                c == '(' -> { buf.append(pendingWs); pendingWs.setLength(0); depth++; buf.append(c); hasArg = true; i++ }
                c == ')' -> {
                    if (depth == 0) { closed = i; i++; break }
                    buf.append(pendingWs); pendingWs.setLength(0); depth--; buf.append(c); i++
                }
                c == ',' && depth == 0 -> { flush(); hasArg = false; i++ }
                c.isWhitespace() -> { if (buf.isNotEmpty()) pendingWs.append(c); i++ }
                else -> { buf.append(pendingWs); pendingWs.setLength(0); buf.append(c); hasArg = true; i++ }
            }
        }

        if (closed < 0) return null          // незакрытая скобка/кавычка — вызов игнорируем
        if (hasArg) flush()

        // пропускаем необязательную ']'
        var end = closed + 1
        while (end < text.length && text[end] == ' ') end++
        if (end < text.length && text[end] == ']') end++
        return Parsed(args, end)
    }

    // ---------- Выполнение ----------

    fun execute(call: ToolCall): String {
        thisLogger().info("[tool] executing ${call.name}, args=${call.args.size}")
        return try {
            saveOpenDocuments()
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
            "Error executing ${call.name}: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun saveOpenDocuments() {
        val app = ApplicationManager.getApplication()
        val action = Runnable { FileDocumentManager.getInstance().saveAllDocuments() }
        if (app.isDispatchThread) action.run() else app.invokeAndWait(action)
    }

    private fun refreshVfs(f: File) {
        try {
            val lfs = LocalFileSystem.getInstance()
            val vf = lfs.refreshAndFindFileByIoFile(f)
                ?: f.parentFile?.let { lfs.refreshAndFindFileByIoFile(it) }
            vf?.refresh(true, false)
        } catch (e: Exception) {
            thisLogger().warn("[tool] vfs refresh failed: ${e.message}")
        }
    }

    private fun baseDir(): File? = project.basePath?.let { File(it) }

    private fun resolvePath(path: String): File {
        val base = project.basePath ?: return File(path)
        val f = File(path)
        return (if (f.isAbsolute) f else File(base, path)).normalize()
    }

    /** Запись разрешена только внутри корня проекта. */
    private fun outsideProject(f: File): Boolean {
        val base = baseDir()?.normalize() ?: return false
        return !f.normalize().absolutePath.startsWith(base.absolutePath)
    }

    private fun looksLikePlaceholder(path: String): Boolean {
        val p = path.trim()
        if (p.isEmpty()) return true
        val lowered = p.lowercase()
        return when {
            p == "." || p == "./" || p == ".." -> false
            lowered in setOf("path", "file", "filename", "your_path", "your_file",
                "your-path", "placeholder", "placeholder.txt") -> true
            lowered.startsWith("<") && lowered.endsWith(">") -> true
            lowered.startsWith("path/") || lowered.startsWith("path\\") -> true
            lowered.startsWith("your_") || lowered.startsWith("your-") -> true
            !p.any { it.isLetterOrDigit() } -> true
            else -> false
        }
    }

    private fun readFile(args: List<String>): String {
        val path = args.getOrNull(0) ?: return "read_file: missing path argument."
        if (looksLikePlaceholder(path)) return "read_file: path \"$path\" looks like a placeholder."
        val f = resolvePath(path)
        if (!f.exists()) return "File not found: $path"
        if (!f.isFile) return "Not a file: $path"
        if (f.length() > 10_000_000L) return "read_file: file is too large (${f.length()} bytes)."

        val text = f.readText()
        val rangeArg = args.getOrNull(1)

        if (!rangeArg.isNullOrBlank()) {
            val m = Regex("""^\s*(\d+)\s*-\s*(\d+)\s*$""").find(rangeArg)
            if (m != null) {
                val lines = text.split('\n')
                val start = m.groupValues[1].toInt().coerceAtLeast(1)
                val end = m.groupValues[2].toInt().coerceAtLeast(start)
                val from = (start - 1).coerceAtMost(lines.size)
                val to = end.coerceIn(from, lines.size)
                return "read_file: $path lines $start-$end (total ${lines.size}):\n" +
                        lines.subList(from, to).joinToString("\n")
            }
            return "read_file: bad range \"$rangeArg\", expected \"startLine-endLine\"."
        }

        if (text.length > MAX_READ_CHARS) {
            val totalLines = text.count { it == '\n' } + 1
            return buildString {
                append("read_file: $path is ${text.length} chars, $totalLines lines. ")
                append("Showing first $MAX_READ_CHARS chars. ")
                append("Use read_file(\"$path\", \"startLine-endLine\") for the rest.\n")
                append("--- beginning ---\n")
                append(text.take(MAX_READ_CHARS))
                append("\n... [truncated]")
            }
        }
        return text
    }

    private fun writeFile(args: List<String>): String {
        if (args.size < 2) return "write_file: expected (path, content[, \"append\"]) but got ${args.size} args."
        val path = args[0]
        val content = args[1]
        val mode = args.getOrNull(2)?.lowercase() ?: "overwrite"
        if (looksLikePlaceholder(path)) return "write_file: path \"$path\" looks like a placeholder."
        if (content.length > MAX_WRITE_CHARS) {
            return "write_file: REFUSED — content is ${content.length} chars, limit is $MAX_WRITE_CHARS."
        }

        val f = resolvePath(path)
        if (outsideProject(f)) return "write_file: REFUSED — path is outside the project root."

        f.parentFile?.mkdirs()
        if (mode == "append") {
            if (f.exists() && f.length() > 0L && !f.readText().endsWith("\n")) f.appendText("\n")
            f.appendText(content)
        } else {
            f.writeText(content)
        }
        refreshVfs(f)
        return "Wrote ${content.length} chars to $path (mode=$mode)"
    }

    private fun editFile(args: List<String>): String {
        if (args.size < 3) return "edit_file: expected (path, old_string, new_string[, \"all\"])."
        val path = args[0]
        val oldStr = args[1]
        val newStr = args[2]
        val replaceAll = args.getOrNull(3)?.equals("all", ignoreCase = true) == true

        if (oldStr.isEmpty()) return "edit_file: old_string must not be empty."
        if (oldStr == newStr) return "edit_file: old_string equals new_string, nothing to do."
        if (looksLikePlaceholder(path)) return "edit_file: path \"$path\" looks like a placeholder."

        val f = resolvePath(path)
        if (outsideProject(f)) return "edit_file: REFUSED — path is outside the project root."
        if (!f.exists()) return "File not found: $path"
        if (!f.isFile) return "Not a file: $path"

        // Нормализуем CRLF, иначе поиск подстроки постоянно промахивается.
        val original = f.readText()
        val normalized = original.replace("\r\n", "\n")
        val needle = oldStr.replace("\r\n", "\n")
        val count = countOccurrences(normalized, needle)

        if (count == 0) return "edit_file: old_string not found in $path. Check exact whitespace, indentation and quotes."
        if (count > 1 && !replaceAll) {
            return "edit_file: old_string appears $count times in $path. Add more context or pass \"all\"."
        }

        val updated = if (replaceAll) normalized.replace(needle, newStr.replace("\r\n", "\n"))
        else normalized.replaceFirst(needle, newStr.replace("\r\n", "\n"))

        val restoreCrlf = original.contains("\r\n")
        f.writeText(if (restoreCrlf) updated.replace("\n", "\r\n") else updated)
        refreshVfs(f)

        return "Edited $path: replaced ${if (replaceAll) count else 1} occurrence(s)."
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
        val path = args.getOrNull(0)?.ifBlank { "." } ?: "."
        val f = resolvePath(path)
        if (!f.exists()) return "Directory not found: $path"
        if (!f.isDirectory) return "Not a directory: $path"

        val entries = f.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        ) ?: return "(unable to list $path)"
        if (entries.isEmpty()) return "(empty)"

        val shown = entries.take(MAX_LIST_ENTRIES)
            .joinToString("\n") { it.name + if (it.isDirectory) "/" else "  (${it.length()} b)" }
        return if (entries.size > MAX_LIST_ENTRIES)
            "$shown\n... (${entries.size - MAX_LIST_ENTRIES} more entries)" else shown
    }

    private fun runBash(args: List<String>): String {
        val cmd = args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return "bash: missing command"

        for (rx in DANGEROUS) {
            if (rx.containsMatchIn(cmd)) return "bash: REFUSED — dangerous command pattern detected."
        }

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val pb = if (isWindows) {
            val hasBash = try {
                ProcessBuilder("where", "bash").start().waitFor(5, TimeUnit.SECONDS)
            } catch (_: Exception) { false }
            if (hasBash) ProcessBuilder("bash", "-c", cmd)
            else ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", cmd)
        } else {
            ProcessBuilder("bash", "-lc", cmd)
        }

        pb.directory(baseDir())
        pb.redirectErrorStream(true)
        pb.environment()["TERM"] = "dumb"
        pb.environment()["NO_COLOR"] = "1"

        val proc = pb.start()
        // Закрываем stdin: иначе команда, ждущая ввода, висит до таймаута.
        try { proc.outputStream.close() } catch (_: Exception) {}

        // Читаем вывод ПАРАЛЛЕЛЬНО с ожиданием — иначе дедлок на заполненном пайпе.
        val out = StringBuilder()
        var truncated = false
        val reader = Thread({
            try {
                proc.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(8192)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(out) {
                            if (out.length < MAX_BASH_OUTPUT) out.append(buf, 0, n) else truncated = true
                        }
                    }
                }
            } catch (_: Exception) {}
        }, "deepseek-bash-reader").apply { isDaemon = true; start() }

        val finished = proc.waitFor(BASH_TIMEOUT_SEC, TimeUnit.SECONDS)
        if (!finished) {
            try { proc.descendants().forEach { it.destroyForcibly() } } catch (_: Exception) {}
            proc.destroyForcibly()
            reader.join(2000)
            val partial = synchronized(out) { out.toString() }
            return "bash: TIMEOUT after $BASH_TIMEOUT_SEC s (process killed).\n--- partial output ---\n" +
                    partial.take(MAX_BASH_OUTPUT)
        }

        reader.join(5000)
        val text = synchronized(out) { out.toString() }
        return buildString {
            append("exit=").append(proc.exitValue()).append('\n')
            append(text.take(MAX_BASH_OUTPUT))
            if (truncated) append("\n... [output truncated at $MAX_BASH_OUTPUT chars]")
        }
    }

    private fun searchText(args: List<String>): String {
        val needle = args.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return "search_text: missing needle"
        val base = baseDir() ?: return "search_text: no project base"
        val hits = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + SEARCH_TIMEOUT_MS
        var timedOut = false

        val it = base.walkTopDown()
            .onEnter { dir -> dir.name !in IGNORED_DIRS && !dir.name.startsWith(".") }
            .filter { it.isFile && it.length() in 1..500_000 }
            .iterator()

        while (it.hasNext()) {
            if (hits.size >= MAX_HITS) break
            if (System.currentTimeMillis() > deadline) { timedOut = true; break }
            val f = it.next()
            if (f.extension.lowercase() in BINARY_EXT) continue
            try {
                f.useLines { lines ->
                    var n = 0
                    for (line in lines) {
                        n++
                        if (line.contains(needle)) {
                            hits.add("${f.relativeTo(base)}:$n: ${line.trim().take(200)}")
                            if (hits.size >= MAX_HITS) return@useLines
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (hits.isEmpty()) return "No matches for: $needle" + if (timedOut) " (search timed out)" else ""
        return hits.joinToString("\n") +
                (if (hits.size >= MAX_HITS) "\n... (capped at $MAX_HITS results)" else "") +
                (if (timedOut) "\n... (search timed out after ${SEARCH_TIMEOUT_MS}ms)" else "")
    }

    companion object {
        private const val MAX_WRITE_CHARS = 5_000_000
        private const val MAX_READ_CHARS = 20_000
        private const val MAX_BASH_OUTPUT = 40_000
        private const val MAX_LIST_ENTRIES = 400
        private const val MAX_HITS = 100
        private const val MAX_CALLS_PER_MESSAGE = 20
        private const val BASH_TIMEOUT_SEC = 90L
        private const val SEARCH_TIMEOUT_MS = 5_000L

        val KNOWN_TOOLS = setOf(
            "read_file", "write_file", "edit_file", "list_files", "bash", "search_text"
        )

        private val IGNORED_DIRS = setOf(
            ".git", "node_modules", "build", ".next", ".gradle", ".idea",
            ".intellijPlatform", ".kotlin", ".run", ".ai", "out", "target", "dist"
        )

        private val BINARY_EXT = setOf(
            "jar", "class", "png", "jpg", "jpeg", "gif", "ico", "pdf", "zip",
            "gz", "tar", "exe", "dll", "so", "dylib", "bin", "woff", "woff2", "ttf"
        )

        private val DANGEROUS = listOf(
            Regex("""\brm\s+(-\w+\s+)*-?rf\s+/(\s|$|\*)"""),
            Regex("""\brm\s+(-\w+\s+)*-?rf\s+~"""),
            Regex("""\brm\s+(-\w+\s+)*-?rf\s+\${'$'}HOME"""),
            Regex("""\bmkfs(\.[a-z0-9]+)?\b"""),
            Regex("""\bdd\b.*\bof=/dev/"""),
            Regex(""">\s*/dev/sd[a-z]"""),
            Regex(""":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;?\s*:"""),
            Regex("""\bshutdown\b|\breboot\b|\bpoweroff\b""")
        )
    }
}
