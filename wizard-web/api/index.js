/**
 * Cat Wizard on Vercel — same installer as catclient.wizard.js (token in,
 * Cat Panel on the visitor's Cloudflare out). Hosted here so nobody has to
 * deploy the wizard Worker first.
 */
import wizard, { _testing as T } from '../lib/catclient.wizard.js';

T.__setSleep({ early: 600, late: 900 });

export const config = { runtime: 'edge', maxDuration: 60 };

export default async function handler(request) {
  const env = {
    WIZARD_TITLE: 'Cat Wizard',
    DEFAULT_WORKER: 'catpanel',
  };
  const incoming = new URL(request.url);
  const mapped = new URL(request.url);
  const route = incoming.searchParams.get('__wpath');
  if (route) {
    mapped.pathname = route;
    mapped.searchParams.delete('__wpath');
  } else if (incoming.pathname === '/api' || incoming.pathname === '/api/') {
    mapped.pathname = '/';
  }
  return wizard.fetch(new Request(mapped.toString(), request), env, {
    waitUntil: (p) => { void p; },
  });
}
