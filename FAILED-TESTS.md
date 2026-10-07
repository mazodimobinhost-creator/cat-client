PASS cat-panel
FAIL panel-dom
```
Could not parse CSS stylesheet
https://p.workers.dev/:260
function renderPxTest(){var px=CFG.settings.proxyIps||[],TT=window.__ipTest||{},box=$('#pxTestChips');if(!box)return;box.innerHTML=px.map(function(p){var st=TT[p];var w=st?(st.ok?'<span style="color:#34d399">relay✓ '+st.ms+'ms</span>':'<span style="color:#f87171">relay✗'+(st.error?' · '+esc(String(st.error).slice(0,42)):'')+'</span>'):'<span class="dim">—</span>';var isSk=(/^socks5h?:///i).test(p);var lbl=isSk?'🧦 '+p.replace(/^socks5h?:///i,'').replace(/^[^@/]*@/,''):'🎯 '+p;return '<span class="chip mono">'+lbl+' '+w+'</span>'}).join('')}
^^^^^^^^

SyntaxError: Unexpected token 'function'
    at new Script (node:vm:117:7)
    at createScript (node:vm:269:10)
    at Object.runInContext (node:vm:300:10)
    at processJavaScript (/home/runner/work/cat-client/cat-client/node_modules/jsdom/lib/jsdom/living/nodes/HTMLScriptElement-impl.js:274:10)
    at HTMLScriptElementImpl._innerEval (/home/runner/work/cat-client/cat-client/node_modules/jsdom/lib/jsdom/living/nodes/HTMLScriptElement-impl.js:207:5)
    at /home/runner/work/cat-client/cat-client/node_modules/jsdom/lib/jsdom/living/nodes/HTMLScriptElement-impl.js:129:12
    at ResourceQueue.push (/home/runner/work/cat-client/cat-client/node_modules/jsdom/lib/jsdom/browser/resources/resource-queue.js:53:16)
    at HTMLScriptElementImpl._fetchInternalScript (/home/runner/work/cat-client/cat-client/node_modules/jsdom/lib/jsdom/living/nodes/HTMLScriptElement-impl.js:128:21)
    at HTMLScriptElementImpl._eval (/home/runner/work/cat-client/cat-client/node_modules/jsdom/lib/jsdom/living/nodes/HTMLScriptElement-impl.js:198:12)
    at HTMLScriptElementImpl._poppedOffStackOfOpenElements (/home/runner/work/cat-client/cat-client/node_modules/jsdom/lib/jsdom/living/nodes/HTMLScriptElement-impl.js:149:12)
(node:2528) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✗ no JS errors on load — https://p.workers.dev/:260
function renderPxTest(){var px=CFG.settings.proxyIps||[],TT=window.__ipTest||{},box=$('#pxTestChips');if(!box)return;box.innerHTML=px.map(function(p){var st=TT[p];var w=st?(st.ok?'<span style="color:#34d399">relay✓ '+st.ms+'ms</span>':'<span style="color:#f87171">relay✗'+(st.error?' · '+esc(String(st.error).slice(0,42)):'')+'</span>'):'<span class="dim">—</span>';var isSk=(/^socks5h?:///i).test(p);var lbl=isSk?'🧦 '+p.replace(/^socks5h?:///i,'').replace(/^[^@/]*@/,''):'🎯 '+p;return '<span class="chip mono">'+lbl+' '+w+'</span>'}).join('')}
^^^^^^^^

SyntaxError: Unexpected token 'function'
    at new Script (node:vm:117:7)
    at createScript (node:vm:269:10)
    at Object.runInContext (node:vm:300:10)
    at processJavaScript (/home/runner/work/cat-client/cat-client/node_modules/jsdom/lib/jsdom/living/nodes/HTMLScriptElement-impl.js:274:10)
    at HTMLScriptElementImpl._innerEval (/home/runner/work/cat-client/cat-client/node_modules/jsdom/lib/jsdom/living/nodes/HTMLScriptElement-impl.js:207:5)
    at /home/runner/work/cat-client/cat-client/node_modules/jsdom/lib/jsdom/living/nodes/HTMLScriptElement-impl.js:129:12
    at ResourceQueue.push (/home/runner/work/cat-client/cat-client/node_modules/jsdom/lib/jsdom/browser/resources/resource-queue.js:53:16)
    at HTMLScriptElementImpl._fetchInternalScript (/home/runner/work/cat-client/cat-client/node_modules/jsdom/lib/jsdom/living/nodes/HTMLScriptElement-impl.js:128:21)
    at HTMLScriptElementImpl._eval (/home/runner/work/cat-client/cat-client/node_modules/jsdom/lib/jsdom/living/nodes/HTMLScriptElement-impl.js:198:12)
    at HTMLScriptElementImpl._poppedOffStackOfOpenElements (/home/runner/work/cat-client/cat-client/node_modules/jsdom/lib/jsdom/living/nodes/HTMLScriptElement-impl.js:149:12)
✗ stats rendered
✗ i18n applied (fa)
✗ kv chip ok
✗ drawer opens
✗ user created + rendered
✗ stat updated
file:///home/runner/work/cat-client/cat-client/scripts/panels/panel-dom.test.mjs:44
document.querySelector('[data-toggle]').click(); await sleep(300);
                                       ^

TypeError: Cannot read properties of null (reading 'click')
    at file:///home/runner/work/cat-client/cat-client/scripts/panels/panel-dom.test.mjs:44:40

Node.js v22.23.3
```
PASS wizard
PASS multipart-upload
