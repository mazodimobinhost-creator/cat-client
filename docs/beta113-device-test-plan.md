# beta113 physical-device sign-off

Do not set the repository variable `BETA113_DEVICE_SIGNOFF=true` until every blocking row passes. Record UNKNOWN rather than guessing. Attach screenshots without tokens, UUIDs, subscription URLs, or secrets.

## Procedure
1. Open Smart Connection; note network and operator display.
2. Tap **Test all routes**. Do not change network until the stable-network rows finish.
3. Confirm Worker success only when an actual Worker profile HTTP delay test succeeds.
4. Repeat with a deliberately disabled/broken Worker; it must be FAILED, never healthy from panel status.
5. Open a captive portal and repeat; the result must be INCONCLUSIVE and must not replace prior history.
6. Start a probe and switch Wi-Fi/mobile mid-probe; it must be INCONCLUSIVE and not persist.
7. In Orbot settings mark dnstt as configured only when Orbot is actually configured to use dnstt. Test with Orbot on/off.
8. Mark dnstt not configured and confirm NOT_CONFIGURED.
9. Close the page/app and observe that no Smart Route probe starts in background. Reopen and manually retest after at least 30 seconds.

## Blocking criteria
- Broken/disabled Worker must report FAILED.
- Captive Portal must report INCONCLUSIVE, not FAILED.
- Network-changing probes must not enter history.
- dnstt without transport evidence must remain UNKNOWN.

## Results
| Network | Route | Status | Latency | Date/time | Tester | Screenshot |
|---|---|---|---:|---|---|---|
| Wi-Fi | Direct |  |  |  |  |  |
| Wi-Fi | Worker |  |  |  |  |  |
| Wi-Fi | Profiles |  |  |  |  |  |
| Mobile (carrier: ___) | Direct |  |  |  |  |  |
| Mobile (carrier: ___) | Worker |  |  |  |  |  |
| Mobile (carrier: ___) | Profiles |  |  |  |  |  |
| Captive Portal | Direct/Worker |  |  |  |  |  |
| Wi-Fi without internet | Direct/Worker |  |  |  |  |  |
| Network changed mid-probe | Tested route |  |  |  |  |  |
| Orbot on + dnstt evidence | dnstt |  |  |  |  |  |
| Orbot off + dnstt evidence | dnstt |  |  |  |  |  |
| dnstt not configured | dnstt |  |  |  |  |  |
| Broken/disabled Worker | Worker |  |  |  |  |  |
| Page/app closed | Background probe check |  |  |  |  |  |
