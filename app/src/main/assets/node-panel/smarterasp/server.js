// SmarterASP Linux «Node.js App» starts «server.js» by default — this starts the same Cat launcher (index.js).
import { main } from './index.js';

main().catch((e) => {
  console.error('[cat] could not start: ' + ((e && e.message) || e));
  console.error('[cat] the host must reach github.com once to fetch the panel (later starts reuse the cached copy).');
  process.exit(1);
});
