# Disabled (stale) unit tests

These test files referenced symbols that no longer exist in the production
code (they predate refactors like the VpnState/widget rework). They were
never compiled because CI did not run `:app:testDebugUnitTest` until now.

Kept here (outside `src/test`, so not compiled) for reference. Re-enable
only after rewriting them against the current APIs.
