# How to properly start Cat Client development

## Option A (Strongly Recommended)

1. Fork https://github.com/ArasTey/ArasClient
2. Clone your fork
3. Change:
   - `applicationId`
   - App name strings
   - Package name
   - Icons / splash
   - Colors (black, white, purple)
4. Build and test
5. Push to this repository or keep developing on the fork

## Option B

Fork WhiteVPN:
https://github.com/WhiteDNS/WhiteVPN

Same process.

## Why not build from zero?

A working VPN client needs:
- Native Xray / Mihomo / sing-box core
- Proper VpnService implementation
- TUN handling
- Battery & doze optimizations
- Protocol parsers
- Subscription parsing
- Routing rules
- Extensive device testing

Starting from a mature project saves months of work and avoids the common "it looks nice but doesn't connect" problem.

---

After you have a working base, we can add the Cat Client specific features step by step.
