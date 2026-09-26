# -*- coding: utf-8 -*-
"""Round 22 (panel 5.13): country chips picker, scanned-IP country in config
names, multi-SNI configs + SNI-aware scan, v6 browser ping fix, best-IP pick.
Escaping rules: generated client code lives in single-quoted worker strings.
Inside them, plain double quotes are literal; a literal single quote must be
written as file-level backslash+quote (BSQ). Template literals avoid quoting
entirely for bigger HTML fragments.
"""
PATH_ = 'app/src/main/assets/panels/catclient.worker.js'
lines = open(PATH_, encoding='utf-8').read().split('\n')

SQ = chr(39)
BSQ = chr(92) + chr(39)      # file-level \' inside a single-quoted worker string
IND = '    '

def line(code):
    return IND + SQ + code + SQ + ','

def rep(old, new, count=1):
    global src
    found = src.count(old)
    assert found == count, 'x%d (want x%d): %r' % (found, count, old[:100])
    src = src.replace(old, new, count)

src = '\n'.join(lines)

# ── server: multi-SNI + location in /api/scan ────────────────────────────
rep("""    const concurrency = Math.max(1, Math.min(32, Number(url.searchParams.get('concurrency') || 16)));
    const results = [];""",
"""    const concurrency = Math.max(1, Math.min(32, Number(url.searchParams.get('concurrency') || 16)));
    const extraSnis = splitCsv(url.searchParams.get('snis')).map((s) => s.trim().toLowerCase())
      .filter((s) => s && validAddress(s) && !isIpLiteral(s) && s !== String(host).toLowerCase())
      .slice(0, 3);
    const results = [];""")

rep("""        results.push(await probeIp(list[index], timeout, host, env));""",
"""        const probe = await probeIp(list[index], timeout, host, env);
        const enriched = Object.assign({}, probe, { location: locationFromColo(probe.colo) });
        if (extraSnis.length && probe.ok) {
          enriched.snisOk = {};
          for (const altSni of extraSnis) {
            const alt = await probeIp(list[index], timeout, altSni, env).catch(() => null);
            enriched.snisOk[altSni] = { ok: !!(alt && alt.ok), ms: alt ? alt.ms : 0 };
          }
        }
        results.push(enriched);""")

# ── server: configOptions parses multiple SNIs ───────────────────────────
rep("""  const sniRaw = String(q.get('sni') || cfg.sni || env.SNI || '').trim().toLowerCase();
  const sni = sniRaw && validAddress(sniRaw) && !isIpLiteral(sniRaw) ? sniRaw : String(host).toLowerCase();""",
"""  const sniRaw = String(q.get('sni') || cfg.sni || env.SNI || '').trim().toLowerCase();
  const sni = sniRaw && validAddress(sniRaw) && !isIpLiteral(sniRaw) ? sniRaw : String(host).toLowerCase();
  const snisExtra = splitCsv(q.get('snis')).concat(Array.isArray(cfg.snis) ? cfg.snis.map(String) : splitCsv(typeof cfg.snis === 'string' ? cfg.snis : ''));
  const snis = [sni].concat(snisExtra.map((v) => v.trim().toLowerCase()))
    .filter((v, i, arr) => v && arr.indexOf(v) === i && validAddress(v) && !isIpLiteral(v))
    .slice(0, 4);""")

rep("""    sni: sni,
    protocols: protocols.length ? protocols : ['vless'],""",
"""    sni: sni,
    snis: snis,
    protocols: protocols.length ? protocols : ['vless'],""")

# ── server: entries rotate through the SNI list ──────────────────────────
rep("""        const name = configName(kind, addr, port, index, host, options);
        const overrides = { port: port, sni: options.sni, fingerprint: options.fingerprint };""",
"""        const snis = (options.snis && options.snis.length) ? options.snis : [options.sni];
        const sni = snis[index % snis.length];
        const name = configName(kind, addr, port, index, host, options);
        const overrides = { port: port, sni: sni, fingerprint: options.fingerprint };""")

# ── configs tab: extra SNI field ─────────────────────────────────────────
rep("""    '<label class="field"><span>SNI (خالی = دامنهٔ ورکر)</span><input id="cfgSni" dir="ltr" value="' + esc(o.sni === state.host ? '' : o.sni) + '" placeholder="' + esc(state.host) + '"></label>' +""",
"""    '<label class="field"><span>SNI (خالی = دامنهٔ ورکر)</span><input id="cfgSni" dir="ltr" value="' + esc(o.sni === state.host ? '' : o.sni) + '" placeholder="' + esc(state.host) + '"></label>' +
    '<label class="field"><span>SNIهای بیشتر (با کاما — بین کانفیگ‌ها می‌چرخند و دسترسی را بهتر می‌کنند)</span><input id="cfgSnis" dir="ltr" placeholder="speed.cloudflare.com,cdn.jsdelivr.net"></label>' +""")

# ── client readOptions / subQuery / cfgSave: snis ────────────────────────
rep("""    ' var sni=($("#cfgSni").value||"").trim().toLowerCase()||S.host;',""",
"""    ' var sni=($("#cfgSni").value||"").trim().toLowerCase()||S.host;',
    ' var snis=($("#cfgSnis").value||"").split(/[;, ]+/).map(function(s){return s.trim().toLowerCase()}).filter(function(s){return s&&s.indexOf(".")>0&&s.indexOf(":")<0}).slice(0,4);',""")
rep("""sni:sni,fingerprint:fp,""", """sni:sni,snis:snis,fingerprint:fp,""")
rep("""q.push("count="+(o.entryLimit||8));""",
    """if(o.snis&&o.snis.length>1)q.push("snis="+encodeURIComponent(o.snis.join(",")));q.push("count="+(o.entryLimit||8));""")
rep("""sni:o.sni===S.host?"":o.sni,""", """sni:o.sni===S.host?"":o.sni,snis:(o.snis||[]).join(","),""")

# ── users tab: country chips + picker modal ──────────────────────────────
rep("""    '<label class="field" style="grid-column:1/-1"><span>کشورهای کاربر (با کاما) — تا کشوری انتخاب نکنی کانفیگی نمی‌گیرد؛ مثلاً NL,DE,FR 🇳🇱🇩🇪🇫🇷</span><input id="uCountries" dir="ltr" placeholder="NL,DE,FR"></label>' +""",
"""    '<label class="field" style="grid-column:1/-1"><span>کشورهای کاربر — با یک کلیک انتخاب کن (تا کشوری نگذاری کانفیگی نمی‌گیرد)</span><div class="chips" id="uCountryChips"></div><input id="uCountries" dir="ltr" placeholder="یا اینجا اضافه کن: NL,DE,FR" style="margin-top:8px"></label>' +""")

BT = chr(96)
hits = [i for i, l in enumerate(lines) if 'if($("#uCreate"))' in l]
assert len(hits) == 1, ('uCreate', hits)
chip_lines = [
    line('var CHIP_CODES=[].concat((S.countryPools||[]).filter(function(p){return p.code}).map(function(p){return p.code}),["NL","DE","FR","US","GB","TR","SE","JP","SG","AE"]).filter(function(v,i,a){return a.indexOf(v)===i}).slice(0,16);'),
    line('var ucBox=$("#uCountryChips");if(ucBox){ucBox.innerHTML=CHIP_CODES.map(function(cc){return ' + BT + '<button type=' + BSQ + 'button' + BSQ + ' class=' + BSQ + 'chip' + BSQ + ' data-ucc=' + BSQ + '>' + BT + '+cc+' + BT + '>' + BT + '+(flagOf(cc)||"")+" "+cc+"</button>"}).join("");}'),
    line('if(ucBox)ucBox.addEventListener("click",function(ev){var b=ev.target.closest("[data-ucc]");if(!b)return;b.classList.toggle("active");var codes=$$("#uCountryChips .chip.active").map(function(x){return x.getAttribute("data-ucc")});var extra=($("#uCountries").value||"").split(/[;, ]+/).map(function(s){return s.trim().toUpperCase()}).filter(function(s){return s&&CHIP_CODES.indexOf(s)<0});$("#uCountries").value=codes.concat(extra).join(",");});'),
    line('function pickCountries(current){var codes=CHIP_CODES.slice();(current||[]).forEach(function(c){if(codes.indexOf(String(c).toUpperCase())<0)codes.push(String(c).toUpperCase())});'),
    line(' return new Promise(function(resolve){var ov=document.createElement("div");ov.style.cssText="position:fixed;inset:0;background:rgba(0,0,0,.6);z-index:99;display:flex;align-items:center;justify-content:center;padding:18px";'),
    line('  var box=document.createElement("div");box.className="card glow";box.style.cssText="max-width:430px;width:100%";'),
    line('  box.innerHTML=' + BT + '<h2 style=' + BSQ + 'margin-bottom:10px' + BSQ + '>🌍 کشورهای این کاربر</h2><div class=' + BSQ + 'chips' + BSQ + ' id=' + BSQ + 'pkChips' + BSQ + '></div><div class=' + BSQ + 'row' + BSQ + ' style=' + BSQ + 'margin-top:12px' + BSQ + '><button class=' + BSQ + 'btn' + BSQ + ' id=' + BSQ + 'pkSave' + BSQ + '>ذخیره</button><button class=' + BSQ + 'btn ghost' + BSQ + ' id=' + BSQ + 'pkCancel' + BSQ + '>انصراف</button></div>' + BT + ';'),
    line('  ov.appendChild(box);document.body.appendChild(ov);var chosen=(current||[]).map(function(c){return String(c).toUpperCase()});var chipsBox=box.querySelector("#pkChips");'),
    line('  function paint(){chipsBox.innerHTML=codes.map(function(cc){return ' + BT + '<button type=' + BSQ + 'button' + BSQ + ' class=' + BSQ + 'chip' + BSQ + '+(chosen.indexOf(cc)>=0?" active":"")+' + BSQ + ' data-pk=' + BSQ + '>' + BT + '+cc+' + BT + '>' + BT + '+(flagOf(cc)||"")+" "+cc+"</button>"}).join("");}'),
    line('  paint();chipsBox.addEventListener("click",function(ev){var b=ev.target.closest("[data-pk]");if(!b)return;var cc=b.getAttribute("data-pk");var i=chosen.indexOf(cc);if(i>=0)chosen.splice(i,1);else chosen.push(cc);paint();});'),
    line('  box.querySelector("#pkSave").onclick=function(){document.body.removeChild(ov);resolve(chosen.join(","))};'),
    line('  box.querySelector("#pkCancel").onclick=function(){document.body.removeChild(ov);resolve(null)};'),
    line(' });}'),
]
lines = src.split('\n')
hits = [i for i, l in enumerate(lines) if 'if($("#uCreate"))' in l]
assert len(hits) == 1, ('uCreate2', hits)
lines[hits[0]:hits[0]] = chip_lines

# edit flow: countries PROMPT -> chips modal (line surgery, no long anchors)
src = '\n'.join(lines)
lines = src.split('\n')
i_cc = [i for i, l in enumerate(lines) if 'var cc=prompt' in l]
assert len(i_cc) == 1, ('cc', i_cc)
j = i_cc[0]
assert 'var body={name:name' in lines[j + 1] and 'userPut(id,body).then' in lines[j + 2], (lines[j + 1][:80], lines[j + 2][:80])
lines[j:j + 3] = [
    line('  pickCountries(u.countries||[]).then(function(cc){if(cc===null)return;'),
    line('   var body={name:name,quotaGb:Number(q)||0,deviceLimit:Number(dev)||0,countries:cc};if(d.trim()!=="")body.days=Number(d)||0;'),
    line('   userPut(id,body).then(function(j2){toast(j2.ok?"ذخیره شد":(j2.error||"خطا"));loadUsers();});});return;}'),
]
src = '\n'.join(lines)

lines = src.split('\n')

# ── scanner: extra SNI field + snis on server scans ──────────────────────
rep("""    '<label class="field" style="margin-top:10px"><span>SNI دامنهٔ پنل یا هاست پیشنهادی</span><input id="scanSni" dir="ltr" value="' + esc(String((state && state.sni) || (state && state.host) || '')) + '" placeholder="mypanel.workers.dev"></label>' +""",
"""    '<label class="field" style="margin-top:10px"><span>SNI دامنهٔ پنل یا هاست پیشنهادی</span><input id="scanSni" dir="ltr" value="' + esc(String((state && state.sni) || (state && state.host) || '')) + '" placeholder="mypanel.workers.dev"></label>' +
    '<label class="field" style="margin-top:10px"><span>SNIهای اضافی برای تست (با کاما — هر کدام جداگانه روی هر آی‌پی از ورکر تست می‌شود)</span><input id="scanSnis" dir="ltr" placeholder="speed.cloudflare.com,www.speedtest.net"></label>' +""")

hits = [i for i, l in enumerate(lines) if 'function pingAddr' in l]
assert len(hits) == 1, ('pingAddr', hits)
lines.insert(hits[0], line('function snisQ(){var v=($("#scanSnis")||{}).value||"";v=v.trim();return v?"&snis="+encodeURIComponent(v):""}'))

rep("""/api/scan?ips="+encodeURIComponent(ips.join(","))+"&timeout=4000&concurrency=12")""",
    """/api/scan?ips="+encodeURIComponent(ips.join(","))+"&timeout=4000&concurrency=12"+snisQ())""")
rep("""/api/scan?ips="+encodeURIComponent(targets.join(","))+"&timeout=4000&concurrency=16&save=1")""",
    """/api/scan?ips="+encodeURIComponent(targets.join(","))+"&timeout=4000&concurrency=16&save=1"+snisQ())""")

# v6 bracket fix in browser ping
rep("""+"://"+addr+":"+port+"/cdn-cgi/trace?""",
    """+"://"+(addr.indexOf(":")>=0?"["+addr+"]":addr)+":"+port+"/cdn-cgi/trace?""")

# best-IP picker
rep("""id="scanClear">پاک کردن</button>""",
    """id="scanClear">پاک کردن</button><button class="btn ghost tiny" id="scanPickBest">⭐ انتخاب بهترین‌ها</button>""")
hits = [i for i, l in enumerate(lines) if 'scanClear").addEventListener' in l]
assert len(hits) == 1, ('scanClear', hits)
lines.insert(hits[0] + 1, line('$("#scanPickBest").addEventListener("click",function(){var alive=scanResults.filter(function(r){return (r.ms!==null)||(r.server&&r.server.ok)});'))
lines.insert(hits[0] + 2, line(' alive.sort(function(a,b){var x=a.ms!==null?a.ms:((a.server&&a.server.ms)||99999),y=b.ms!==null?b.ms:((b.server&&b.server.ms)||99999);return x-y});scanResults.forEach(function(r){r.selected=false});alive.slice(0,8).forEach(function(r){r.selected=true});renderScan();updateSelCount();toast(alive.length?(Math.min(8,alive.length)+" تندترین آی‌پی انتخاب شد"):"آی‌پی زنده‌ای نیست");});'))

# auto server-verify for selected IPs without country (names get the real country)
hits = [i for i, l in enumerate(lines) if 'applyOptions();showTab("configs");toast(ips.length' in l]
assert len(hits) == 1, ('useIps', hits)
lines.insert(hits[0], line(' if(ips.filter(function(ip){var h=scanResults.filter(function(r){return r.ip===ip})[0];return !(h&&h.server&&h.server.ok&&h.server.colo)}).length)verifyOnServer(ips);'))

src = '\n'.join(lines)

# ── renderScan: per-IP SNI results row (template literal) ────────────────
old_rs = """' var msText=r.ms===null?"✗":(r.ms+" ms");var loc=r.server&&r.server.location?(r.server.location.flag+" "+r.server.location.city+", "+r.server.location.country):(r.server&&r.server.colo?r.server.colo:"🌐 Auto");var srv=r.server===undefined?"—":(r.server&&r.server.ok?("✓ "+(r.server.ms||"")+"ms"):"✗");',"""
new_rs = ("""' var msText=r.ms===null?"✗":(r.ms+" ms");var loc=r.server&&r.server.location?(r.server.location.flag+" "+r.server.location.city+", "+r.server.location.country):(r.server&&r.server.colo?r.server.colo:"🌐 Auto");var srv=r.server===undefined?"—":(r.server&&r.server.ok?("✓ "+(r.server.ms||"")+"ms"):"✗");',\n"""
    + line(' var sniRow=(r.server&&r.server.snisOk)?Object.keys(r.server.snisOk).filter(function(s){return r.server.snisOk[s].ok}).map(function(s){return "✓ "+s}).join("<br>"):"";').rstrip(',')
    + ',\n'
    + line(' return ' + BT + '<tr><td><input type="checkbox" style="width:auto" data-ip-check="' + BT + '+r.ip+' + BT + '"${r.selected?" checked":""}></td><td dir="ltr"><b>' + BT + '+r.ip+' + BT + '</b><br><small>' + BT + '+loc+' + BT + '</small>${sniRow?\'<small>\'+' + BT + '+sniRow+' + BT + '+\'</small>\':""}</td><td class="ms ${cls}">${msText}</td><td class="ms ${r.server&&r.server.ok?"good":(r.server===undefined?"":"bad")}">${srv}</td><td><button class="btn ghost tiny" data-copy-ip="' + BT + '+r.ip+' + BT + '">کپی</button> <button class="btn tiny" data-use-ip="' + BT + '+r.ip+' + BT + '">انتخاب</button></td></tr>' + BT + ';}).join("");'))
found = src.count(old_rs)
assert found == 1, ('renderScan', found)
src = src.replace(old_rs, new_rs, 1)

open(PATH_, 'w', encoding='utf-8').write(src)
print('OK r22 worker 5.13')
