#!/usr/bin/env python3
"""ONE-TIME refactor (beta220, already applied): move the large screens/dialogs out of MainActivity.kt.

Each moved function becomes `internal fun MainActivity.name(...)` in a feature file next to MainActivity.kt; the
code itself is byte-for-byte the same except:
  * `this@MainActivity` -> `this@name` (the extension receiver label),
  * unqualified callable references `::member` -> `this::member`,
  * the private MainActivity members / nested types / companion constants the moved code uses become `internal`
    (and are imported where Kotlin needs it: companion constants and nested types are not in an extension's scope).
Nothing else changes: same behaviour, same UI. Kept for review/history; rerunning it is a no-op once applied.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from kotlin_scan import code_mask, function_span, match_brace  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / 'app/src/main/java/com/cat/client/MainActivity.kt'

GROUPS: dict[str, tuple[str, list[str]]] = {
    'MainActivitySubscriptions.kt': ('Servers tab: subscription list, cards, add/edit dialog, free configs, optimizer.', [
        'buildSubscriptionsScreen', 'buildFreeConfigsSection', 'renderSubscriptions', 'subscriptionCard',
        'showSubscriptionDialog', 'optimizeUserSubscription']),
    'MainActivityDashboard.kt': ('Home tab: dashboard, traffic meter row, world map page.', [
        'buildFlowMeterRow', 'buildDashboard', 'showMapPage']),
    'MainActivityAdvanced.kt': ('Settings tab: advanced screen, appearance block, chain settings, split tunnel.', [
        'buildInterfaceDesignBlock', 'buildAdvancedScreen', 'buildConnectionChainSettings', 'advancedToggleRow',
        'showSplitTunnelSettings']),
    'MainActivityScanner.kt': ('IP scanner tab and scanner dialogs (whitelist, SNI, UDP/TCP, inject into subscription).', [
        'buildScannerScreen', 'renderScannerSources', 'startIpScanner', 'renderScannerIpHealth', 'runScannerAiTest',
        'sendScanToPanelSuspend', 'buildSniScannerCard', 'showWhitelistSuperScannerDialog',
        'runCottenMaxDualUdpTcpScannerDialog', 'injectScannedIpsIntoActiveSubscription']),
    'MainActivityCloud.kt': ('Cloud tab: panel deployment, history, secrets, installers.', [
        'addDeploymentProfilesSection', 'buildCloudScreen', 'renderCloudDeploymentHistory', 'showPanelSecretsDialog',
        'showOptInGeminiWorkerReviewDialog', 'showTechNamoozInstallDialog']),
    'MainActivityCatWarp.kt': ('Cat Warp tab: add/edit Cat Warp configs, WARP keys, exit picker, auto scan & build.', [
        'buildAetherScreen', 'showAetherExitLocationPickerDialog', 'showGetNewWarpKeysDialog',
        'runOneTapPattNgMimPsiphonSetup', 'showAetherConfigEditor', 'showWarpKeysStatusDialog', 'showAddAetherDialog',
        'showAetherAutoScanAndBuildWizard']),
    'MainActivityEngines.kt': ('Emergency & anti-censorship tab: engine cards and their dialogs (DNS, paqet, Outline, WG, CF).', [
        'buildAntiCensorshipEnginesCard', 'showWhiteDnsAndAetherToolsDialog', 'showIphoneIosConnectionHubDialog',
        'showWhiteDnsResolverMethodsDialog', 'showMasterDnsAndDnstGeneratorDialog', 'showPaqetPatthEngineDialog',
        'showSmartConfigDoctorAndFluxDialog', 'showOutlineFreeHubDialog', 'showAmneziaAndProtonWgHubDialog',
        'runCloudflareZeroByteAndIpv6HealerDialog', 'showFourPowerFeaturesCenterDialog',
        'showUniversalOmniConfigHubDialog', 'showIspProtocolMatrixDialog', 'showLiveWhitelistTlsClonerDialog',
        'showServerlessV52MethodDialog', 'runFetchSevenGitHubFreeConfigSourcesDialog',
        'showEasyHotspotShareWithQrDialog', 'runOneClickCleanAndSortConfigsDialog',
        'showCloudflareFiveSpoofMethodsDialog', 'runIranDnsAndDnsttScanDialog',
        'runOneClickRepairMyRealConfigAndConnect']),
    'MainActivityGuides.kt': ('Plain-language guides, recommended one-click wizard, feature finder.', [
        'buildPlainLanguageQuickGuideCard', 'showRecommendedOneClickConnectWizardDialog', 'finderEntries',
        'showFeatureFinderDialog', 'showPlainLanguageFullGuideDialog']),
    'MainActivityFox.kt': ('Cat Fox VPN: card, account manager, sign-in dialogs, hub, direct connect.', [
        'buildFirefoxVpnFenixCard', 'showFirefoxAccountManagerDialog', 'showFirefoxWebViewLoginDialog',
        'showFirefoxEmailLoginDialog', 'showFirefoxVpnFenixHubDialog', 'connectFirefoxVpnDirectly']),
    'MainActivityConnectionTesting.kt': ('Connection testing page (ping/speed lists, chain picker).', [
        'showConnectionTestingPage']),
}

FUN_RE = re.compile(r'(?m)^    (?:@\S+\s+)*(?:(?:private|override|internal|protected|public|suspend|inline|operator|tailrec)\s+)*fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)\s*\(')
DECL_RE = re.compile(r'(?m)^    ((?:@\w+(?:\([^)\n]*\))?\s+)*)private\s+((?:(?:inline|suspend|lateinit|const|data|enum|inner|abstract|open|tailrec|operator|infix|sealed|value)\s+)*)(fun|val|var|class|object|interface)\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)')


def main() -> None:
    src = MAIN.read_text()
    if 'internal fun MainActivity.' in src or not re.search(r'(?m)^    private fun buildAdvancedScreen\(', src):
        print('already applied'); return
    mask = code_mask(src)
    cls = src.index('class MainActivity : Activity()')
    body_open = src.index('{', cls)
    body_close = match_brace(src, mask, body_open)

    # member functions of MainActivity with spans (incl. leading KDoc/comments/annotations)
    funs: dict[str, list[tuple[int, int]]] = {}
    for m in FUN_RE.finditer(src, body_open, body_close):
        if not mask[m.start() + 4]:
            continue
        s, e = function_span(src, mask, m.start())
        # pull preceding comment/annotation lines
        while True:
            prev_end = s - 1
            prev_start = src.rfind('\n', 0, prev_end) + 1
            line = src[prev_start:prev_end]
            t = line.strip()
            if line.startswith('    ') and (t.startswith('//') or t.startswith('/*') or t.startswith('*') or t.startswith('@')):
                s = prev_start
            else:
                break
        funs.setdefault(m.group(1), []).append((s, e))

    moved_names = {n for _, (_, names) in GROUPS.items() for n in names}
    missing = moved_names - funs.keys()
    if missing:
        raise SystemExit('not found: %s' % sorted(missing))

    # private members and nested types of MainActivity (4-space level)
    priv: dict[str, list[int]] = {}
    for m in DECL_RE.finditer(src, body_open, body_close):
        if mask[m.start() + 4]:
            priv.setdefault(m.group(4), []).append(m.start())
    comp_idx = src.index('    private companion object {', body_open)
    comp_close = match_brace(src, mask, src.index('{', comp_idx))
    companion_names = set(re.findall(r'(?m)^        (?:const\s+)?(?:val|var|fun)\s+(\w+)', src[comp_idx:comp_close]))
    nested_types = set(re.findall(r'(?m)^    (?:private\s+)?(?:data\s+|enum\s+|inner\s+)?(?:class|object|interface)\s+(\w+)', src[body_open:body_close]))
    all_members = set(funs) | set(priv) | set(re.findall(r'(?m)^    (?:\S+\s+)*?(?:val|var)\s+(\w+)', src[body_open:body_close]))

    spans = []
    files: dict[str, list[str]] = {}
    for fname, (_, names) in GROUPS.items():
        parts = []
        for name in names:
            for s, e in funs[name]:
                spans.append((s, e))
                text = src[s:e]
                parts.append(transform(text, name, all_members))
        files[fname] = parts

    moved_text = '\n'.join(p for parts in files.values() for p in parts)
    tokens = set(re.findall(r'\b\w+\b', moved_text))

    # remove moved spans from MainActivity (back to front)
    out = src
    for s, e in sorted(spans, reverse=True):
        # also drop one blank separator line that followed the function so no double gap is left behind
        if out[e:e + 1] == '\n' and out[s - 2:s] == '\n\n':
            e += 1
        out = out[:s] + out[e:]

    # widen what the moved code uses
    widened = sorted(n for n in priv if n in tokens and n not in moved_names)

    def widen(text: str) -> str:
        def rep(m: re.Match) -> str:
            name = m.group(4)
            if name in widened:
                g = m.group(0)
                k = g.index('private', 4 + len(m.group(1)))
                return g[:k] + 'internal' + g[k + len('private'):]
            return m.group(0)
        return DECL_RE.sub(rep, text)
    out = widen(out)
    used_companion = sorted(n for n in companion_names if n in tokens)
    if used_companion:
        out = out.replace('    private companion object {', '    internal companion object {', 1)
    used_nested = sorted(n for n in nested_types if n in tokens)

    # remaining class code: unqualified references to moved functions
    for name in moved_names:
        out = re.sub(r'(?<![\w.)\]>?])::%s\b' % name, 'this::%s' % name, out)

    imports = '\n'.join(re.findall(r'(?m)^import .*$', src))
    for fname, parts in files.items():
        text = '\n'.join(parts)
        local_tokens = set(re.findall(r'\b\w+\b', text))
        extra = ['import com.cat.client.MainActivity.Companion.%s' % n for n in used_companion if n in local_tokens]
        extra += ['import com.cat.client.MainActivity.%s' % n for n in used_nested if n in local_tokens]
        header = ('package com.cat.client\n\n' + imports + ('\n' + '\n'.join(extra) if extra else '') + '\n\n'
                  '// %s\n// Moved out of MainActivity.kt unchanged (beta220 code split); see docs/CODEMAP.md.\n\n'
                  % GROUPS[fname][0])
        (MAIN.parent / fname).write_text(header + text.rstrip('\n') + '\n')
    MAIN.write_text(out)
    print('moved %d functions into %d files; widened %d members; companion=%d nested=%d' % (
        len(spans), len(files), len(widened), len(used_companion), len(used_nested)))
    print('MainActivity lines: %d -> %d' % (src.count('\n'), out.count('\n')))


def transform(text: str, name: str, members: set[str]) -> str:
    sig = re.compile(r'(?m)^(    (?:@\S+\s+)*)private\s+((?:suspend\s+|inline\s+)*)fun\s+(<[^>]*>\s*)?%s\s*\(' % name)
    m = sig.search(text)
    if not m:
        raise SystemExit('signature not private for %s' % name)
    gen = (m.group(3) or '').strip()
    new_sig = '%sinternal %sfun %sMainActivity.%s(' % (m.group(1), m.group(2), (gen + ' ') if gen else '', name)
    text = text[:m.start()] + new_sig + text[m.end():]
    text = text.replace('this@MainActivity', 'this@%s' % name)
    text = re.sub(r'(?<![\w.)\]>?])::(\w+)\b', lambda mm: ('this::' + mm.group(1)) if mm.group(1) in members else mm.group(0), text)
    if '"""' not in text:
        text = '\n'.join(l[4:] if l.startswith('    ') else l for l in text.split('\n'))
    return text


if __name__ == '__main__':
    main()
