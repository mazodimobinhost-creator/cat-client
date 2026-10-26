# Android failure report (c2509af)


## files with compile errors
## log tail
### step18.log
✓ the app scanner line pasted RAW into the box («#SOCKS5 ip» remark): … which is asked to CONNECT to the real target
✓ the app scanner line pasted RAW into the box («#SOCKS5 ip» remark): … and the client payload reaches the far side through it
✓ a t.me/socks share: the sub has a 🧦 config for it
✓ a t.me/socks share: the 🧦 name carries no relay host:port
✓ a t.me/socks share: the worker exits through that SOCKS5 server (first dial)
✓ a t.me/socks share: … which is asked to CONNECT to the real target
✓ a t.me/socks share: … and the client payload reaches the far side through it

PROXYIP DATA-PLANE TESTS PASSED
✓ syntax check passed
✓ subQuery keeps only in-range ports (70000/0/-5/abc/99999 dropped)
✓ subQuery accepts 65535, rejects 65536
✓ ?port=70000: sub is NOT empty (falls back to the panel port walk)
✓ ?port=70000: every emitted port is dialable
✓ ?port=99999: sing-box carries no out-of-range port
✓ ?port=99999: clash carries no out-of-range port
✓ ?port=99999: xray carries no out-of-range port
✓ ?addr=ip#DE: sub is not empty
✓ ?addr=ip#DE: every server is the BARE ip
✓ ?addr=ip#DE: params survive the «#» (path+security present)
✓ ?addr=ip#DE: the tag shows up as the entry country (flag in the name)
✓ ?addr=ip#DE: clash server is the bare ip (no # in the YAML)
✓ ?addr=ip#DE: sing-box server is the bare ip
✓ ?addr=ip#DE + strict country DE: the pinned address survives
✓ ?addr=ip:99999: no «[1.2.3.4:99999]» garbage
✓ ?addr=ip:99999: falls back to the bare host on the panel ports
✓ ?addr=ip:2053: every config keeps the verified port 2053
✓ ?addr=[v6]:8443: the pin survives, bracketed once
✓ ?addr=bare v6: survives untouched (not mistaken for host:port)
✓ ?addr=mix of junk + one ip: only the real ip is served
✓ ?addr=all junk: the panel address list takes over (never an empty sub)
ADDR/PORT QUERY TESTS PASSED
(node:3486) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js?panel=aq-unit is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✓ syntax check passed
✓ md-daily parses validated rows
✓ md-daily drops risk > 5 (the CAPTCHA tail)
✓ md-daily drops junk cells
✓ md-daily on garbage → empty, no throw
✓ txt «ip port» lines keep their verified port
✓ txt space lines are port-pinned like ip:port
✓ txt space form == colon form
✓ refresh merges md-daily + space-txt feeds (dedup incl. duplicate table rows)
✓ daily IPs reach the pool port-pinned 443
✓ high-risk IP never reaches the pool
✓ space-txt IPs keep verified ports + repo country tag
✓ country import writes the selected ProxyIPs into the active list
✓ reselecting a country is actionable but reports zero real additions
(node:3501) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js?panel=pxfeeds is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✓ country import persists addresses and country metadata
✓ DEFAULT_PROXY_REPOS carries the daily source
✓ default repo list still fits the cap (≤10)
PROXYIP FEED TESTS PASSED
✓ survival apply → ok + changed list
✓ survival returns the survive link
(node:3520) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✓ preset: daily rotation
✓ preset: safe SNI = Host (spoofing/rotation off)
✓ preset: fragment on
✓ preset: plain ports on
✓ preset: both protocols
✓ preset: defaults + host + health ordering
✓ preset is idempotent (nothing left to change)
✓ survival without apply → 400
✓ settings dialed back (test setup)
✓ seed: 3 ips with provenance latency
✓ baseline sub: plain configs OFF (as configured)
✓ baseline sub: vless OFF, trojan only (as configured)
✓ baseline sub: original panel order
✓ survive: plain ports forced back on
✓ survive: both protocols forced back on
✓ survive: SNI pinned to worker Host (not spoofed)
✓ survive: scanner-proven fast address stays first
✓ survive applies to Base64 format
✓ survive applies to Clash/Mihomo format
✓ survive applies to sing-box format
✓ survive applies to Xray format
✓ survive render did NOT persist anything
✓ health ordering: 40ms leads, 120ms next, unknown last
✓ health ordering expires stale scans after 7 days
✓ health ordering carries domain Ping through DNS-to-IP mapping
✓ telemetry: users carry lastOnline (panel shows «never connected»)

SNI Lab — live SNI testing (panel 6.67.4)

(node:3533) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
  ✓ an anonymous caller gets 401 (the table describes the owner's network)
  ✓ …and cannot trigger the subrequests either
  ✓ an empty candidate list is rejected, not answered with a fake table
  ✓ a fresh panel reports an empty run, not an invented one
  ✓ …and the defaults it ships are listed for the page to offer
  ✓ …and the effective pool it will save into
  ✓ the run reports one row per unique, well-formed candidate
  ✓ a Cloudflare-fronted hostname is marked cf
  ✓ …the trace body alone is enough when the server header is stripped
  ✓ a plain origin is NOT marked cf, and its status is reported
  ✓ garbage and the worker's own hostname never reach the network
  ✓ every probe was a /cdn-cgi/trace fetch, timed
  ✓ the run is persisted under its own KV key (the panel can show it after a reload)
  ✓ …and read back unchanged
  ✓ a long list is capped at 12 probes per run
  ✓ the settings page carries the lab card (test / edge / save)
  ✓ …with both button labels and the hint in the panel language
  ✓ …and the same keys exist in English (no untranslated row)
  · source-shape checks skipped (artifact is minified/obfuscated — identifiers renamed)
  ✓ a run changes the lab record but NEVER the settings (the pool is only written by the form)
  ✓ the settings payload advertises the defaults so the page can offer them

sni lab: 20 passed, 0 FAILED
### kotlin-precheck.log
         > Could not get resource 'https://plugins.gradle.org/m2/org/apache/httpcomponents/httpclient/4.5.14/httpclient-4.5.14.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/org/apache/httpcomponents/httpclient/4.5.14/httpclient-4.5.14.pom'. Received status code 403 from server: Forbidden
   > Could not resolve net.java.dev.jna:jna-platform:5.6.0.
     Required by:
         project : > com.android.application:com.android.application.gradle.plugin:8.7.3 > com.android.tools.build:gradle:8.7.3 > com.android.tools.analytics-library:shared:31.7.3
         project : > com.android.application:com.android.application.gradle.plugin:8.7.3 > com.android.tools.build:gradle:8.7.3 > com.android.tools:sdk-common:31.7.3 > com.android.tools:common:31.7.3
      > Could not resolve net.java.dev.jna:jna-platform:5.6.0.
         > Could not get resource 'https://repo.maven.apache.org/maven2/net/java/dev/jna/jna-platform/5.6.0/jna-platform-5.6.0.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/net/java/dev/jna/jna-platform/5.6.0/jna-platform-5.6.0.pom'. Received status code 403 from server: Forbidden
      > Could not resolve net.java.dev.jna:jna-platform:5.6.0.
         > Could not get resource 'https://plugins.gradle.org/m2/net/java/dev/jna/jna-platform/5.6.0/jna-platform-5.6.0.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/net/java/dev/jna/jna-platform/5.6.0/jna-platform-5.6.0.pom'. Received status code 403 from server: Forbidden
   > Could not resolve com.googlecode.juniversalchardet:juniversalchardet:1.0.3.
     Required by:
         project : > com.android.application:com.android.application.gradle.plugin:8.7.3 > com.android.tools.build:gradle:8.7.3 > androidx.databinding:databinding-compiler-common:8.7.3
      > Could not resolve com.googlecode.juniversalchardet:juniversalchardet:1.0.3.
         > Could not get resource 'https://repo.maven.apache.org/maven2/com/googlecode/juniversalchardet/juniversalchardet/1.0.3/juniversalchardet-1.0.3.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/com/googlecode/juniversalchardet/juniversalchardet/1.0.3/juniversalchardet-1.0.3.pom'. Received status code 403 from server: Forbidden
      > Could not resolve com.googlecode.juniversalchardet:juniversalchardet:1.0.3.
         > Could not get resource 'https://plugins.gradle.org/m2/com/googlecode/juniversalchardet/juniversalchardet/1.0.3/juniversalchardet-1.0.3.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/com/googlecode/juniversalchardet/juniversalchardet/1.0.3/juniversalchardet-1.0.3.pom'. Received status code 403 from server: Forbidden
   > Could not resolve javax.annotation:javax.annotation-api:1.3.2.
     Required by:
         project : > com.android.application:com.android.application.gradle.plugin:8.7.3 > com.android.tools.build:gradle:8.7.3 > com.android.tools.utp:android-test-plugin-result-listener-gradle-proto:31.7.3
      > Could not resolve javax.annotation:javax.annotation-api:1.3.2.
         > Could not get resource 'https://repo.maven.apache.org/maven2/javax/annotation/javax.annotation-api/1.3.2/javax.annotation-api-1.3.2.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/javax/annotation/javax.annotation-api/1.3.2/javax.annotation-api-1.3.2.pom'. Received status code 403 from server: Forbidden
      > Could not resolve javax.annotation:javax.annotation-api:1.3.2.
         > Could not get resource 'https://plugins.gradle.org/m2/javax/annotation/javax.annotation-api/1.3.2/javax.annotation-api-1.3.2.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/javax/annotation/javax.annotation-api/1.3.2/javax.annotation-api-1.3.2.pom'. Received status code 403 from server: Forbidden
   > Could not resolve com.google.auto.value:auto-value-annotations:1.6.2.
     Required by:
         project : > com.android.application:com.android.application.gradle.plugin:8.7.3 > com.android.tools.build:gradle:8.7.3 > com.android.tools.build:bundletool:1.17.1
      > Could not resolve com.google.auto.value:auto-value-annotations:1.6.2.
         > Could not get resource 'https://repo.maven.apache.org/maven2/com/google/auto/value/auto-value-annotations/1.6.2/auto-value-annotations-1.6.2.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/com/google/auto/value/auto-value-annotations/1.6.2/auto-value-annotations-1.6.2.pom'. Received status code 403 from server: Forbidden
      > Could not resolve com.google.auto.value:auto-value-annotations:1.6.2.
         > Could not get resource 'https://plugins.gradle.org/m2/com/google/auto/value/auto-value-annotations/1.6.2/auto-value-annotations-1.6.2.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/com/google/auto/value/auto-value-annotations/1.6.2/auto-value-annotations-1.6.2.pom'. Received status code 403 from server: Forbidden
   > Could not resolve com.google.errorprone:error_prone_annotations:2.3.1.
     Required by:
         project : > com.android.application:com.android.application.gradle.plugin:8.7.3 > com.android.tools.build:gradle:8.7.3 > com.android.tools.build:bundletool:1.17.1
      > Could not resolve com.google.errorprone:error_prone_annotations:2.3.1.
         > Could not get resource 'https://repo.maven.apache.org/maven2/com/google/errorprone/error_prone_annotations/2.3.1/error_prone_annotations-2.3.1.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/com/google/errorprone/error_prone_annotations/2.3.1/error_prone_annotations-2.3.1.pom'. Received status code 403 from server: Forbidden
      > Could not resolve com.google.errorprone:error_prone_annotations:2.3.1.
         > Could not get resource 'https://plugins.gradle.org/m2/com/google/errorprone/error_prone_annotations/2.3.1/error_prone_annotations-2.3.1.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/com/google/errorprone/error_prone_annotations/2.3.1/error_prone_annotations-2.3.1.pom'. Received status code 403 from server: Forbidden
   > Could not resolve com.google.dagger:dagger:2.28.3.
     Required by:
         project : > com.android.application:com.android.application.gradle.plugin:8.7.3 > com.android.tools.build:gradle:8.7.3 > com.android.tools.build:bundletool:1.17.1
      > Could not resolve com.google.dagger:dagger:2.28.3.
         > Could not get resource 'https://repo.maven.apache.org/maven2/com/google/dagger/dagger/2.28.3/dagger-2.28.3.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/com/google/dagger/dagger/2.28.3/dagger-2.28.3.pom'. Received status code 403 from server: Forbidden
      > Could not resolve com.google.dagger:dagger:2.28.3.
         > Could not get resource 'https://plugins.gradle.org/m2/com/google/dagger/dagger/2.28.3/dagger-2.28.3.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/com/google/dagger/dagger/2.28.3/dagger-2.28.3.pom'. Received status code 403 from server: Forbidden
   > Could not resolve org.bitbucket.b_c:jose4j:0.9.5.
     Required by:
         project : > com.android.application:com.android.application.gradle.plugin:8.7.3 > com.android.tools.build:gradle:8.7.3 > com.android.tools.build:bundletool:1.17.1
      > Could not resolve org.bitbucket.b_c:jose4j:0.9.5.
         > Could not get resource 'https://repo.maven.apache.org/maven2/org/bitbucket/b_c/jose4j/0.9.5/jose4j-0.9.5.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/org/bitbucket/b_c/jose4j/0.9.5/jose4j-0.9.5.pom'. Received status code 403 from server: Forbidden
      > Could not resolve org.bitbucket.b_c:jose4j:0.9.5.
         > Could not get resource 'https://plugins.gradle.org/m2/org/bitbucket/b_c/jose4j/0.9.5/jose4j-0.9.5.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/org/bitbucket/b_c/jose4j/0.9.5/jose4j-0.9.5.pom'. Received status code 403 from server: Forbidden
   > Could not resolve org.slf4j:slf4j-api:1.7.30.
     Required by:
         project : > com.android.application:com.android.application.gradle.plugin:8.7.3 > com.android.tools.build:gradle:8.7.3 > com.android.tools.build:bundletool:1.17.1
      > Could not resolve org.slf4j:slf4j-api:1.7.30.
         > Could not get resource 'https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/1.7.30/slf4j-api-1.7.30.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/1.7.30/slf4j-api-1.7.30.pom'. Received status code 403 from server: Forbidden
      > Could not resolve org.slf4j:slf4j-api:1.7.30.
         > Could not get resource 'https://plugins.gradle.org/m2/org/slf4j/slf4j-api/1.7.30/slf4j-api-1.7.30.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/1.7.30/slf4j-api-1.7.30.pom'. Received status code 403 from server: Forbidden
   > Could not resolve org.jdom:jdom2:2.0.6.
     Required by:
         project : > com.android.application:com.android.application.gradle.plugin:8.7.3 > com.android.tools.build:gradle:8.7.3 > com.android.tools.build.jetifier:jetifier-processor:1.0.0-beta10
      > Could not resolve org.jdom:jdom2:2.0.6.
         > Could not get resource 'https://repo.maven.apache.org/maven2/org/jdom/jdom2/2.0.6/jdom2-2.0.6.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/org/jdom/jdom2/2.0.6/jdom2-2.0.6.pom'. Received status code 403 from server: Forbidden
      > Could not resolve org.jdom:jdom2:2.0.6.
         > Could not get resource 'https://plugins.gradle.org/m2/org/jdom/jdom2/2.0.6/jdom2-2.0.6.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/org/jdom/jdom2/2.0.6/jdom2-2.0.6.pom'. Received status code 403 from server: Forbidden
   > Could not resolve commons-codec:commons-codec:1.10.
     Required by:
         project : > com.android.application:com.android.application.gradle.plugin:8.7.3 > com.android.tools.build:gradle:8.7.3 > com.android.tools.build:builder:8.7.3
      > Could not resolve commons-codec:commons-codec:1.10.
         > Could not get resource 'https://repo.maven.apache.org/maven2/commons-codec/commons-codec/1.10/commons-codec-1.10.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/commons-codec/commons-codec/1.10/commons-codec-1.10.pom'. Received status code 403 from server: Forbidden
      > Could not resolve commons-codec:commons-codec:1.10.
         > Could not get resource 'https://plugins.gradle.org/m2/commons-codec/commons-codec/1.10/commons-codec-1.10.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/commons-codec/commons-codec/1.10/commons-codec-1.10.pom'. Received status code 403 from server: Forbidden
   > Could not resolve com.squareup:javawriter:2.5.0.
     Required by:
         project : > com.android.application:com.android.application.gradle.plugin:8.7.3 > com.android.tools.build:gradle:8.7.3 > com.android.tools.build:builder:8.7.3
      > Could not resolve com.squareup:javawriter:2.5.0.
         > Could not get resource 'https://repo.maven.apache.org/maven2/com/squareup/javawriter/2.5.0/javawriter-2.5.0.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/com/squareup/javawriter/2.5.0/javawriter-2.5.0.pom'. Received status code 403 from server: Forbidden
      > Could not resolve com.squareup:javawriter:2.5.0.
         > Could not get resource 'https://plugins.gradle.org/m2/com/squareup/javawriter/2.5.0/javawriter-2.5.0.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/com/squareup/javawriter/2.5.0/javawriter-2.5.0.pom'. Received status code 403 from server: Forbidden
   > Could not resolve com.google.code.findbugs:jsr305:3.0.2.
     Required by:
         project : > com.android.application:com.android.application.gradle.plugin:8.7.3 > com.android.tools.build:gradle:8.7.3 > com.android.tools.build:builder:8.7.3 > com.android.tools.build:apkzlib:8.7.3
      > Could not resolve com.google.code.findbugs:jsr305:3.0.2.
         > Could not get resource 'https://repo.maven.apache.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.pom'. Received status code 403 from server: Forbidden
      > Could not resolve com.google.code.findbugs:jsr305:3.0.2.
         > Could not get resource 'https://plugins.gradle.org/m2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.pom'. Received status code 403 from server: Forbidden
> There are 14 more failures with identical causes.

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights.
> Get more help at https://help.gradle.org.

BUILD FAILED in 3s
