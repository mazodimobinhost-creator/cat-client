// SmarterASP Linux «Node.js App» starts «server.js» by default — this starts the same Cat launcher (index.js).
// index.js starts by itself when cat-config.json sits next to it; otherwise this does it. (A namespace import, so
// an older index.js left over from a previous upload still loads instead of failing on a missing export.)
import * as cat from './index.js';

if (!cat.autostarted) {
  cat.main().catch((e) => {
    console.error('[cat] could not start: ' + ((e && e.message) || e));
    if (e && e.stack) console.error(String(e.stack).split('\n').slice(1, 6).join('\n'));
    process.exit(1);
  });
}
