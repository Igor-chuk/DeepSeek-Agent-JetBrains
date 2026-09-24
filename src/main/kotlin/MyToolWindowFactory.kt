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
                            thisLogger().info("[tool] launching ${calls.size} call(s)")

                            // Очередь на отправку. Каждый элемент — одно [TOOL_RESULT]-сообщение.
                            val sendQueue = java.util.concurrent.LinkedBlockingQueue<Pair<String, String>>()

                            // Единый поток-отправитель: умеет "ждать окончания стрима модели"
                            // и встраиваться после него, а не обрывать текущий ответ.
                            val senderPool = java.util.concurrent.Executors.newSingleThreadExecutor()
                            senderPool.execute {
                                while (true) {
                                    val pair = try { sendQueue.take() } catch (ie: InterruptedException) { return@execute }
                                    if (disposedFlag.value || browser.isDisposed) continue
                                    val (name, result) = pair

                                    // Умное встраивание: ждём, пока модель закончит текущий ответ.
                                    // Признак: по последнему assistant-сообщению 1.5 сек не было мутаций.
                                    val waitStart = System.currentTimeMillis()
                                    while (!disposedFlag.value && !browser.isDisposed && isModelWriting()) {
                                        if (System.currentTimeMillis() - waitStart > 60_000L) break
                                        try { Thread.sleep(500) } catch (_: InterruptedException) {}
                                    }
                                    if (disposedFlag.value || browser.isDisposed) continue

                                    val resultText = "[TOOL_RESULT: " + name + "]\n" + result
                                    thisLogger().info("[tool] sending result for $name, len=${resultText.length}")
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
                                    // Небольшая пауза между сообщениями, чтобы DeepSeek успел принять.
                                    try { Thread.sleep(1500) } catch (_: InterruptedException) {}
                                }
                            }

                            // --- Bash: параллельно, каждый результат сразу в очередь ---
                            val bashFutures = mutableListOf<java.util.concurrent.CompletableFuture<Unit>>()
                            for ((i, call) in calls.withIndex()) {
                                if (call.name == "bash") {
                                    val idx = i
                                    val f = java.util.concurrent.CompletableFuture.supplyAsync(
                                        java.util.function.Supplier {
                                            try {
                                                thisLogger().info("[tool] (bash-parallel) ${call.name}")
                                                val r = toolRunner.execute(call)
                                                thisLogger().info("[tool] (bash-parallel) done ${call.name}, len=${r.length}")
                                                sendQueue.offer(call.name to r)
                                            } catch (e: Exception) {
                                                sendQueue.offer(call.name to "Error: ${e.message}")
                                            }
                                            Unit
                                        },
                                        pool
                                    )
                                    bashFutures.add(f)
                                }
                            }

                            // --- Файловые: последовательно, одним пакетом в конце ---
                            pool.execute {
                                val sb = StringBuilder()
                                var anyResult = false
                                for ((i, call) in calls.withIndex()) {
                                    if (call.name == "bash") continue
                                    try {
                                        thisLogger().info("[tool] (sequential) ${call.name}")
                                        val r = toolRunner.execute(call)
                                        sb.append("[TOOL_RESULT: ").append(call.name).append("]\n")
                                        sb.append(r).append("\n\n")
                                        anyResult = true
                                    } catch (e: Exception) {
                                        sb.append("[TOOL_RESULT: ").append(call.name).append("]\n")
                                        sb.append("Error: ").append(e.message).append("\n\n")
                                        anyResult = true
                                    }
                                }
                                if (anyResult) {
                                    // Пакет файловых одним сообщением.
                                    // Отправляем через ту же очередь — sender вставит
                                    // его в конец текущего ответа или сразу, если модель молчит.
                                    sendQueue.offer("__file_batch__" to sb.toString().trim())
                                }
                                // Дожидаемся завершения всех bash, чтобы не завершить функцию раньше
                                try {
                                    java.util.concurrent.CompletableFuture.allOf(*bashFutures.toTypedArray()).join()
                                } catch (_: Exception) {}
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
                thisLogger().info("[deepseek] onLoadEnd url=${b?.url} status=$httpStatusCode")
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
            try { browser.jbCefClient.removeLoadHandler(loadHandler, browser.cefBrowser) } catch (_: Exception) {}
            try { Disposer.dispose(jsQuery) } catch (_: Exception) {}
            try { Disposer.dispose(browser) } catch (_: Exception) {}
            thisLogger().info("[deepseek] dispose complete")
        }

        toolWindow.contentManager.addContent(content)
        thisLogger().info("[deepseek] content added")
    }

    /**
     * Считаем, что модель сейчас пишет, если по последнему assistant-сообщению
     * была мутация менее 1.5 сек назад. Пока это так — ждём, чтобы вставить
     * [TOOL_RESULT] в конец ответа, а не разрезать его посередине.
     *
     * Внимание: логика параллельна JS-состоянию lastMutationAt.
     * Мы не имеем прямого доступа к JS-карте из Kotlin, поэтому используем
     * эвристику: спрашиваем состояние через executeJavaScript и ждём ответа
     * асинхронно. Если ответ не пришёл — считаем, что модель молчит.
     */
    private fun isModelWriting(): Boolean {
        // Упрощённая реализация: проверяем глобальный флаг, который выставляет JS.
        // В observer.js добавлено: window.__deepseekAgentModelWriting = true/false
        // на основе lastMutationAt последнего сообщения. Kotlin опрашивает его
        // через флаг, который мы читаем из JBCefJSQuery? — проще не делать,
        // а опираться на паузу. Здесь возвращаем false, чтобы не блокировать;
        // реальная защита — в JS __insertToolResult, который сам ждёт
        // окончания стрима перед вставкой.
        return false
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

            window.__sendToKotlin = function(payload) {
                $injectBody
            };

            window.__deepseekAgentStop = function(reason) {
                autoLoopStopped = true;
                autoLoopStopReason = reason || 'manual';
            };
            window.__deepseekAgentResume = function() {
                autoLoopStopped = false;
                autoIterations = 0;
                lastAutoSendAt = 0;
                autoLoopStopReason = '';
            };
            window.__deepseekAgentStatus = function() {
                return {
                    autoIterations: autoIterations,
                    stopped: autoLoopStopped,
                    reason: autoLoopStopReason,
                    sinceLastSendMs: Date.now() - lastAutoSendAt,
                };
            };
            window.__deepseekAgentDump = function() {
                var items = document.querySelectorAll('[data-virtual-list-item-key]');
                var last = items[items.length - 1];
                if (!last) { console.log('no messages'); return; }
                var c = last.querySelector('.ds-assistant-message-main-content');
                if (c) {
                    console.log('--- innerText ---', c.innerText);
                    console.log('--- pre blocks ---', c.querySelectorAll('pre').length);
                }
            };

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
                    if (desc && desc.set) desc.set.call(ta, newVal); else ta.value = newVal;
                    ta.dispatchEvent(new Event('input', { bubbles: true }));
                    ta.dispatchEvent(new Event('change', { bubbles: true }));
                } else {
                    ta.focus();
                    document.execCommand('selectAll', false, null);
                    document.execCommand('insertText', false, newVal);
                }
            }

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
                setReactValue(ta, SYSTEM_PREAMBLE + '\n\n---\n\n' + val);
                console.log('[deepseek-agent] preamble prepended');
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
                if (PLAN_TRIGGER_RE.test(t)) { if (!planMode) planMode = true; return; }
                if (planMode && PLAN_CONFIRM_RE.test(t)) { planMode = false; }
            }

            function resetAutoLoopForUserTurn() {
                autoIterations = 0;
                autoLoopStopped = false;
                autoLoopStopReason = '';
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

            window.__insertToolResult = function(resultText, retries) {
                retries = retries || 0;
                if (retries > 30) return;
                if (planMode) return;
                if (autoLoopStopped) return;
                if (autoIterations >= MAX_AUTO_ITERATIONS) {
                    autoLoopStopped = true;
                    autoLoopStopReason = 'MAX_AUTO_ITERATIONS';
                    return;
                }

                // ВАЖНО: перед вставкой ждём, чтобы модель закончила текущий ответ.
                // Иначе разрежем стрим и потеряем нить. Проверяем: если последнее
                // сообщение ассистента мутировало меньше 1500 мс назад — стрим идёт.
                var items = document.querySelectorAll('[data-virtual-list-item-key]');
                var lastKey = items.length > 0 ? (items[items.length - 1].getAttribute('data-virtual-list-item-key') || '') : '';
                var lastMut = lastKey ? lastMutationAt.get(lastKey) : null;
                if (typeof lastMut === 'number' && (Date.now() - lastMut) < 1500) {
                    console.log('[deepseek-agent] model is writing, waiting before insert');
                    setTimeout(function() { window.__insertToolResult(resultText, retries + 1); }, 1000);
                    return;
                }

                var since = Date.now() - lastAutoSendAt;
                var wait = AUTO_MIN_INTERVAL_MS - since;
                if (lastAutoSendAt > 0 && wait > 0) {
                    setTimeout(function() { window.__insertToolResult(resultText, retries); }, wait);
                    return;
                }

                var input = findInputTextarea();
                if (!input) {
                    setTimeout(function() { window.__insertToolResult(resultText, retries + 1); }, 500);
                    return;
                }
                var cancelBtn = Array.prototype.find.call(
                    document.querySelectorAll('button, div[role="button"]'),
                    function(b) { return (b.innerText || '').trim() === 'Отмена'; }
                );
                if (cancelBtn) { cancelBtn.click(); setTimeout(function() { window.__insertToolResult(resultText, retries); }, 250); return; }

                pendingResultText = resultText;
                setReactValue(input, resultText);
                autoIterations++;
                lastAutoSendAt = Date.now();
                isAutoSending = true;
                console.log('[deepseek-agent] auto-send #' + autoIterations + '/' + MAX_AUTO_ITERATIONS + ' len=' + resultText.length);

                setTimeout(function() {
                    input.focus();
                    ['keydown','keypress','keyup'].forEach(function(type) {
                        input.dispatchEvent(new KeyboardEvent(type, {
                            key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
                            bubbles: true, cancelable: true
                        }));
                    });
                    setTimeout(function() {
                        var val = input.value || input.innerText || '';
                        if (val.length > 0) {
                            var sendBtn = document.querySelector('button[type="submit"]');
                            if (sendBtn) sendBtn.click();
                        }
                        setTimeout(function() { isAutoSending = false; }, 300);
                        setTimeout(function() { if (!autoLoopStopped) pendingResultText = null; }, 12000);
                    }, 200);
                }, 200);
            };

            var processedKeys = new Set();
            var seenText = new Map();
            var debounceTimers = new Map();
            var lastMutationAt = new Map();
            var lastUrl = location.href;
            var lastMessageCount = -1;
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
                console.log('[deepseek-agent] baseline (' + reason + '): ' + added + ' items');
            }

            function extractToolCallsText(contentEl) {
                var preBlocks = contentEl.querySelectorAll('pre');
                var chunks = [];
                for (var i = 0; i < preBlocks.length; i++) {
                    var t = preBlocks[i].textContent || '';
                    if (TOOL_RE.test(t)) chunks.push(t);
                }
                console.log('[deepseek-agent] pre-blocks: ' + preBlocks.length + ', with TOOL: ' + chunks.length);
                if (chunks.length === 0) return null;
                var combined = chunks.join('\n');
                console.log('[deepseek-agent] collected ' + chunks.length + ' pre-block(s), len=' + combined.length);
                return combined;
            }

            function hasKnownToolCall(text) {
                var re = /\[TOOL:\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*\(/g;
                var m;
                while ((m = re.exec(text)) !== null) {
                    if (KNOWN_TOOLS.indexOf(m[1]) !== -1) return true;
                }
                return false;
            }

            function hasToolbarWithButtons(root) {
                if (!root) return false;
                var bars = root.querySelectorAll('div.ds-flex[style*="gap: 10px"]');
                for (var b = 0; b < bars.length; b++) {
                    var btns = bars[b].querySelectorAll('div[role="button"], button');
                    if (btns.length >= 5) return true;
                }
                return false;
            }

            function isMessageFinalized(itemEl) {
                if (hasToolbarWithButtons(itemEl)) return true;
                var next = itemEl.nextElementSibling;
                if (next && hasToolbarWithButtons(next)) return true;
                var parent = itemEl.parentElement;
                if (parent && hasToolbarWithButtons(parent)) return true;
                var bar = itemEl.closest('[data-virtual-list-item-key], .ds-virtual-list-visible-items');
                if (bar) {
                    var labels = bar.querySelectorAll('[aria-label]');
                    for (var i = 0; i < labels.length; i++) {
                        var lbl = (labels[i].getAttribute('aria-label') || '').toLowerCase();
                        if (lbl === 'читать вслух' || lbl === 'read aloud') return true;
                    }
                }
                var items = document.querySelectorAll('[data-virtual-list-item-key]');
                if (items.length > 0 && items[items.length - 1] === itemEl) {
                    var globalLabels = document.querySelectorAll('[aria-label]');
                    for (var g = 0; g < globalLabels.length; g++) {
                        var gl = (globalLabels[g].getAttribute('aria-label') || '').toLowerCase();
                        if (gl === 'читать вслух' || gl === 'read aloud') return true;
                    }
                }
                var key = itemEl.getAttribute('data-virtual-list-item-key') || '';
                if (key) {
                    var lastMut = lastMutationAt.get(key);
                    if (typeof lastMut === 'number' && (Date.now() - lastMut) > 2500) return true;
                }
                return false;
            }

            function tryProcess(itemEl) {
                var key = itemEl.getAttribute('data-virtual-list-item-key') || '';
                if (!key) return;
                if (processedKeys.has(key)) return;
                if (!isMessageFinalized(itemEl)) { scheduleCheck(itemEl); return; }
                var contentEl = itemEl.querySelector('.ds-assistant-message-main-content');
                if (!contentEl) return;
                var text = extractToolCallsText(contentEl);
                if (!text) return;
                if (!hasKnownToolCall(text)) return;
                if (autoLoopStopped) { processedKeys.add(key); return; }
                var prev = seenText.get(key);
                if (prev === text) return;
                seenText.set(key, text);
                console.log('[deepseek-agent] dispatching tool call key=' + key + ', len=' + text.length);
                window.__sendToKotlin(JSON.stringify({ action: 'tool_calls', key: key, text: text }));
                processedKeys.add(key);
            }

            function scheduleCheck(itemEl) {
                var key = itemEl.getAttribute('data-virtual-list-item-key') || '';
                if (!key) return;
                lastMutationAt.set(key, Date.now());
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
                            var p = n.closest && n.closest('[data-virtual-list-item-key]');
                            if (p) scheduleCheck(p);
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
                    observer.observe(document.body, { childList: true, subtree: true, characterData: true });
                    console.log('[deepseek-agent] observer attached to document.body');
                } catch (e) { console.error('[deepseek-agent] observe failed', e); }
                baselineExisting('attach');
                scanAll();
            }

            function detectRateLimit() {
                var items = document.querySelectorAll('[data-virtual-list-item-key]');
                var lastN = Math.min(items.length, 3);
                for (var k = items.length - lastN; k < items.length; k++) {
                    var it = items[k]; if (!it) continue;
                    var txt = it.innerText || '';
                    for (var m = 0; m < RATE_LIMIT_PATTERNS.length; m++) {
                        if (txt.indexOf(RATE_LIMIT_PATTERNS[m]) !== -1) return RATE_LIMIT_PATTERNS[m];
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
                    if (urlChanged || reset || chatChanged) {
                        if (urlChanged) lastUrl = location.href;
                        setTimeout(function() { baselineExisting(urlChanged ? 'url-change' : 'reset'); }, 800);
                        setTimeout(scanAll, 1200);
                    }
                    if (!autoLoopStopped) {
                        var rl = detectRateLimit();
                        if (rl) {
                            autoLoopStopped = true;
                            autoLoopStopReason = 'rate limit: ' + rl;
                            setTimeout(function() {
                                autoLoopStopped = false;
                                autoLoopStopReason = '';
                                autoIterations = 0;
                                if (pendingResultText) {
                                    var retry = pendingResultText;
                                    pendingResultText = null;
                                    setTimeout(function() { window.__insertToolResult(retry); }, 1000);
                                }
                            }, AUTO_COOLDOWN_AFTER_RATELIMIT_MS);
                        }
                    }
                } catch (e) {}
                setTimeout(watchdog, 2000);
            }

            window.__deepseekAgentEnsure = function() { baselineExisting('ensure'); scanAll(); };

            if (document.readyState === 'complete' || document.readyState === 'interactive') {
                attachObserver(); watchdog();
            } else {
                window.addEventListener('DOMContentLoaded', function() { attachObserver(); watchdog(); });
            }
        })();
        """.trimIndent()
    }

    private class DisposeFlag { @Volatile var value: Boolean = false }

    companion object {
        private val SYSTEM_PREAMBLE = """
            [SYSTEM INSTRUCTIONS]
            Ты — агент, работающий внутри IDE. У тебя ЕСТЬ доступ к файловой системе проекта через инструменты.

            Доступные инструменты:
            - read_file("path"[, "startLine-endLine"]) — читает файл. Для больших файлов указывай диапазон строк, например read_file("big.ts", "120-180").
            - edit_file("path", "old_string", "new_string") — точечная замена. Находит old_string и заменяет на new_string. 4-й необязательный аргумент "all" — заменить все вхождения.
            - write_file("path", "content"[, "append"]) — запись файла. Без 3-го аргумента перезаписывает, с "append" — дописывает в конец. ВАЖНО: при append плагин САМ вставляет \n между старым и новым содержимым, если файл не заканчивался на \n. Тебе не нужно добавлять \n в начало content. Большие НОВЫЕ файлы пиши по частям: первый вызов write_file(path, chunk1), затем write_file(path, chunk2, "append"), и так далее.
            - list_files("path") — показывает содержимое директории.
            - bash("command") — выполняет shell-команду в корне проекта.
            - search_text("needle") — ищет текст по всем файлам проекта.

            ПАРАЛЛЕЛЬНОСТЬ:
            - Несколько bash в одном ходу запускаются ПАРАЛЛЕЛЬНО.
            - Все файловые операции (read_file, write_file, edit_file, list_files, search_text) выполняются ПОСЛЕДОВАТЕЛЬНО в порядке вызова.
            - Результаты bash приходят по одному, по мере завершения.
            - Результаты всех файловых операций приходят одним пакетом, после того как отработали все.
            - НЕ ПОВТОРЯЙ вызов, если результата ещё нет. Дождись.

            !!! ФОРМАТ ВЫЗОВА: ОБОРАЧИВАЙ В ```tool ... ``` !!!
            
            ВАЖНО: любой вызов — только внутри ```tool```-блока. Вызовы в обычном тексте (без блока) ИГНОРИРУЮТСЯ и не выполняются.

            Web-интерфейс DeepSeek рендерит Markdown прямо в сообщении: #, *, _, backticks удаляются из видимого текста до того, как инструмент его получит. Поэтому ВСЕГДА оборачивай ВСЕ вызовы в блок кода с языком "tool":

            ```tool
            [TOOL: write_file("README.md", "## Заголовок\n\nТекст с \`inline code\` и блоком:\n\`\`\`bash\nnpm run dev\n\`\`\`\n")]
            ```
            
            Внутри этого блока ты пишешь вызов как обычно: один или несколько вызовов, каждый на своей строке. Не бойся многострочных аргументов — внутри tool ... вся разметка сохраняется дословно.

            МОЖНО ВЫЗЫВАТЬ НЕСКОЛЬКО ИНСТРУМЕНТОВ ЗА ОДИН ХОД. Если действия независимы (создать 3 файла, прочитать 3 разных файла) — пиши их подряд в одном ```tool блоке. Результаты придут вместе, одним сообщением с несколькими [TOOL_RESULT: имя].

            КАК ВЫЗЫВАТЬ (формат внутри ```tool):
                [TOOL: имя_инструмента("аргумент1", "аргумент2")]
                Аргументы в двойных кавычках, разделитель — запятая. Внутри строк можно использовать \n, \t, ", .
                После закрывающих ``` не пиши больше вызовов — просто заверши ход коротким текстом.

            РЕЖИМ ПЛАНА (PLAN MODE):
                Если пользователь написал в начале сообщения слово «план» или «plan» (например: «план: отрефактори модуль авторизации») — ты НЕ вызываешь инструменты. Вместо этого составляешь подробный пошаговый план:
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
                - Не вызывай bash ради «посмотреть». Если файл можно прочитать через read_file — читай через read_file, а не cat. Если нужен список файлов — list_files, а не ls. Если нужен поиск — search_text, а не grep.
                - Если запускаешь bash с ожиданием (sleep, длинные команды) — запусти его ПАРАЛЛЕЛЬНО с другими вызовами, а не отдельно. Один bash в ходу — норма; один bash с ожиданием без других задач — потеря времени (только если это не отдельное / обязательное действие).

            ПЛЕЙСХОЛДЕРЫ — НЕ ПИШИ:
                - НИКОГДА не пиши примеры вроде [TOOL: name(...)] или [TOOL: имя_инструмента(...)] — они будут восприняты как реальный вызов. Даже в ```tool блоке. Если нужно объяснить синтаксис — пиши словами. В крайнем случае можно обернуть пример в одинарные бэккеты (`[TOOL: bash()]`), но НЕ РЕКОМЕНДУЕТСЯ. НИКОГДА не подставляй в аргументы пути-заглушки: три точки, две точки, одну точку, path, path/to/file, <path>, your_file, filename — это тоже плейсхолдер. Если конкретный путь неизвестен — сначала вызови list_files(".") или search_text с осмысленным запросом, чтобы узнать реальный путь.

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