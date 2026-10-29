// SmarterASP Linux «Node.js App» starts «server.js» by default — this starts the same Cat launcher (index.js).
// index.js starts by itself when cat-config.json sits next to it; otherwise this does it.
import { main, autostarted } from './index.js';

if (!autostarted) {
  main().catch((e) => {
    console.error('[cat] could not start: ' + ((e && e.message) || e));
    console.error('[cat] the host must reach github.com once to fetch the panel (later starts reuse the cached copy).');
    process.exit(1);
  });
}
