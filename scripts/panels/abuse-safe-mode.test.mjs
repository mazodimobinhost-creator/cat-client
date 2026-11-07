import { _testing as T } from '../../app/src/main/assets/panels/catclient.worker.js';
let failed = 0;
function check(name, ok) { console.log(`${ok ? 'ok' : 'not ok'} - ${name}`); if (!ok) failed++; }
check('fresh deployment disables data plane', T.dataPlaneEnabled({}) === false);
check('unrelated bindings do not enable it', T.dataPlaneEnabled({ UUID: 'secret', CAT_KV: {} }) === false);
check('explicit true enables it', T.dataPlaneEnabled({ DATA_PLANE_ENABLED: 'true' }) === true);
check('explicit one enables it', T.dataPlaneEnabled({ DATA_PLANE_ENABLED: '1' }) === true);
check('false remains disabled', T.dataPlaneEnabled({ DATA_PLANE_ENABLED: 'false' }) === false);
if (failed) process.exit(1);
