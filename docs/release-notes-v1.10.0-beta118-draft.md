# Cat Client 1.10.0-beta118 — Cloudflare runtime recovery

## Fixed

- Publishes the normal minified ESM Worker as the deploy/release artifact instead of the much larger RC4/control-flow-obfuscated artifact that passed Node mocks but produced Error 1101 in the reported Cloudflare deployment.
- Removes the unverified claim that readable Worker source causes Error 1101. Cloudflare documents 1101 as a runtime exception; source obfuscation is not treated as a remedy.
- Fails closed before KV creation, upload, or route mutation when a Worker name already exists.
- Treats only an actual settings HTTP 404 as a fresh install. HTTP 200 (including empty bindings), auth errors, server errors, malformed settings responses, and network failures cannot proceed.
- Prevents the Wizard from replacing an existing script with a partial binding list that could detach Secret, D1, R2, IOT_DB, or custom bindings.

## Runtime verification

Fresh installs still require three consecutive uncached `/health` successes. A failed fresh deployment does not return a panel URL and its workers.dev route is disabled without deleting account storage.

## Evidence and limits

- Compared with BPB Worker Panel's standard esbuild/Terser ESM artifact and `keep_bindings` update approach; no GPL source was copied.
- Local syntax, module-import, mock health, and Wizard regression tests are required in CI.
- A real Cloudflare-account deployment and physical-device test remain pending. This candidate must not be called device-verified.

- Wizard 1.3.1 prefers the branch-pinned minified snapshot, so a pre-release cannot accidentally fetch the older beta116 artifact through `releases/latest`.
