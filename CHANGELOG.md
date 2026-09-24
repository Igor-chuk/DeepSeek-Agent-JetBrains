# DeepSeek Agent — Changelog

All notable changes to this plugin are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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