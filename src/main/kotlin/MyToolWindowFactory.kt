package ru.ichuk.deepseek

import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.concurrency.AppExecutorUtil
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.JLabel
import javax.swing.SwingConstants
import javax.swing.UIManager

class MyToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (!JBCefApp.isSupported()) {
            val label = JLabel("JCEF is not available in this IDE build.", SwingConstants.CENTER)
            label.foreground = UIManager.getColor("Label.disabledForeground")
            toolWindow.contentManager.addContent(
                ContentFactory.getInstance().createContent(label, "DeepSeek", false)
            )
            return
        }

        val disposed = DisposeFlag()
        val browser = JBCefBrowser.createBuilder().setOffScreenRendering(false).build()
        val toolRunner = ToolRunner(project)
        val jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        val pool = AppExecutorUtil.getAppExecutorService()

        jsQuery.addHandler { payload ->
            if (disposed.value) return@addHandler JBCefJSQuery.Response("disposed")
            try {
                val json = JsonParser.parseString(payload).asJsonObject
                when (json.get("action")?.asString) {
                    "tool_calls" -> {
                        val text = json.get("text")?.asString.orEmpty()
                        val runId = json.get("runId")?.asString.orEmpty()
                            .filter { it.isLetterOrDigit() }.take(32)
                        pool.execute { runToolBatch(toolRunner, browser, disposed, pool, runId, text) }
                    }
                    "log" -> thisLogger().info("[js] " + json.get("text")?.asString.orEmpty())
                }
                JBCefJSQuery.Response("ok")
            } catch (e: Exception) {
                thisLogger().warn("[bridge] handler failed", e)
                JBCefJSQuery.Response("error: ${e.message}")
            }
        }

        val injectBody = jsQuery.inject(
            "payload",
            "response => { /* ok */ }",
            "error => { console.error('[to-kotlin-err]', error); }"
        )

        val loadHandler = object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(b: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (disposed.value || frame?.isMain != true || b == null) return
                try {
                    b.executeJavaScript(buildAgentScript(injectBody), b.url, 0)
                } catch (e: Exception) {
                    thisLogger().warn("[deepseek] injection failed: ${e.message}")
                }
            }
        }

        browser.jbCefClient.addLoadHandler(loadHandler, browser.cefBrowser)
        browser.loadURL("https://chat.deepseek.com")

        val content = ContentFactory.getInstance().createContent(browser.component, "DeepSeek", false)
        content.setDisposer {
            disposed.value = true
            try { browser.jbCefClient.removeLoadHandler(loadHandler, browser.cefBrowser) } catch (_: Exception) {}
            try { Disposer.dispose(jsQuery) } catch (_: Exception) {}
            try { Disposer.dispose(browser) } catch (_: Exception) {}
        }
        toolWindow.contentManager.addContent(content)
    }

    // ---------- Выполнение пакета вызовов ----------

    private fun runToolBatch(
        toolRunner: ToolRunner,
        browser: JBCefBrowser,
        disposed: DisposeFlag,
        pool: java.util.concurrent.ExecutorService,
        runId: String,
        text: String
    ) {
        if (disposed.value) return

        val calls = try { toolRunner.parseToolCalls(text) } catch (e: Exception) {
            thisLogger().warn("[tool] parse failed", e)
            emptyList()
        }
        thisLogger().info("[tool] parsed ${calls.size} call(s), runId=$runId")

        // Ответ отправляем ВСЕГДА, иначе JS остаётся в состоянии ожидания.
        if (calls.isEmpty()) {
            sendToBrowser(
                browser, disposed, runId,
                "[TOOL_ERROR]\nНе удалось разобрать ни одного вызова инструмента.\n" +
                        "Проверь синтаксис: [TOOL: name(\"arg1\", \"arg2\")] внутри блока ```tool, " +
                        "все кавычки и скобки должны быть закрыты."
            )
            return
        }

        val results = arrayOfNulls<String>(calls.size)

        // bash — параллельно, файловые операции — последовательно, порядок вывода сохраняем.
        val futures = HashMap<Int, CompletableFuture<String>>()
        calls.forEachIndexed { i, call ->
            if (call.name == "bash") {
                futures[i] = CompletableFuture.supplyAsync({ toolRunner.execute(call) }, pool)
            }
        }
        calls.forEachIndexed { i, call ->
            if (call.name != "bash") {
                results[i] = if (disposed.value) "[cancelled]" else toolRunner.execute(call)
            }
        }
        futures.forEach { (i, f) ->
            results[i] = try {
                f.get(BATCH_TIMEOUT_SEC, TimeUnit.SECONDS)
            } catch (e: Exception) {
                f.cancel(true)
                "Error: ${e.javaClass.simpleName}: ${e.message}"
            }
        }

        val sb = StringBuilder()
        calls.forEachIndexed { i, call ->
            val hint = call.args.firstOrNull()?.take(60)?.replace("\n", " ") ?: ""
            sb.append("[TOOL_RESULT: ").append(call.name)
            if (hint.isNotEmpty()) sb.append(" — ").append(hint)
            sb.append("]\n").append(results[i] ?: "(no result)").append("\n\n")
        }

        sendToBrowser(browser, disposed, runId, sb.toString().trim())
    }

    private fun sendToBrowser(browser: JBCefBrowser, disposed: DisposeFlag, runId: String, result: String) {
        val capped = if (result.length > MAX_RESULT_CHARS)
            result.take(MAX_RESULT_CHARS) + "\n\n... [общий результат обрезан до $MAX_RESULT_CHARS символов]"
        else result

        // Base64 полностью снимает проблемы экранирования кавычек/переносов/юникода.
        val b64 = Base64.getEncoder().encodeToString(capped.toByteArray(StandardCharsets.UTF_8))
        val js = "window.__dsInsertToolResult && window.__dsInsertToolResult('$runId','$b64');"

        ApplicationManager.getApplication().invokeLater {
            if (disposed.value) return@invokeLater
            try {
                browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
            } catch (e: Exception) {
                thisLogger().warn("[deepseek] executeJavaScript failed: ${e.message}")
            }
        }
    }

    // ---------- Инжектируемый скрипт ----------

    private fun buildAgentScript(injectBody: String): String {
        val enc = Base64.getEncoder()
        val preambleB64 = enc.encodeToString(SYSTEM_PREAMBLE.toByteArray(StandardCharsets.UTF_8))
        // Фразы rate-limit держим в base64: иначе чтение исходников самого плагина
        // добавляет их в DOM чата, и детектор срабатывает на собственном коде.
        val phrasesB64 = enc.encodeToString(
            listOf(
                "\u0421\u043B\u0438\u0448\u043A\u043E\u043C \u0447\u0430\u0441\u0442\u044B\u0435 \u0441\u043E\u043E\u0431\u0449\u0435\u043D\u0438\u044F",
                "Too many requests",
                "\u041F\u043E\u0432\u0442\u043E\u0440\u0438\u0442\u0435 \u043F\u043E\u043F\u044B\u0442\u043A\u0443 \u043F\u043E\u0437\u0436\u0435"
            ).joinToString("\u0001").toByteArray(StandardCharsets.UTF_8)
        )

        return """
        (function() {
            if (window.__dsAgentV4) { console.log('[ds-agent] already injected'); return; }
            window.__dsAgentV4 = true;

            function b64d(s) {
                var bin = atob(s), bytes = new Uint8Array(bin.length);
                for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                return new TextDecoder('utf-8').decode(bytes);
            }

            var SYSTEM_PREAMBLE = b64d('$preambleB64');
            var RL_PHRASES = b64d('$phrasesB64').split('\u0001');

            var CFG = {
                pollMs: 700,
                stableMs: 1200,          // ответ считаем законченным, если текст не менялся
                minSendInterval: 2500,
                maxIterations: 60,
                toolTimeoutMs: 180000,   // страховка, если Kotlin не ответил
                generatingMaxMs: 300000, // страховка от залипшего индикатора генерации
                sendAttempts: 8,
                verifyMs: 3500,          // окно подтверждения доставки
                rlWaitMs: 20000,         // пауза при rate limit
                rlMaxWaits: 8,
                rlBackoffMs: 7000,       // интервал между отправками после лимита
                failWaitMs: 3000,        // не-лимитная ошибка отправки
                retryCheckMs: 9000,      // сколько ждем результата клика по кнопке повтора
                rlMaxLen: 160,           // длиннее — это текст чата, а не предупреждение
                maxBlockChars: 4000,
                maxSameCall: 3
            };

            // state: idle | stream | tools | send | verify | cooldown
            var S = {
                state: 'idle', stateAt: Date.now(),
                lastSendAt: 0, minInterval: CFG.minSendInterval,
                iterations: 0, stopped: false,
                generatingSince: 0,
                processed: {}, snap: {}, handledErr: {},
                lastCallText: '', sameCallCount: 0,
                pendingText: null, rlHits: 0
            };

            function isBusy() {
                return S.state === 'send' || S.state === 'verify' || S.state === 'cooldown';
            }

            window.__dsSendToKotlin = function(payload) {
                $injectBody
            };

            // ---------------- статус ----------------

            var badge = null, statusText = '';

            function setStatus(t) {
                if (t === statusText) return;
                statusText = t;
                console.log('[ds-agent]', S.state, '|', t);
                try {
                    if (!badge || !badge.isConnected) {
                        badge = document.createElement('div');
                        badge.style.cssText = 'position:fixed;right:12px;bottom:12px;z-index:2147483647;' +
                            'font:11px/1.5 monospace;padding:3px 8px;border-radius:6px;max-width:60vw;' +
                            'background:rgba(0,0,0,.65);color:#fff;pointer-events:none;opacity:.8';
                        document.documentElement.appendChild(badge);
                    }
                    badge.textContent = 'agent: ' + t;
                } catch (e) {}
            }

            function setState(s, msg) {
                if (S.state !== s) { S.state = s; S.stateAt = Date.now(); }
                if (msg) setStatus(msg);
            }

            function hashOf(s) {
                var h = 5381;
                for (var i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0;
                return 'h' + (h >>> 0);
            }

            // ---------------- элементы страницы ----------------

            function findInput() {
                var el = document.getElementById('chat-input');
                if (el) return el;
                var all = document.querySelectorAll('textarea');
                for (var i = 0; i < all.length; i++) {
                    var ph = (all[i].placeholder || '').toLowerCase();
                    if (ph.indexOf('deepseek') !== -1 || ph.indexOf('сообщен') !== -1 || ph.indexOf('message') !== -1) return all[i];
                }
                if (all.length > 0) return all[all.length - 1];
                return document.querySelector('div[contenteditable="true"]');
            }

            function readValue(el) {
                return el ? ((el.tagName === 'TEXTAREA' ? el.value : el.innerText) || '') : '';
            }

            function setValue(el, v) {
                if (!el) return;
                if (el.tagName === 'TEXTAREA') {
                    var d = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value');
                    if (d && d.set) d.set.call(el, v); else el.value = v;
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                } else {
                    el.focus();
                    document.execCommand('selectAll', false, null);
                    document.execCommand('insertText', false, v);
                }
            }

            function isVisible(el) {
                if (!el || !el.isConnected) return false;
                var r = el.getBoundingClientRect();
                return r.width > 0 && r.height > 0;
            }

            function findSendButton(input) {
                if (!input) return null;
                var parent = input.parentElement, fallback = null;
                for (var i = 0; i < 5 && parent; i++) {
                    var btns = parent.querySelectorAll('div[role="button"], button');
                    for (var j = btns.length - 1; j >= 0; j--) {
                        var b = btns[j];
                        if (b.contains(input)) continue;
                        var lbl = (b.getAttribute('aria-label') || '').toLowerCase();
                        if (lbl.indexOf('send') !== -1 || lbl.indexOf('отправ') !== -1) return b;
                        if (!fallback && (b.querySelector('svg') || b.getAttribute('type') === 'submit')) fallback = b;
                    }
                    parent = parent.parentElement;
                }
                return fallback;
            }

            function pressEnter(el) {
                var o = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
                el.dispatchEvent(new KeyboardEvent('keydown', o));
                el.dispatchEvent(new KeyboardEvent('keypress', o));
                el.dispatchEvent(new KeyboardEvent('keyup', o));
            }

            function isGenerating() {
                var gen = false;
                try {
                    if (document.querySelectorAll('[aria-label*="Stop" i],[aria-label*="Останов" i]').length > 0) gen = true;
                } catch (e) {}
                if (!gen) {
                    var b = findSendButton(findInput());
                    var l = b ? (b.getAttribute('aria-label') || '').toLowerCase() : '';
                    if (l.indexOf('stop') !== -1 || l.indexOf('останов') !== -1) gen = true;
                }
                if (gen) {
                    if (!S.generatingSince) S.generatingSince = Date.now();
                    if (Date.now() - S.generatingSince > CFG.generatingMaxMs) return false;
                    return true;
                }
                S.generatingSince = 0;
                return false;
            }

            // ---------------- непринятое сообщение и кнопка повтора ----------------

            function matchesRl(t) {
                if (!t) return false;
                var s = t.trim();
                if (!s || s.length > CFG.rlMaxLen) return false;
                for (var i = 0; i < RL_PHRASES.length; i++) {
                    if (s.indexOf(RL_PHRASES[i]) !== -1) return true;
                }
                return false;
            }

            function scopeKey(scope) {
                var k = scope.getAttribute && scope.getAttribute('data-virtual-list-item-key');
                return 'e' + (k || hashOf((scope.textContent || '').slice(0, 120)));
            }

            // Оранжевая круглая кнопка повтора у непринятого сообщения:
            // div[role=button].ds-button--warning.ds-button--filled.ds-button--circle
            function findRetryIn(scope) {
                var sel = '[role="button"][class*="ds-button--warning"], button[class*="ds-button--warning"]';
                var found = scope.querySelectorAll(sel);
                for (var i = 0; i < found.length; i++) {
                    if (isVisible(found[i])) return found[i];
                }
                var up = scope.parentElement;
                for (var h = 0; h < 2 && up; h++) {
                    var f2 = up.querySelectorAll(sel);
                    for (var j = 0; j < f2.length; j++) {
                        if (isVisible(f2[j])) return f2[j];
                    }
                    up = up.parentElement;
                }
                return null;
            }

            // Текст ошибки лежит в блоке, соседнем с пузырем .ds-message,
            // поэтому осматриваем контейнер элемента списка целиком.
            function findFailedSend() {
                var scopes = [];
                var items = document.querySelectorAll('[data-virtual-list-item-key]');
                for (var i = items.length - 1; i >= Math.max(0, items.length - 2); i--) scopes.push(items[i]);
                if (!scopes.length) {
                    var msgs = document.querySelectorAll('.ds-message');
                    if (msgs.length && msgs[msgs.length - 1].parentElement) scopes.push(msgs[msgs.length - 1].parentElement);
                }
                var toasts = document.querySelectorAll('[role="alert"],[class*="toast"],[class*="Toast"]');
                for (var t = 0; t < toasts.length && t < 15; t++) scopes.push(toasts[t]);

                for (var s = 0; s < scopes.length; s++) {
                    var scope = scopes[s];
                    if (!scope) continue;
                    var key = scopeKey(scope);
                    if (S.handledErr[key]) continue;

                    var err = null;
                    var leaves = scope.querySelectorAll('span,div,p');
                    for (var j = 0; j < leaves.length; j++) {
                        var el = leaves[j];
                        if (el.children.length !== 0) continue;   // только листовые узлы
                        if (el.closest('pre,code')) continue;     // текст из блока кода — не ошибка
                        if (!matchesRl(el.textContent)) continue;
                        if (!isVisible(el)) continue;
                        err = el; break;
                    }
                    var retry = findRetryIn(scope);
                    if (!err && !retry) continue;

                    return { scope: scope, key: key, err: err, retry: retry, rate: !!err };
                }
                return null;
            }

            // ---------------- поиск вызовов инструментов ----------------

            var TOOL_RE = /\[TOOL:\s*(read_file|write_file|edit_file|list_files|bash|search_text)\s*\(/;
            var RESULT_RE = /^\s*\[TOOL_(RESULT|ERROR)/;
            var SOURCE_RE = /(^|\n)\s*(import |package |private fun |fun |val |var |public class )/;

            function blockLang(pre) {
                var root = pre.closest('.md-code-block') || pre.parentElement;
                if (!root) return '';
                var info = root.querySelector('.md-code-block-infostring, .md-code-block-banner');
                return info ? (info.textContent || '').trim().toLowerCase().split(/\s+/)[0] : '';
            }

            function assistantBody(item) {
                var b = item.querySelector('.ds-assistant-message-main-content, .ds-markdown');
                if (b) return b;
                if (RESULT_RE.test(item.textContent || '')) return null;  // это наш результат, не ответ модели
                return item.querySelector('pre') ? item : null;
            }

            function extractCalls(body) {
                if (!body) return null;
                var pres = body.querySelectorAll('pre'), tagged = [], loose = [];
                for (var i = 0; i < pres.length; i++) {
                    var code = pres[i].querySelector('code') || pres[i];
                    var t = code.textContent || '';
                    if (!TOOL_RE.test(t) || t.length > CFG.maxBlockChars) continue;
                    if (blockLang(pres[i]) === 'tool') tagged.push(t);
                    else if (!SOURCE_RE.test(t)) loose.push(t);
                }
                if (tagged.length) return tagged.join('\n');
                if (loose.length) return loose.join('\n');
                if (pres.length > 0) return null;   // код есть, но это не вызовы
                var all = body.textContent || '';
                if (all.length > CFG.maxBlockChars || SOURCE_RE.test(all)) return null;
                return TOOL_RE.test(all) ? all : null;
            }

            function lastAssistantWithCalls() {
                var items = document.querySelectorAll('[data-virtual-list-item-key]');
                for (var i = items.length - 1; i >= Math.max(0, items.length - 4); i--) {
                    var body = assistantBody(items[i]);
                    if (!body) continue;
                    var text = extractCalls(body);
                    if (!text) return null;          // последний ответ модели без вызовов — ждем
                    return {
                        key: (items[i].getAttribute('data-virtual-list-item-key') || 'k') + '|' + hashOf(text),
                        text: text
                    };
                }
                return null;
            }

            function baseline() {
                // Дедуп идет по хешу текста, поэтому после перезагрузки страницы
                // работа продолжается корректно и ничего не помечается виденным заранее.
                var f = lastAssistantWithCalls();
                if (f) S.snap[f.key] = { at: Date.now() };
            }

            // ---------------- главный цикл ----------------

            function tick() {
                try { poll(); } catch (e) { console.error('[ds-agent] poll error', e); }
                setTimeout(tick, CFG.pollMs);
            }

            function poll() {
                if (S.stopped) return;
                if (isBusy()) return;               // отправкой управляют send/verify/cooldown

                if (S.state === 'tools') {
                    if (Date.now() - S.stateAt > CFG.toolTimeoutMs) {
                        setState('idle', 'таймаут инструментов');
                    }
                    return;
                }

                if (isGenerating()) { setStatus('генерация ответа'); return; }

                var found = lastAssistantWithCalls();
                if (!found) { setState('idle', 'ожидание'); return; }
                if (S.processed[found.key]) { setState('idle', 'ожидание'); return; }

                var sn = S.snap[found.key];
                if (!sn) { S.snap[found.key] = { at: Date.now() }; setState('stream', 'дочитываю ответ'); return; }
                if (Date.now() - sn.at < CFG.stableMs) return;

                if (found.text === S.lastCallText) {
                    S.sameCallCount++;
                    if (S.sameCallCount > CFG.maxSameCall) {
                        S.stopped = true;
                        setState('idle', 'цикл: модель повторяет вызов');
                        return;
                    }
                } else {
                    S.lastCallText = found.text;
                    S.sameCallCount = 1;
                }

                if (S.iterations >= CFG.maxIterations) {
                    S.stopped = true;
                    setState('idle', 'лимит итераций');
                    return;
                }

                S.processed[found.key] = true;
                setState('tools', 'выполняю инструменты');
                window.__dsSendToKotlin(JSON.stringify({
                    action: 'tool_calls', runId: 'r' + Date.now(), key: found.key, text: found.text
                }));
            }

            // ---------------- приём результата и отправка ----------------

            window.__dsInsertToolResult = function(runId, b64) {
                var text;
                try { text = b64d(b64); } catch (e) { text = '[TOOL_RESULT]\ndecode error: ' + e; }
                S.pendingText = text;
                S.rlHits = 0;
                setState('send', 'готовлю отправку');
                queueSend(0);
            };

            function abortSend(msg) { setState('idle', msg + ' — __dsRetry()'); }

            function queueSend(attempt) {
                if (S.state !== 'send' || S.pendingText === null) return;
                if (attempt > 40) { abortSend('не удалось отправить'); return; }

                var f = findFailedSend();
                if (f) { cooldown(f); return; }

                if (isGenerating()) {
                    setStatus('жду окончания генерации');
                    setTimeout(function() { queueSend(attempt + 1); }, 800);
                    return;
                }

                var wait = S.minInterval - (Date.now() - S.lastSendAt);
                if (S.lastSendAt > 0 && wait > 0) {
                    setStatus('пауза ' + Math.ceil(wait / 1000) + ' с');
                    setTimeout(function() { queueSend(attempt); }, Math.min(wait, 1200));
                    return;
                }

                var input = findInput();
                if (!input) { setTimeout(function() { queueSend(attempt + 1); }, 500); return; }

                setValue(input, S.pendingText);
                S.iterations++;
                S.lastSendAt = Date.now();
                setStatus('отправка (' + S.iterations + '/' + CFG.maxIterations + ')');
                setTimeout(function() { sendLoop(input, 0); }, 250);
            }

            function sendLoop(input, attempt) {
                if (S.state !== 'send') return;
                var f = findFailedSend();
                if (f) { cooldown(f); return; }
                if (readValue(input).trim() === '') {
                    setState('verify', 'проверяю доставку');
                    verify(Date.now());
                    return;
                }
                if (attempt >= CFG.sendAttempts) { abortSend('кнопка не сработала'); return; }
                input.focus();
                var btn = findSendButton(input);
                if (attempt % 2 === 0 && btn && btn.getAttribute('aria-disabled') !== 'true') btn.click();
                else pressEnter(input);
                setTimeout(function() { sendLoop(input, attempt + 1); }, 900);
            }

            // Пустое поле ввода еще не значит доставку: ошибка появляется с задержкой.
            function verify(startedAt) {
                if (S.state !== 'verify') return;
                var f = findFailedSend();
                if (f) { cooldown(f); return; }
                if (isGenerating()) { sendAccepted(); return; }
                if (Date.now() - startedAt >= CFG.verifyMs) { sendAccepted(); return; }
                setTimeout(function() { verify(startedAt); }, 400);
            }

            function sendAccepted() {
                S.pendingText = null;
                if (S.minInterval > CFG.minSendInterval) {
                    S.minInterval = Math.max(CFG.minSendInterval, S.minInterval - 1000);
                }
                setState('idle', 'результат отправлен');
            }

            function cooldown(f) {
                if (S.state === 'cooldown') return;
                S.rlHits++;
                if (f && f.rate && S.minInterval < CFG.rlBackoffMs) S.minInterval = CFG.rlBackoffMs;

                if (S.rlHits > CFG.rlMaxWaits) {
                    if (f) S.handledErr[f.key] = true;
                    abortSend('лимит не спадает');
                    return;
                }

                var waitMs = (f && f.rate) ? CFG.rlWaitMs : CFG.failWaitMs;
                var startedAt = Date.now();
                setState('cooldown', (f && f.rate ? 'rate limit' : 'ошибка отправки') +
                    ': пауза (' + S.rlHits + '/' + CFG.rlMaxWaits + ')');

                (function waitLoop() {
                    if (S.state !== 'cooldown') return;
                    var left = waitMs - (Date.now() - startedAt);
                    if (left > 0) {
                        setStatus((f && f.rate ? 'rate limit: ' : 'повтор через ') +
                            Math.ceil(left / 1000) + ' с (' + S.rlHits + '/' + CFG.rlMaxWaits + ')');
                        setTimeout(waitLoop, 1000);
                        return;
                    }

                    var cur = findFailedSend();
                    var retry = cur && cur.retry;
                    if (retry) {
                        setState('verify', 'жму кнопку повтора');
                        S.lastSendAt = Date.now();
                        try { retry.click(); } catch (e) { console.warn('[ds-agent] retry click failed', e); }
                        setTimeout(function() { afterRetryClick(cur, Date.now()); }, 800);
                    } else {
                        // Кнопки нет — помечаем ошибку отработанной, иначе она навсегда
                        // останется в хвосте чата и будет триггерить cooldown по кругу.
                        if (cur) S.handledErr[cur.key] = true;
                        S.lastSendAt = Date.now() - S.minInterval;
                        setState('send', 'повторная отправка текстом');
                        queueSend(0);
                    }
                })();
            }

            function afterRetryClick(f, startedAt) {
                if (S.state !== 'verify') return;
                var still = findFailedSend();
                if (!still || still.key !== f.key) { verify(Date.now()); return; }   // ошибка ушла
                if (isGenerating()) { sendAccepted(); return; }
                if (Date.now() - startedAt >= CFG.retryCheckMs) {
                    S.state = 'send';            // снимаем guard и уходим на новый круг
                    cooldown(still);
                    return;
                }
                setStatus('жду результат повтора');
                setTimeout(function() { afterRetryClick(f, startedAt); }, 500);
            }

            // ---------------- ручная отправка ----------------

            function needsPreamble(input) {
                if (document.querySelectorAll('[data-virtual-list-item-key]').length !== 0) return false;
                var v = readValue(input);
                return v.trim().length > 0 && v.indexOf('[SYSTEM INSTRUCTIONS]') !== 0;
            }

            function resetLoop() {
                S.iterations = 0; S.stopped = false; S.sameCallCount = 0; S.rlHits = 0;
                S.handledErr = {};
                if (S.state !== 'tools' && !isBusy()) setState('idle', 'готов');
            }

            function injectPreambleAndSend(input) {
                setValue(input, SYSTEM_PREAMBLE + '\n\n---\n\n' + readValue(input));
                setTimeout(function() {
                    var b = findSendButton(input);
                    if (b) b.click(); else pressEnter(input);
                }, 200);
            }

            document.addEventListener('keydown', function(e) {
                if (!e.isTrusted || isBusy()) return;          // игнорируем свои же события
                if (e.key !== 'Enter' || e.shiftKey || e.ctrlKey || e.metaKey || e.altKey || e.isComposing) return;
                var input = findInput();
                if (!input) return;
                resetLoop();
                if (!needsPreamble(input)) return;
                e.preventDefault(); e.stopImmediatePropagation();
                injectPreambleAndSend(input);
            }, true);

            document.addEventListener('click', function(e) {
                if (!e.isTrusted || isBusy()) return;
                var input = findInput();
                if (!input) return;
                var btn = findSendButton(input);
                if (!btn || !(btn === e.target || btn.contains(e.target))) return;
                resetLoop();
                if (!needsPreamble(input)) return;
                e.preventDefault(); e.stopImmediatePropagation();
                injectPreambleAndSend(input);
            }, true);

            // ---------------- отладка из консоли ----------------

            window.__dsStatus = function() { return S; };

            window.__dsRetry = function() {
                S.stopped = false; S.rlHits = 0; S.handledErr = {};
                if (S.pendingText !== null) { setState('send', 'повтор'); queueSend(0); }
                else setState('idle', 'готов');
            };

            window.__dsProbeRetry = function() {
                var f = findFailedSend();
                console.log('[ds-agent] failed send:', f);
                if (f) console.log('  retry btn:', f.retry, '| err:', f.err && f.err.textContent);
                return f;
            };

            window.__dsStop = function() { S.stopped = true; setState('idle', 'остановлен вручную'); };

            baseline();
            setState('idle', 'готов');
            tick();
            console.log('[ds-agent] v4 injected');
        })();
        """.trimIndent()
    }

    private class DisposeFlag { @Volatile var value: Boolean = false }

    companion object {
        private const val MAX_RESULT_CHARS = 60_000
        private const val BATCH_TIMEOUT_SEC = 120L

        private val SYSTEM_PREAMBLE = """
            [SYSTEM INSTRUCTIONS]
            Ты — автономный ИИ-ассистент, встроенный в IDE. У тебя есть прямой доступ к проекту.

            Доступные инструменты:
            - read_file("path"[, "startLine-endLine"]) — чтение файла
            - edit_file("path", "old_string", "new_string"[, "all"]) — точечная замена
            - write_file("path", "content"[, "append"]) — создание/перезапись файла
            - list_files("path") — список файлов
            - bash("command") — терминал
            - search_text("needle") — поиск по коду

            КРИТИЧЕСКИ ВАЖНО:
            Любой вызов делай ТОЛЬКО внутри блока кода с языком tool:
            ```tool
            [TOOL: list_files(".")]
            ```
            Если написать вызов без блока ```tool, веб-интерфейс сотрет все символы Markdown (#, *, |, backticks) из аргументов до их выполнения.
            Все аргументы — в двойных кавычках, скобки и кавычки внутри строк экранируй через \.
            Результаты всех вызванных инструментов придут в следующем сообщении одним общим блоком.
            Записывать и изменять файлы можно только внутри корня проекта.
            [/SYSTEM INSTRUCTIONS]
        """.trimIndent()
    }
}
