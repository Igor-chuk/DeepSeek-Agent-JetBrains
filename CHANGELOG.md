# DeepSeek Agent — Changelog

All notable changes to this plugin are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.2.4] — 2026-09-24

### Added
- Delivery state machine in the injected script: `idle | stream | tools | send | verify | cooldown`. After each send the script waits `verifyMs` (3500 ms) for the input to clear and, on failure, enters a cooldown instead of silently losing the message.
- Automatic recovery from send errors: the script looks for the chat's retry button (`findFailedSend`) and clicks it, or re-sends the text as a fallback. Failed items are remembered in `handledErr` so the same error does not loop forever.
- Adaptive send interval: `minInterval` starts at `minSendInterval` (2500 ms), jumps to `rlBackoffMs` (7000 ms) after a rate limit, and decays by 1000 ms on each successful send.
- Separate cooldown durations: `rlWaitMs` (20000 ms) for rate limits and `failWaitMs` (3000 ms) for non-limit send errors. `rlMaxWaits` is now 8.
- `window.__dsRetry()` for manual recovery from the console.

### Changed
- Tool results are batched again. All [TOOL: ...] calls from one assistant turn are executed together, and their results are sent back as a single message. bash commands still run in parallel with each other; file operations run sequentially.
- The 1.5 s inter-message delay and completion-order delivery introduced in 1.2.0 are gone along with the streaming sender.
- Injected script renamed `__dsAgentV3` → `__dsAgentV4`.
- README brought fully in line with the current code: tool limits, safety notes, `__dsSendToKotlin` / `__dsAgentV4` architecture, and the new auto-loop parameters.

### Fixed
- README.md had been truncated mid-sentence at the end of the System preamble section. The file is now complete and consistent with the current code.
- README.md no longer advertises features that are not in the codebase: the “Asynchronous tool results” and “Plan mode” sections were removed, and the tool table, safety notes and auto-loop parameters were brought in line with ToolRunner.kt and MyToolWindowFactory.kt.
- search_text skip-list in the README now also lists .gradle.

### Removed
- “Asynchronous tool results” and “Plan mode” sections from the README.

## [1.2.3] — 2026-09-24

### Added
- `read_file(path, "startLine-endLine")` — read a specific range of lines. For large files, `read_file(path)` now returns the beginning plus a hint to use the range form.
- `write_file(path, content, "append")` — append to a file. Automatically inserts `\n` between old and new content if the file didn't end with a newline, so chunks don't merge into one line.
- Pre-amble: `АСИНХРОННЫЕ РЕЗУЛЬТАТЫ` block, updated `read_file` / `write_file` descriptions with range and append forms, and a note that `append` adds `\n` automatically.
- `isMessageFinalized` now also checks DOM quiescence: if a message hasn't mutated in 2.5 seconds, it is considered finalized even when the bottom toolbar isn't rendered yet.

### Changed
- `MAX_WRITE_CHARS` raised from 500 KB to 5 MB.

## [1.2.1] — 2026-09-24

### Fixed
- `isMessageFinalized` now iterates over **all** `div.ds-flex[style*="gap: 10px"]` blocks in the message instead of only the first one. The first match was often the small code-block toolbar (2 buttons) rather than the message-level toolbar (6 buttons), which caused two-` ```tool ``` `-block messages to be silently ignored.
- Threshold raised from 3 to 5 buttons, so the code-block toolbar (Copy + Download) is not confused with the message toolbar (Copy / Regenerate / Like / Dislike / Read aloud / Share).

## [1.2.0] — 2026-09-24

### Added
- **Streaming results.** Each tool result is now sent to the chat as soon as it completes, instead of waiting for the whole batch. A slow `sleep 30` no longer blocks a fast `sleep 10` — the fast result reaches the model first, and the model can start working on it while the other call is still running.
- Results are delivered in **completion order**, not call order.
- Results are queued through a single-thread sender with a 1.5 s delay between messages, so DeepSeek accepts each one separately.

### Changed
- `CompletableFuture.allOf(...).join()` on the EDT is gone. The Kotlin side never blocks the UI thread anymore; all batching, waiting and JavaScript execution happens on background threads.

## [1.1.9] — 2026-09-24

### Changed
- Bumped version to refresh plugin metadata in the IDE plugin page.

## [1.1.2] — 2026-09-24

### Added
- **Automatic retry after rate limit.** When DeepSeek responds with `Too frequent messages` / `Слишком частые сообщения` / `Server is busy`, the auto-loop now pauses for 30 seconds and **re-sends the lost `[TOOL_RESULT]`** without user intervention.
- Extended rate-limit detection patterns: `Too many requests, please try again later`, `Попробуйте позже`, `Server is busy`, `Server busy`, `Слишком много запросов`.
- White sidebar icon (`src/main/resources/META-INF/icon.svg`) shown regardless of theme or tool-window state.
- Expanded `<description>` in `plugin.xml` listing all tools and features.
- Structured `<change-notes>` in `plugin.xml`.

### Changed
- Tool-window icon now points to `icon.svg` instead of the default `AllIcons.Actions.SidePanel`.
- Rate-limit cooldown replaces the previous hard stop; the loop unblocks itself and resets its iteration counter.

### Fixed
- Lost `[TOOL_RESULT]` after a rate-limit response: the pending payload is now cached in `pendingResultText` and re-sent after cooldown.

## [1.1.1] — 2026-09-24

### Added
- 30-second cooldown on rate-limit detection.
- Wider set of rate-limit patterns.
- Placeholder path filter in `ToolRunner` — `read_file`, `write_file`, `edit_file`, `list_files` refuse paths like `...`, `path`, `<path>`, `your_file` and suggest `list_files(".")` instead.
- Whitelist of known tools in `parseToolCalls`.

### Fixed
- Immediate `autoLoopStopped = true` on rate limit now replaced with a resumable pause.

## [1.1.0] — 2026-09-23

### Added
- Targeted `edit_file(path, old_string, new_string[, "all"])` tool.
- Batch tool calls: multiple `[TOOL: ...]` invocations per assistant turn.
- System preamble automatically prepended to the first message of every new chat.
- Automatic `watchdog` that re-baselines existing messages on SPA navigation.
- Rate-limit kill-switch, `AUTO_MIN_INTERVAL_MS = 5000` throttle, `MAX_AUTO_ITERATIONS = 20` cap.
- Manual control from DevTools: `__deepseekAgentStop()`, `__deepseekAgentResume()`, `__deepseekAgentStatus()`, `__deepseekAgentDump()`.
- Support for tool calls inside `<pre><code>` blocks (preserves Markdown).
- Placeholder filter on the JS side (`looksLikePlaceholderArg`, `filterPlaceholders`).
- Plan mode: prefix the first message with `plan:` / `/plan` / `план:`.

### Changed
- `write_file` capped at 500 KB; larger writes are refused and redirected to `edit_file`.
- `parseToolCalls` no longer executes placeholder tool names.

## [1.0.0] — 2026-09-23

### Added
- Initial release: `read_file`, `write_file`, `list_files`, `bash`, `search_text`.
- JCEF-based DeepSeek chat embedded in a tool window.
- JS ↔ Kotlin bridge via `JBCefJSQuery`.
- IDE integration: file operations resolve relative to the current project.