# DeepSeek Agent

An IntelliJ IDEA plugin that embeds [chat.deepseek.com](https://chat.deepseek.com) into a tool window and gives the web model **real access to your project**: reading files, editing them in place, searching, running shell commands — and feeding the results back into the chat.

The model stays where it is. The IDE does the work.

---

## Features

### Tools the model can call

| Tool | Signature | What it does |
|------|-----------|--------------|
| `read_file` | `read_file("path")` | Reads a file (up to 20 000 chars). |
| `edit_file` | `edit_file("path", "old", "new"[, "all"])` | Targeted in-place replacement. Refuses on empty / ambiguous / missing `old` string. |
| `write_file` | `write_file("path", "content")` | Full rewrite. Capped at 500 KB — larger writes are refused and redirected to `edit_file`. |
| `list_files` | `list_files("path")` | Lists a directory. |
| `search_text` | `search_text("needle")` | Grep-like search across the project. Skips `.git`, `node_modules`, `build`, `.next`. |
| `bash` | `bash("command")` | Runs a shell command in the project root. |

Batch calls are supported — several tools per assistant turn.

### Asynchronous tool results

Each result is delivered to the chat **as soon as the corresponding tool finishes** — not as one batch at the end. A fast `read_file` returns immediately while a slow `npm install` keeps running in the background; the model sees the fast result first and can start reasoning about it before the slow one arrives.

Results arrive in **completion order**, not call order. Between two consecutive results there is a 1.5 s gap so that DeepSeek accepts each message separately.

### Plan mode

Prefix the first message with `plan:` (or `/plan`, or `план:`) and the model will produce a step-by-step plan instead of executing tools. It waits for a confirmation word (`да`, `поехали`, `выполняй`, `ok`, `yes`, `go`) before acting.

### System preamble

On the first message of every new chat, the plugin automatically prepends a system prompt that tells the model:

- which tools exist,
- how to call them (fenced in ` ```tool ... ``` ` so Markdown survives the web UI),
- how to edit files (prefer `edit_file` over `write_file`),
- how to behave in plan mode,
- and forbids placeholders like `[TOOL: name(...