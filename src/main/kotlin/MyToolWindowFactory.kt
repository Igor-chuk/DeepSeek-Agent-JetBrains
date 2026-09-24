package ru.ichuk.deepseek

import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefBrowserBuilder
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import javax.swing.JLabel
import javax.swing.SwingConstants
import javax.swing.UIManager

class MyToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        thisLogger().info("[deepseek] createToolWindowContent called")

        if (!JBCefApp.isSupported()) {
            thisLogger().warn("[deepseek] JCEF is NOT supported")
            val label = JLabel("JCEF is not available in this IDE build.", SwingConstants.CENTER)
            label.foreground = UIManager.getColor("Label.disabledForeground")
            toolWindow.contentManager.addContent(
                ContentFactory.getInstance().createContent(label, "DeepSeek", false)
            )
            return
        }

        val disposedFlag = DisposeFlag()
        val browser = JBCefBrowser.createBuilder()
            .setOffScreenRendering(false)
            .build()
        thisLogger().info("[deepseek] browser created (empty, OSR=off)")

        val toolRunner = ToolRunner(project)

        // ---- JS -> Kotlin bridge ------------------------------------------
        val jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

        jsQuery.addHandler { payload ->
            if (disposedFlag.value) return@addHandler JBCefJSQuery.Response("disposed")
            thisLogger().info("[bridge] from JS: $payload")
            try {
                val json = JsonParser.parseString(payload).asJsonObject
                val action = json.get("action")?.asString

                when (action) {
                    "tool_calls" -> {
                        val text = json.get("text")?.asString ?: ""
                        val calls = toolRunner.parseToolCalls(text)
                        thisLogger().info("[tool] parsed ${calls.size} call(s)")

                        if (calls.isEmpty()) {
                            thisLogger().info("[tool] no known tool calls — ignoring")
                        } else {
                            val pool = com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService()
                            thisLogger().info("[tool] launching ${calls.size} call(s) in parallel")
                            val futures = calls.map { call ->
                                java.util.concurrent.CompletableFuture.supplyAsync(
                                    java.util.function.Supplier {
                                        try {
                                            thisLogger().info("[tool] (parallel) executing ${call.name}")
                                            val r = toolRunner.execute(call)
                                            thisLogger().info("[tool] (parallel) done ${call.name}, len=${r.length}")
                                            r
                                        } catch (e: Exception) {
                                            thisLogger().warn("[tool] (parallel) failed ${call.name}: ${e.message}", e)
                                            "Error executing ${call.name}: ${e.message}"
                                        }
                                    },
                                    pool
                                )
                            }
                            // Отправляем результат КАЖДОГО вызова сразу по мере
                            // завершения, а не ждём весь batch. Для быстрых команд
                            // это значит, что модель получает результат раньше
                            // и может продолжать работу, пока остальные ещё бегут.
                            // Между сообщениями держим паузу 1.5 сек, чтобы
                            // DeepSeek успевал принять каждое отдельно.
                            val sendQueue = java.util.concurrent.LinkedBlockingQueue<Pair<String, String>>()
                            val senderPool = java.util.concurrent.Executors.newSingleThreadExecutor()
                            senderPool.execute {
                                var lastSendAt = 0L
                                while (true) {
                                    val (name, result) = try {
                                        sendQueue.take()
                                    } catch (ie: InterruptedException) { return@execute }
                                    if (disposedFlag.value || browser.isDisposed) continue
                                    val now = System.currentTimeMillis()
                                    val wait = 1500L - (now - lastSendAt)
                                    if (lastSendAt > 0 && wait > 0) {
                                        try { Thread.sleep(wait) } catch (ie: InterruptedException) {}
                                    }
                                    val resultText = "[TOOL_RESULT: " + name + "]\n" + result
                                    thisLogger().info("[tool] sending single result for ${name}, len=${resultText.length}")
                                    val escaped = escapeForJsString(resultText)
                                    val js = "window.__insertToolResult && window.__insertToolResult('$escaped');"
                                    ApplicationManager.getApplication().invokeLater {
                                        if (disposedFlag.value || browser.isDisposed) return@invokeLater
                                        try {
                                            browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
                                        } catch (e: Exception) {
                                            thisLogger().warn("[deepseek] executeJavaScript failed: ${e.message}")
                                        }
                                    }
                                    lastSendAt = System.currentTimeMillis()
                                }
                            }
                            for ((idx, call) in calls.withIndex()) {
                                val idxFinal = idx
                                futures[idx].whenComplete { result, err ->
                                    val out = if (err != null) "Error: ${err.message}" else (result ?: "<no result>")
                                    sendQueue.offer(call.name to out)
                                    thisLogger().info("[tool] (async) queued result for ${call.name} (idx=$idxFinal)")
                                }
                            }
                        }
                    }
                    else -> thisLogger().info("[bridge] unknown action=$action")
                }
                JBCefJSQuery.Response("ok")
            } catch (e: Exception) {
                thisLogger().warn("[bridge] handler failed", e)
                JBCefJSQuery.Response("error: ${e.message}")
            }
        }
        thisLogger().info("[deepseek] jsQuery handler attached")

        val injectBody = jsQuery.inject(
            "payload",
            "response => { console.log('[to-kotlin-ok]', response); }",
            "error => { console.error('[to-kotlin-err]', error); }",
        )

        // ---- Load handler -------------------------------------------------
        val loadHandler = object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(b: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (disposedFlag.value) return
                if (frame?.isMain != true) return
                thisLogger().info(
                    "[deepseek] onLoadEnd url=${b?.url} status=$httpStatusCode",
                )
                val script = buildObserverScript(injectBody)
                thisLogger().info("[deepseek] injecting observer, len=${script.length}")
                try {
                    b?.executeJavaScript(script, b.url, 0)
                } catch (e: Exception) {
                    thisLogger().warn("[deepseek] executeJavaScript (observer) failed: ${e.message}")
                }
            }
        }
        browser.jbCefClient.addLoadHandler(loadHandler, browser.cefBrowser)
        thisLogger().info("[deepseek] load handler attached")

        browser.loadURL("https://chat.deepseek.com")
        thisLogger().info("[deepseek] loadURL called")

        val content = ContentFactory.getInstance()
            .createContent(browser.component, "DeepSeek", false)

        content.setDisposer {
            thisLogger().info("[deepseek] disposing browser + handlers")
            disposedFlag.value = true

            // 1. Снимаем load handler — иначе CefClient держит ссылку на наш объект.
            try {
                browser.jbCefClient.removeLoadHandler(loadHandler, browser.cefBrowser)
            } catch (e: Exception) {
                thisLogger().warn("[deepseek] removeLoadHandler failed: ${e.message}")
            }

            // 2. Dispose JS query — иначе мост JS→Kotlin остаётся в CefClient
            //    и не даёт процессу завершиться чисто.
            try {
                Disposer.dispose(jsQuery)
            } catch (e: Exception) {
                thisLogger().warn("[deepseek] dispose jsQuery failed: ${e.message}")
            }

            // 3. Только последним — сам браузер.
            try {
                Disposer.dispose(browser)
            } catch (e: Exception) {
                thisLogger().warn("[deepseek] dispose browser failed: ${e.message}")
            }

            thisLogger().info("[deepseek] dispose complete")
        }

        toolWindow.contentManager.addContent(content)
        thisLogger().info("[deepseek] content added")
    }

    // ---- helpers ---------------------------------------------------------

    private fun <T> runOnEdt(block: () -> T): T {
        var result: T? = null
        ApplicationManager.getApplication().invokeAndWait {
            result = block()
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun escapeForJsString(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<'  -> sb.append("\\u003c")
                '>'  -> sb.append("\\u003e")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * JS, который:
     *  1) вешает window.__sendToKotlin (мост в Kotlin),
     *  2) вешает window.__insertToolResult (вставляет результат и жмёт Send),
     *  3) в пустом чате приписывает системную инструкцию к первому
     *     сообщению пользователя (перехват Enter/клика в capture-фазе),
     *  4) через MutationObserver следит за document.body (SPA-safe),
     *  5) ищет [TOOL: ...] в code-блоках (textContent) и, как fallback,
     *     в innerText; ДЕДУП по тексту, а не по key, чтобы ловить
     *     тул-колл, который появился в стриме позже первого скана,
     *  6) throttle + cap + kill-switch против бесконечного цикла.
     */
    private fun buildObserverScript(injectBody: String): String {
        val escapedPreamble = escapeForJsString(SYSTEM_PREAMBLE)
        return """
        (function() {
            if (window.__deepseekAgentInjected) {
                console.log('[deepseek-agent] already injected');
                if (window.__deepseekAgentEnsure) {
                    try { window.__deepseekAgentEnsure(); } catch (e) {}
                }
                return;
            }
            window.__deepseekAgentInjected = true;
            console.log('[deepseek-agent] injected');

            var SYSTEM_PREAMBLE = '$escapedPreamble';

            var KNOWN_TOOLS = ['read_file','write_file','edit_file','list_files','bash','search_text'];

            // ---------- loop control ----------
            var AUTO_MIN_INTERVAL_MS = 5000;
            var AUTO_COOLDOWN_AFTER_RATELIMIT_MS = 30000;
            var MAX_AUTO_ITERATIONS = 20;
            var RATE_LIMIT_PATTERNS = [
                'Слишком частые сообщения',
                'Повторите попытку позже',
                'Too frequent messages',
                'Too many requests',
                'rate limit',
                'Rate limit',
                'Слишком много запросов',
                'Too many requests, please try again later',
                'please try again later',
                'Попробуйте позже',
                'Server is busy',
                'Server busy',
            ];

            var pendingResultText = null;
            var lastAutoSendAt = 0;
            var autoIterations = 0;
            var autoLoopStopped = false;
            var autoLoopStopReason = '';
            var isAutoSending = false;

            // ---------- bridge to Kotlin ----------
            window.__sendToKotlin = function(payload) {
                $injectBody
            };

            // ---------- manual control ----------
            window.__deepseekAgentStop = function(reason) {
                autoLoopStopped = true;
                autoLoopStopReason = reason || 'manual';
                console.warn('[deepseek-agent] STOP: ' + autoLoopStopReason);
            };
            window.__deepseekAgentResume = function() {
                autoLoopStopped = false;
                autoIterations = 0;
                lastAutoSendAt = 0;
                autoLoopStopReason = '';
                console.log('[deepseek-agent] resumed');
            };
            window.__deepseekAgentStatus = function() {
                return {
                    autoIterations: autoIterations,
                    stopped: autoLoopStopped,
                    reason: autoLoopStopReason,
                    sinceLastSendMs: Date.now() - lastAutoSendAt,
                };
            };

            // Диагностика: посмотреть, что именно мы видим в последнем сообщении.
            window.__deepseekAgentDump = function() {
                var items = document.querySelectorAll('[data-virtual-list-item-key]');
                var last = items[items.length - 1];
                if (!last) { console.log('no messages'); return; }
                var contentEl = last.querySelector('.ds-assistant-message-main-content');
                console.log('--- contentEl ---', contentEl);
                if (contentEl) {
                    console.log('--- innerText ---', contentEl.innerText);
                    console.log('--- pre blocks ---', contentEl.querySelectorAll('pre'));
                    console.log('--- code blocks ---', contentEl.querySelectorAll('code'));
                }
            };

            // ---------- shared: find the chat input ----------
            function findInputTextarea() {
                var all = document.querySelectorAll('textarea');
                for (var i = 0; i < all.length; i++) {
                    var ph = all[i].placeholder || '';
                    if (ph.indexOf('DeepSeek') !== -1) return all[i];
                }
                for (var j = all.length - 1; j >= 0; j--) {
                    if (!all[j].closest('[data-virtual-list-item-key]')) return all[j];
                }
                if (all.length > 0) return all[all.length - 1];
                var editables = document.querySelectorAll('div[contenteditable="true"]');
                if (editables.length > 0) return editables[editables.length - 1];
                return null;
            }

            function setReactValue(ta, newVal) {
                if (ta.tagName === 'TEXTAREA') {
                    var proto = Object.getPrototypeOf(ta);
                    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                    if (desc && desc.set) desc.set.call(ta, newVal);
                    else ta.value = newVal;
                    ta.dispatchEvent(new Event('input', { bubbles: true }));
                    ta.dispatchEvent(new Event('change', { bubbles: true }));
                } else {
                    ta.focus();
                    document.execCommand('selectAll', false, null);
                    document.execCommand('insertText', false, newVal);
                }
            }

            // ---------- preamble ----------
            function isChatEmpty() {
                return document.querySelectorAll('[data-virtual-list-item-key]').length === 0;
            }

            function maybePrependInstructions() {
                if (!isChatEmpty()) return;
                var ta = findInputTextarea();
                if (!ta) return;

                var val = (ta.tagName === 'TEXTAREA' ? ta.value : ta.innerText) || '';
                if (!val.trim()) return;
                if (val.indexOf('[SYSTEM INSTRUCTIONS]') === 0) return;
                if (val.indexOf('[TOOL_RESULT:') === 0) return;

                var newVal = SYSTEM_PREAMBLE + '\n\n---\n\n' + val;
                setReactValue(ta, newVal);
                console.log('[deepseek-agent] prepended system instructions to first message, len=' + newVal.length);
            }

            var planMode = false;
            var PLAN_TRIGGER_RE = /^\s*(?:\/plan|план|plan)\b/i;
            var PLAN_CONFIRM_RE = /^\s*(?:да|поехали|выполняй|ок|ok|yes|go)\b/i;

            function detectPlanMode() {
                var ta = findInputTextarea();
                if (!ta) return;
                var val = (ta.tagName === 'TEXTAREA' ? ta.value : ta.innerText) || '';
                var t = val.trim();
                if (!t) return;

                if (PLAN_TRIGGER_RE.test(t)) {
                    if (!planMode) {
                        planMode = true;
                        console.log('[deepseek-agent] PLAN MODE ON (trigger: ' + t.slice(0, 40) + ')');
                    }
                    return;
                }

                if (planMode && PLAN_CONFIRM_RE.test(t)) {
                    planMode = false;
                    console.log('[deepseek-agent] PLAN MODE OFF (confirmed: ' + t.slice(0, 40) + ')');
                    return;
                }
            }

            function resetAutoLoopForUserTurn() {
                autoIterations = 0;
                autoLoopStopped = false;
                autoLoopStopReason = '';
                console.log('[deepseek-agent] user turn — auto-loop counters reset');
            }

            document.addEventListener('keydown', function(e) {
                if (e.key !== 'Enter' || e.shiftKey) return;
                var t = e.target;
                if (!t || (t.tagName !== 'TEXTAREA' && !t.isContentEditable)) return;
                if (!isAutoSending) resetAutoLoopForUserTurn();
                detectPlanMode();
                maybePrependInstructions();
            }, true);

            document.addEventListener('click', function(e) {
                if (!e.target || !e.target.closest) return;
                var btn = e.target.closest('button');
                if (!btn) return;
                var ta = findInputTextarea();
                if (!ta) return;
                var form = ta.closest('form') || (ta.parentElement && ta.parentElement.parentElement);
                if (form && form.contains(btn)) {
                    if (!isAutoSending) resetAutoLoopForUserTurn();
                    detectPlanMode();
                    maybePrependInstructions();
                }
            }, true);

            // ---------- insert tool result ----------
            window.__insertToolResult = function(resultText, retries) {
                retries = retries || 0;
                if (retries > 20) {
                    console.error('[deepseek-agent] failed to find input after 20 retries');
                    return;
                }
                if (planMode) {
                    console.warn('[deepseek-agent] PLAN MODE active — dropping tool result (waiting for user confirmation)');
                    return;
                }
                if (autoLoopStopped) {
                    console.warn('[deepseek-agent] auto loop stopped (' + autoLoopStopReason + '), dropping tool result');
                    return;
                }
                if (autoIterations >= MAX_AUTO_ITERATIONS) {
                    autoLoopStopped = true;
                    autoLoopStopReason = 'MAX_AUTO_ITERATIONS=' + MAX_AUTO_ITERATIONS;
                    console.warn('[deepseek-agent] STOP: ' + autoLoopStopReason);
                    return;
                }

                var since = Date.now() - lastAutoSendAt;
                var wait = AUTO_MIN_INTERVAL_MS - since;
                if (lastAutoSendAt > 0 && wait > 0) {
                    console.log('[deepseek-agent] throttling: waiting ' + wait + 'ms');
                    setTimeout(function() { window.__insertToolResult(resultText, retries); }, wait);
                    return;
                }

                try {
                    console.log('[deepseek-agent] inserting tool result, len=' + resultText.length);

                    var input = findInputTextarea();
                    if (!input) {
                        console.log('[deepseek-agent] input not found, retrying...');
                        setTimeout(function() { window.__insertToolResult(resultText, retries + 1); }, 500);
                        return;
                    }

                    var cancelBtn = Array.prototype.find.call(
                        document.querySelectorAll('button, div[role="button"]'),
                        function(b) { return (b.innerText || '').trim() === 'Отмена'; }
                    );
                    if (cancelBtn) {
                        cancelBtn.click();
                        setTimeout(function() { window.__insertToolResult(resultText, retries); }, 250);
                        return;
                    }

                    pendingResultText = resultText;
                    setReactValue(input, resultText);

                    autoIterations++;
                    lastAutoSendAt = Date.now();
                    isAutoSending = true;
                    console.log('[deepseek-agent] auto-send #' + autoIterations + '/' + MAX_AUTO_ITERATIONS);

                    setTimeout(function() {
                        input.focus();
                        ['keydown', 'keypress', 'keyup'].forEach(function(type) {
                            var ev = new KeyboardEvent(type, {
                                key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
                                bubbles: true, cancelable: true
                            });
                            input.dispatchEvent(ev);
                        });
                        setTimeout(function() {
                            var val = input.value || input.innerText || '';
                            if (val.length > 0) {
                                var sendBtn = document.querySelector('button[type="submit"]');
                                if (sendBtn) sendBtn.click();
                            }
                            setTimeout(function() { isAutoSending = false; }, 300);
                            setTimeout(function() {
                                if (!autoLoopStopped) pendingResultText = null;
                            }, 12000);
                        }, 200);
                    }, 200);
                } catch (e) {
                    isAutoSending = false;
                    console.error('[deepseek-agent] insertToolResult failed:', e);
                }
            };

            // ---------- tool-call observer ----------
            var processedKeys = new Set();  // key -> мы уже РАСПАРСИЛИ тул-колл
            var seenText = new Map();       // key -> последний диспатченный текст
            var debounceTimers = new Map();
            var lastUrl = location.href;
            var lastMessageCount = -1;
            var lastFirstKey = null;
            var lastFirstKey = null;

            var TOOL_RE = /\[TOOL:\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*\(([\s\S]*?)\)\s*]/;

            function baselineExisting(reason) {
                var items = document.querySelectorAll('[data-virtual-list-item-key]');
                var added = 0;
                items.forEach(function(el) {
                    var key = el.getAttribute('data-virtual-list-item-key');
                    if (key && !processedKeys.has(key)) {
                        processedKeys.add(key);
                        added++;
                    }
                });
                lastMessageCount = items.length;
                console.log('[deepseek-agent] baseline (' + reason + '): marked ' + added + ' items (' + items.length + ' total)');
            }

            function looksLikePlaceholderArg(s) {
                if (s == null) return true;
                var t = String(s).trim().toLowerCase();
                if (t === '') return true;
                // "." и "./" — валидные пути (корень проекта), НЕ плейсхолдеры.
                if (t === '.' || t === './') return false;
                if (t === '...' || t === '..') return true;
                if (t === 'path' || t === 'file' || t === 'filename') return true;
                if (t === 'your_path' || t === 'your_file' || t === 'your-path') return true;
                if (t === 'placeholder' || t === 'placeholder.txt') return true;
                if (t.indexOf('path/to/') === 0) return true;
                if (t.indexOf('path\\to\\') === 0) return true;
                if (t.length >= 2 && t[0] === '<' && t[t.length - 1] === '>') return true;
                if (t.length >= 6 && t.indexOf('your_') === 0) return true;
                if (t.charAt(0) === '\\') return true;
                // Проверка «нет букв/цифр» — только если это не точка и не ./ выше.
                if (!/[a-z0-9]/.test(t)) return true;
                return false;
            }

            function filterPlaceholders(text) {
                var re = /\[TOOL:\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*\(([\s\S]*?)\)\s*]/g;
                var m;
                var found = false;
                var anyGood = false;
                while ((m = re.exec(text)) !== null) {
                    found = true;
                    var raw = m[2];
                    var parts = [];
                    var sb = '';
                    var q = null;
                    for (var i = 0; i < raw.length; i++) {
                        var c = raw[i];
                        if (q) {
                            if (c === '\\') { sb += c + (raw[i+1] || ''); i++; continue; }
                            if (c === q) q = null;
                            sb += c;
                        } else if (c === '"' || c === "'") {
                            q = c; sb += c;
                        } else if (c === ',') {
                            parts.push(sb); sb = '';
                        } else {
                            sb += c;
                        }
                    }
                    if (sb.length) parts.push(sb);
                    for (var k = 0; k < parts.length; k++) {
                        var arg = parts[k].trim().replace(/^["']|["']$/g, '');
                        if (!looksLikePlaceholderArg(arg)) { anyGood = true; break; }
                    }
                }
                if (!found) return null;
                return anyGood ? text : null;
            }

            function extractToolCallsText(contentEl) {
                // 1. Code-блоки — их textContent сохраняет Markdown.
                //    ВАЖНО: собираем ВСЕ блоки, а не только первый — модель
                //    может выдать несколько ```tool ... ``` подряд, и каждый
                //    нужно обработать.
                var preBlocks = contentEl.querySelectorAll('pre, code');
                var chunks = [];
                for (var i = 0; i < preBlocks.length; i++) {
                    var t = preBlocks[i].textContent || '';
                    if (TOOL_RE.test(t)) {
                        chunks.push(t);
                    }
                }
                if (chunks.length > 0) {
                    var combined = chunks.join('\n');
                    console.log('[deepseek-agent] collected ' + chunks.length + ' pre-block(s), total len=' + combined.length);
                    return combined;
                }
                // 2. Fallback — innerText всего сообщения.
                var raw = contentEl.innerText || '';
                if (TOOL_RE.test(raw)) {
                    console.log('[deepseek-agent] candidate in innerText (len=' + raw.length + ')');
                    return raw;
                }
                return null;
            }

            // Проверяем, есть ли в тексте вызов ИЗВЕСТНОГО инструмента.
            // Плейсхолдеры вроде [TOOL: name(...)] отсекаются.
            function hasKnownToolCall(text) {
                var re = /\[TOOL:\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*\(/g;
                var m;
                while ((m = re.exec(text)) !== null) {
                    if (KNOWN_TOOLS.indexOf(m[1]) !== -1) return true;
                }
                return false;
            }

            function isMessageFinalized(itemEl) {
                // DeepSeek рендерит НИЖНИЙ тулбар сообщения (Copy / Regenerate /
                // Like / Dislike / Read aloud / Share) только когда стрим
                // закончился. Отличить его от внутреннего тулбара кодового
                // блока (Copy / Download) можно по количеству кнопок:
                // у нижнего — минимум 5-6, у кодового — 2.
                var bars = itemEl.querySelectorAll('div.ds-flex[style*="gap: 10px"]');
                for (var b = 0; b < bars.length; b++) {
                    var btns = bars[b].querySelectorAll('div[role="button"], button');
                    if (btns.length >= 5) return true;
                }
                // Fallback: кнопка "Читать вслух" / "Read aloud" есть только
                // у финализированного нижнего тулбара.
                var all = itemEl.querySelectorAll('[aria-label]');
                for (var i = 0; i < all.length; i++) {
                    var lbl = (all[i].getAttribute('aria-label') || '').toLowerCase();
                    if (lbl === 'читать вслух' || lbl === 'read aloud') return true;
                }
                return false;
            }

            function tryProcess(itemEl) {
                var key = itemEl.getAttribute('data-virtual-list-item-key') || '';
                if (!key) return;
                if (processedKeys.has(key)) return;

                if (!isMessageFinalized(itemEl)) {
                    // Сообщение ещё в стриме — обработаем позже.
                    // ВАЖНО: перепланируем проверку, а не выходим навсегда.
                    scheduleCheck(itemEl);
                    return;
                }

                var contentEl = itemEl.querySelector('.ds-assistant-message-main-content');
                if (!contentEl) return;

                var text = extractToolCallsText(contentEl);
                if (!text) {
                    // Тул-колла пока нет — НЕ помечаем key. Возможно,
                    // он появится на следующей мутации (стрим продолжается).
                    return;
                }

                if (!hasKnownToolCall(text)) {
                    console.log('[deepseek-agent] [TOOL:] found in key=' + key + ', but name is unknown (placeholder) — waiting');
                    return;
                }

                var filteredText = filterPlaceholders(text);
                if (!filteredText) {
                    console.log('[deepseek-agent] all args in key=' + key + ' look like placeholders — ignoring');
                    return;
                }
                text = filteredText;

                if (autoLoopStopped) {
                    console.warn('[deepseek-agent] auto loop stopped (' + autoLoopStopReason + '), ignoring tool call in key=' + key);
                    processedKeys.add(key);
                    return;
                }

                // Dedup: если ровно этот же текст мы уже диспатчили — молча выходим.
                var prev = seenText.get(key);
                if (prev === text) return;
                seenText.set(key, text);

                console.log('[deepseek-agent] dispatching tool call from key=' + key + ', payload len=' + text.length);
                window.__sendToKotlin(JSON.stringify({
                    action: 'tool_calls',
                    key: key,
                    text: text
                }));
                processedKeys.add(key);
            }

            function scheduleCheck(itemEl) {
                var key = itemEl.getAttribute('data-virtual-list-item-key') || '';
                if (!key) return;
                var prev = debounceTimers.get(key);
                if (prev) clearTimeout(prev);
                var t = setTimeout(function() {
                    debounceTimers.delete(key);
                    tryProcess(itemEl);
                }, 2000);
                debounceTimers.set(key, t);
            }

            function scanAll() {
                document.querySelectorAll('[data-virtual-list-item-key]')
                    .forEach(function(el) { scheduleCheck(el); });
            }

            var observer = new MutationObserver(function(mutations) {
                mutations.forEach(function(m) {
                    m.addedNodes.forEach(function(n) {
                        if (!(n instanceof HTMLElement)) return;
                        if (n.matches && n.matches('[data-virtual-list-item-key]')) {
                            scheduleCheck(n);
                        } else if (n.querySelectorAll) {
                            n.querySelectorAll('[data-virtual-list-item-key]').forEach(scheduleCheck);
                            var parent = n.closest && n.closest('[data-virtual-list-item-key]');
                            if (parent) scheduleCheck(parent);
                        }
                    });
                    if (m.type === 'characterData' && m.target && m.target.parentElement) {
                        var p = m.target.parentElement.closest('[data-virtual-list-item-key]');
                        if (p) scheduleCheck(p);
                    }
                });
            });

            function attachObserver() {
                try {
                    observer.observe(document.body, {
                        childList: true, subtree: true, characterData: true
                    });
                    console.log('[deepseek-agent] observer attached to document.body');
                } catch (e) {
                    console.error('[deepseek-agent] observe failed', e);
                }
                baselineExisting('attach');
                scanAll();
            }

            function detectRateLimit() {
                var items = document.querySelectorAll('[data-virtual-list-item-key]');
                var lastN = Math.min(items.length, 2);
                for (var k = items.length - lastN; k < items.length; k++) {
                    var it = items[k];
                    if (!it) continue;
                    var txt = it.innerText || '';
                    for (var m = 0; m < RATE_LIMIT_PATTERNS.length; m++) {
                        if (txt.indexOf(RATE_LIMIT_PATTERNS[m]) !== -1) {
                            return RATE_LIMIT_PATTERNS[m];
                        }
                    }
                }
                var probes = document.querySelectorAll(
                    '[role="alert"], [class*="toast"], [class*="Toast"], [class*="notification"], [class*="Notification"], .ds-toast, .ds-notification'
                );
                for (var i = 0; i < probes.length; i++) {
                    var t = probes[i].innerText || '';
                    for (var j = 0; j < RATE_LIMIT_PATTERNS.length; j++) {
                        if (t.indexOf(RATE_LIMIT_PATTERNS[j]) !== -1) {
                            return RATE_LIMIT_PATTERNS[j];
                        }
                    }
                }
                return null;
            }

            function watchdog() {
                try {
                    var urlChanged = (location.href !== lastUrl);
                    var items = document.querySelectorAll('[data-virtual-list-item-key]');
                    var currentCount = items.length;
                    var firstKey = items.length > 0 ? (items[0].getAttribute('data-virtual-list-item-key') || '') : '';
                    var chatChanged = (lastFirstKey !== null) && (firstKey !== lastFirstKey) && (firstKey !== '');
                    lastFirstKey = firstKey;
                    var reset = (currentCount < lastMessageCount);

                    if (chatChanged) {
                        console.log('[deepseek-agent] chat changed (first key ' + firstKey + '), re-baselining');
                    }

                    if (urlChanged || reset || chatChanged) {
                        if (urlChanged) {
                            console.log('[deepseek-agent] URL changed: ' + lastUrl + ' -> ' + location.href);
                            lastUrl = location.href;
                        }
                        if (reset) console.log('[deepseek-agent] message count dropped (' + lastMessageCount + ' -> ' + currentCount + ')');
                        setTimeout(function() { baselineExisting(urlChanged ? 'url-change' : 'reset'); }, 800);
                        setTimeout(scanAll, 1200);
                    }

                    if (!autoLoopStopped) {
                        var rl = detectRateLimit();
                        if (isAutoSending) {
                            console.log('[deepseek-agent] watchdog: isAutoSending=true, rate-limit=' + (rl || 'none'));
                        }
                        if (rl) {
                            if (!autoLoopStopped) {
                                autoLoopStopped = true;
                                autoLoopStopReason = 'rate limit detected: "' + rl + '"';
                                console.warn('[deepseek-agent] PAUSE for ' + AUTO_COOLDOWN_AFTER_RATELIMIT_MS + 'ms: ' + autoLoopStopReason);
                                setTimeout(function() {
                                    console.log('[deepseek-agent] cooldown passed, resuming auto-loop');
                                    autoLoopStopped = false;
                                    autoLoopStopReason = '';
                                    autoIterations = 0;
                                    if (pendingResultText) {
                                        var retry = pendingResultText;
                                        pendingResultText = null;
                                        console.log('[deepseek-agent] retrying pending tool result, len=' + retry.length);
                                        setTimeout(function() { window.__insertToolResult(retry); }, 1000);
                                    }
                                }, AUTO_COOLDOWN_AFTER_RATELIMIT_MS);
                            }
                        }
                    }
                } catch (e) {}
                setTimeout(watchdog, 2000);
            }

            window.__deepseekAgentEnsure = function() {
                baselineExisting('ensure');
                scanAll();
            };

            if (document.readyState === 'complete' || document.readyState === 'interactive') {
                attachObserver();
                watchdog();
            } else {
                window.addEventListener('DOMContentLoaded', function() {
                    attachObserver();
                    watchdog();
                });
            }
        })();
        """.trimIndent()
    }

    /**
     * Простой флаг с volatile-семантикой, чтобы лямбды и disposer
     * видели согласованное состояние «мы уже уничтожаемся».
     * Не используем @Volatile-поле класса: MyToolWindowFactory один
     * на всё IDE, а createToolWindowContent вызывается по разу на проект —
     * значит состояние должно быть локальным для каждого окна.
     */
    private class DisposeFlag {
        @Volatile var value: Boolean = false
    }

    companion object {
        private val SYSTEM_PREAMBLE = """
            [SYSTEM INSTRUCTIONS]
            Ты — агент, работающий внутри IDE. У тебя ЕСТЬ доступ к файловой системе проекта через инструменты.

            Доступные инструменты:
            - read_file("path") — читает содержимое файла.
            - edit_file("path", "old_string", "new_string") — точечная замена. Находит old_string в файле и заменяет на new_string. Есть 4-й необязательный аргумент "all" — заменить все вхождения.
            - write_file("path", "content") — создаёт/перезаписывает файл целиком. ТОЛЬКО для новых файлов или полной перезаписи небольших файлов (< ~50 КБ).
            - list_files("path") — показывает содержимое директории.
            - bash("command") — выполняет shell-команду в корне проекта.
            - search_text("needle") — ищет текст по всем файлам проекта.

            !!! ФОРМАТ ВЫЗОВА: ОБОРАЧИВАЙ В ```tool ... ``` !!!

            Web-интерфейс DeepSeek рендерит Markdown прямо в сообщении: #, *, _, backticks удаляются из видимого текста до того, как инструмент его получит. Поэтому ВСЕГДА оборачивай ВСЕ вызовы в блок кода с языком "tool":

            ```tool
            [TOOL: write_file("README.md", "## Заголовок\n\nТекст с \`inline code\` и блоком:\n\`\`\`bash\nnpm run dev\n\`\`\`\n")]
            ```
            
            Внутри этого блока ты пишешь вызов как обычно: один или несколько вызовов, каждый на своей строке. Не бойся многострочных аргументов — внутри tool ... вся разметка сохраняется дословно.

            МОЖНО ВЫЗЫВАТЬ НЕСКОЛЬКО ИНСТРУМЕНТОВ ЗА ОДИН ХОД. Если действия независимы (создать 3 файла, прочитать 3 разных файла) — пиши их подряд в одном ```tool блоке. Результаты придут вместе, одним сообщением с несколькими [TOOL_RESULT: имя].

            КАК ВЫЗЫВАТЬ (формат внутри ```tool):
                [TOOL: имя_инструмента("аргумент1", "аргумент2")]
                Аргументы в двойных кавычках, разделитель — запятая. Внутри строк можно использовать \n, \t, ", \.

            После закрывающих ``` не пиши больше вызовов — просто заверши ход коротким текстом.

            РЕЖИМ ПЛАНА (PLAN MODE):
            Если пользователь написал в начале сообщения слово «план» или «plan» (например: «план: отрефактори модуль авторизации») —
            ты НЕ вызываешь инструменты. Вместо этого составляешь подробный пошаговый план:
              1. Что именно будешь делать на каждом шаге.
              2. Какие файлы затронешь.
              3. Какие инструменты будешь вызывать (только описательно, не в формате [TOOL: ...]).
              4. Что может пойти не так и как ты это учтёшь.
            Затем заканчиваешь ход фразой: «Готов выполнять? Ответь да или поехали.»
            Никаких [TOOL: ...] в plan-режиме. Только план текстом.
            Когда пользователь ответит «да» / «поехали» / «выполняй» — тогда начинаешь выполнять шаг за шагом обычным способом.

            ДИСЦИПЛИНА АВТОНОМНОГО РЕЖИМА:

                - НЕ исследуй проект «на всякий случай». Только то, что нужно для выполнения текущего запроса пользователя.

                - Если задача уже выполнена — остановись и напиши короткий итог.

                - Не больше 5-7 ходов на один запрос пользователя, если он не попросил обойти весь проект.

                - Если не уверен, нужен ли следующий вызов — НЕ вызывай, а спроси пользователя или заверши ход.

            ПЛЕЙСХОЛДЕРЫ — НЕ ПИШИ:
                - НИКОГДА не пиши примеры вроде [TOOL: name(...)] или [TOOL: имя_инструмента(...)] — они будут восприняты как реальный вызов. Даже в ```tool блоке. Если нужно объяснить синтаксис — пиши словами. НИКОГДА не подставляй в аргументы пути-заглушки: три точки, две точки, одну точку, path, path/to/file, <path>, your_file, filename — это тоже плейсхолдер. Если конкретный путь неизвестен — сначала вызови list_files(".") или search_text с осмысленным запросом, чтобы узнать реальный путь.

            КАК РЕДАКТИРОВАТЬ ФАЙЛЫ:

                - Для ИЗМЕНЕНИЯ существующего файла ВСЕГДА используй edit_file, а НЕ write_file.

                - Сначала read_file — прочитай нужный фрагмент.

                - Потом edit_file с МИНИМАЛЬНЫМ уникальным old_string (несколько строк контекста) и его новой версией new_string.

                - write_file применяй ТОЛЬКО для НОВЫХ файлов или когда действительно нужно перезаписать весь файл целиком (и он небольшой).

                - НИКОГДА не помещай в ответ содержимое больших файлов (мегабайты данных), base64-блобы, минифицированные бандлы. Если файл большой — работай через edit_file по частям.

            ВАЖНО:

                - НИКОГДА не говори, что у тебя нет доступа к файлам, директориям или терминалу. Просто вызывай инструмент.

                - Прежде чем писать код — читай релевантные файлы через read_file / search_text.
            [/SYSTEM INSTRUCTIONS]
    """.trimIndent()
    }
}