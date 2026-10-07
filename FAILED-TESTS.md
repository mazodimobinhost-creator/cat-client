PASS cat-panel
FAIL panel-dom
```
Could not parse CSS stylesheet
Not implemented: Window's scrollTo() method
(node:2248) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✓ no JS errors on load
✗ stats rendered
✓ i18n applied (fa)
✗ kv chip ok
✓ drawer opens
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
