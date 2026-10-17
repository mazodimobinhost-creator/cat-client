# Aether Android integration notice

Cat Client's optional Aether runtime is built from the pinned WhiteAestherMobile source listed in `upstream.lock`.
It is not renamed or presented as a Cat Client implementation.

- WhiteAestherMobile: Copyright its contributors, AGPL-3.0-only
- Aether: Copyright CluvexStudio and contributors, AGPL-3.0
- quiche: Copyright Cloudflare, BSD-2-Clause

The native artifact is reproducibly built by `scripts/build-aether-core.sh`. Corresponding source is the exact Git commit in `upstream.lock`; lockfile and license checksums are verified before compilation. Distributions containing the native artifact must include this notice, the applicable license texts, and an offer/link to the complete corresponding source.
