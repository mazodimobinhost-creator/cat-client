/**
 * 🌐 Cat GP — your own relay for Cat Client (Code.gs)
 *
 * 1) script.google.com → New project → delete everything → paste this whole file → Save.
 * 2) Deploy → New deployment → ⚙ Web app → Execute as: Me → Who has access: Anyone → Deploy → allow access.
 * 3) Copy the "Web app" address (…/exec) back into Cat Client.
 * Keep CAT_KEY secret: whoever has it can use your daily Google quota (~20 000 requests).
 * If CAT_WORKER is set, the same key is sent with every hop so a leaked panel UUID cannot fetch.
 * After editing this file: Deploy → Manage deployments → ✏ → Version: New version (the address stays the same).
 */
const CAT_KEY = __CAT_KEY__;
// Optional: your Cat panel's /gp/<uuid>/<key> address → sites see a Cloudflare exit instead of Google.
const CAT_WORKER = __CAT_WORKER__;

const SKIP = { host: 1, connection: 1, "content-length": 1, "transfer-encoding": 1, "proxy-connection": 1,
  "proxy-authorization": 1, "accept-encoding": 1, te: 1, upgrade: 1, "keep-alive": 1, trailer: 1,
  "cf-connecting-ip": 1, "cf-ipcountry": 1, "x-forwarded-for": 1, "x-forwarded-proto": 1, "x-real-ip": 1, forwarded: 1 };
const METHODS = { GET: "get", POST: "post", PUT: "put", DELETE: "delete", PATCH: "patch" };
const MAX_B64 = 8 * 1024 * 1024;

function doPost(e) {
  var q;
  try { q = JSON.parse(e.postData.contents); } catch (err) { return reply({ e: "bad request" }); }
  if (!q || q.k !== CAT_KEY) return reply({ e: "unauthorized" });
  try { return reply(relayOne(q)); } catch (err) { return reply({ e: String((err && err.message) || err) }); }
}

function relayOne(q) {
  if (typeof q.u !== "string") return { e: "bad url" };
  var url = q.u;
  if (!/^https?:\/\//i.test(url) || url.length > 4096) return { e: "bad url" };
  var method = METHODS[String(q.m || "GET").toUpperCase()];
  if (!method) return { e: "method not supported by Apps Script: " + q.m };
  if (typeof q.b === "string" && q.b.length > MAX_B64) return { e: "too large" };
  var headers = {};
  var h = q.h || {};
  for (var k in h) if (!SKIP[k.toLowerCase()] && k.toLowerCase().indexOf("proxy-") !== 0 && k.toLowerCase().indexOf("cf-") !== 0) headers[k] = String(h[k]);
  if (CAT_WORKER) {
    var w = UrlFetchApp.fetch(CAT_WORKER, { method: "post", contentType: "application/json", muteHttpExceptions: true,
      payload: JSON.stringify({ k: CAT_KEY, u: url, m: method.toUpperCase(), h: headers, b: q.b || "", ct: q.ct || "", r: q.r === true }) });
    var t = w.getContentText();
    try { return JSON.parse(t); } catch (err) { return { e: "worker HTTP " + w.getResponseCode() }; }
  }
  var opt = { method: method, headers: headers, muteHttpExceptions: true, followRedirects: q.r === true, validateHttpsCertificates: true };
  if (q.b) { opt.payload = Utilities.base64Decode(q.b); if (q.ct) opt.contentType = q.ct; }
  var r;
  try {
    r = UrlFetchApp.fetch(url, opt);
  } catch (err) {
    // Some browser headers are refused by UrlFetchApp: retry once with the plain ones.
    if (!/header|attribute/i.test(String(err))) throw err;
    var safe = {};
    ["Accept", "Accept-Language", "Content-Type", "Cookie", "Authorization", "Referer", "Range", "If-None-Match", "If-Modified-Since"]
      .forEach(function (n) { for (var k2 in headers) if (k2.toLowerCase() === n.toLowerCase()) safe[n] = headers[k2]; });
    opt.headers = safe;
    r = UrlFetchApp.fetch(url, opt);
  }
  return { s: r.getResponseCode(), h: r.getAllHeaders(), b: Utilities.base64Encode(r.getContent()) };
}

function doGet() { return reply({ ok: true, relay: "cat-gp" }); }
function reply(o) { return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON); }
