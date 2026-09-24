# DeepSeek Agent

An IntelliJ IDEA plugin that embeds [chat.deepseek.com](https://chat.deepseek.com) into a tool window and gives the model real access to the project: reading, editing, searching, running shell commands.

## Tools

| Tool | Signature | Notes |
| --- | --- | --- |
| `read_file` | `read_file("path"[, "startLine-endLine"])` | Files over 10 MB refused; output over 20 000 chars truncated, range form suggested. |
| `edit_file` | `edit_file("path", "old", "new"[, "all"])` | In-place replacement; refuses empty/ambiguous/missing old string; normalizes CRLF. |
| `write_file` | `write_file("path", "content"[, "append"])` | Full rewrite up to 5 MB, or append (auto `\n`). Refuses paths outside the project. |
| `list_files` | `list_files("path")` | Capped at 400 entries. |
| `search_text` | `search_text("needle")` | Max 100 hits / 5 s. Skips `.git`, `node_modules`, `build`, `.next`, `.gradle`, `.idea`, `.intellijPlatform`, `.kotlin`, `.run`, `.ai`, `out`, `target`, `dist`, binary files. |
| `bash` | `bash("command")` | Project root; output capped at 40 000 chars; timeout 90 s. |

Batch calls: up to 20 tools per turn.

## Batch results

All calls from one assistant turn run together and come back as a single `[TOOL_RESULT: ...]` message (capped at 60 000 chars). `bash` calls run in parallel; file operations run sequentially; output is in call order.

## System preamble

On the first message of each chat the plugin prepends a prompt that lists the tools, explains the `tool` code-fence syntax, prefers `edit_file` over `write_file`, and forbids placeholder paths.

## Safety

- Refused `bash` patterns: `rm -rf /`, `rm -rf ~`, `rm -rf $HOME`, `mkfs`, `dd ... of=/dev/...`, `> /dev/sd*`, fork bombs, `shutdown` / `reboot` / `poweroff`.
- `write_file` refuses content over 5 MB and paths outside the project root; `edit_file` also refuses outside paths.
- Placeholder-like paths (`...`, `path`, `<path>`, `your_file`, ...) are rejected by `read_file`, `write_file`, `edit_file`, `list_files`.

## Auto-loop

The injected script polls the DOM (700 ms) and treats a message as final after 1200 ms of no changes. When it finds a `[TOOL: ...]` call in a `tool`-tagged block, it dispatches it to Kotlin, then inserts the result back.

Delivery is a small state machine (`idle | stream | tools | send | verify | cooldown`). After sending, the script waits up to `verifyMs = 3500` ms for the input to clear; on failure it enters `cooldown` and either clicks the chat's retry button or re-sends the text. Safeguards:

- `minSendInterval = 2500` ms, raised to `rlBackoffMs = 7000` after a rate limit and decayed by 1000 ms on each success,
- `maxIterations = 60`, `maxSameCall = 3`,
- rate limit → pause `rlWaitMs = 20000` ms; non-limit send error → pause `failWaitMs = 3000` ms,
- `rlMaxWaits = 8` — after that the loop aborts with “лимит не спадает”,
- `toolTimeoutMs = 180000` — unlock if Kotlin never answered,
- `window.__dsRetry()` to resume manually.

## Architecture

- JCEF browser hosted by `MyToolWindowFactory`; `ToolRunner` executes calls.
- `JBCefJSQuery` bridge: `window.__dsSendToKotlin` → Kotlin handler → `runToolBatch`.
- Injected script `window.__dsAgentV4` polls (`tick` / `poll`) and extracts `[TOOL: ...]` from `tool`-tagged `<pre>` blocks.
- Results are Base64-encoded, returned via `window.__dsInsertToolResult`.
- Relative paths resolve against the current project.
