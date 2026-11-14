# Jasaz review

Reviewed 2026-10-18 against `itsyebekhe/jasaz` main commit `7b6376ec` and the public video `S9Bv509Dylk`. Architecture and safety review only; no Jasaz source is imported into Cat.

## What the video actually demonstrates

The walkthrough enters a Cloudflare account ID and API token into `jasaz.pages.dev`, selects a released Worker script, disables Jasaz obfuscation, creates/binds KV and deploys. If 1101 returns, it recommends **Clean Rebuild**, which deletes and recreates the Worker. The resulting panel and subscription work in that demonstration. This is not evidence that obfuscation fixes 1101 or that the deployment will avoid restrictions over time.

The likely repair is mundane: Jasaz uploads the script with module metadata, creates the expected KV/D1 binding and enables the workers.dev subdomain. A panel that previously had the wrong upload format or a missing binding can then start. Deleting/recreating also discards the broken version, but hides the root cause.

## Implementation observed

- Pages frontend calls a Pages Function deployment endpoint.
- The function receives the user's Cloudflare API token and account ID.
- It downloads a caller-supplied Worker URL.
- It optionally runs a custom XOR/Base64 string-rewriting obfuscator.
- It discovers or creates D1/KV resources and uploads multipart Worker metadata.
- Module detection is a string search for `export default` or `export `.
- Compatibility date is the current deployment date.
- Clean rebuild deletes the old Worker first.
- `/api/raw?url=` fetches an arbitrary URL, buffers the full response and returns it with wildcard CORS.

## Risks and non-adoptions

1. **Credential transit:** encoding a token in browser storage is not encryption. The public Pages Function receives the plaintext Cloudflare token and can use every granted permission. Cat will not ask users to send account tokens through a shared public service.
2. **Destructive recovery:** Clean Rebuild can lose script versions, secrets, routes, bindings, settings and diagnostics. Cat's standing rule is preserve KV, D1, secrets and history; it will use canary/version rollback instead.
3. **Binding replacement:** the upload metadata contains only bindings selected in this wizard. Updating an existing Worker can detach omitted secrets or custom bindings.
4. **Naive module detection:** searching source text can misclassify comments, strings or mixed syntax.
5. **Moving compatibility date:** using today's date on each deploy changes runtime semantics without review and makes reproducing a known-good build harder.
6. **Obfuscator correctness:** the hand-written lexer does not provide a complete JavaScript parser. Rewriting string literals can change computed keys or syntax. It also adds runtime decode/cache CPU and memory. The video itself instructs users to disable it.
7. **Open fetch proxy/SSRF surface:** `/api/raw` accepts arbitrary URLs, has no allowlist, timeout or response-size limit, buffers the whole body and enables wildcard CORS.
8. **Unbounded request/response bodies:** deploy accepts `request.json()` directly and source/API responses are buffered without explicit ceilings.
9. **No proven 1101 diagnosis:** delete/redeploy cannot distinguish uncaught exception, missing Response, wrong module metadata, missing binding, CPU, memory or transient platform failure.
10. **GPL-3.0:** direct code copying or integration would require GPL-compatible distribution and complete corresponding source.

## Useful independent lessons for Cat

- Validate module syntax and expected export before upload.
- Pin one reviewed compatibility date across every deploy path.
- Upload explicit multipart module metadata.
- Probe required bindings before route activation.
- Preserve all existing bindings on update or refuse the update.
- Deploy a new version, execute exact-version health checks, then promote; do not delete first.
- Keep source canonical and generate minified artifacts reproducibly.
- Classify Cloudflare outcomes separately: exception/1101, CPU/1102, memory, binding, health mismatch and restriction.

## Conclusion

Jasaz can repair a specific broken installation by re-uploading the script with bindings and module metadata. Its obfuscation does not make a Worker invisible to Cloudflare and is not the demonstrated fix. Its destructive rebuild and shared-token model are not suitable for Cat. Cat already implements the reusable parts more safely through canonical builds, binding-preserving updates, health verification and bounded runtime work.
