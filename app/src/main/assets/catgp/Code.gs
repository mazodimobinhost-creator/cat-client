// Cat GP — your own relay for Cat Client (Code.gs)
// 1) script.google.com → New project → delete everything → paste this whole file → Save.
// 2) Deploy → New deployment → Web app → Execute as: Me → Who has access: Anyone → Deploy.
// 3) Copy the Web app address (.../exec) back into Cat Client.
// Keep CAT_KEY secret: whoever has it can use your daily Google quota (~20 000 requests).
// If CAT_WORKER is set, each request goes to YOUR Cat GP Cloudflare Worker (not the Cat panel).
// After editing: Deploy → Manage deployments → pencil → Version: New version (the address stays).
// Do not leave the default myFunction in the file. A leftover } then a block comment is
// "Unexpected token *" in the Apps Script parser (it reads / as a regex).
var CAT_KEY = __CAT_KEY__;
var CAT_WORKER = __CAT_WORKER__;

var SKIP = { host: 1, connection: 1, "content-length": 1, "transfer-encoding": 1, "proxy-connection": 1,
  "proxy-authorization": 1, "accept-encoding": 1, te: 1, upgrade: 1, "keep-alive": 1, trailer: 1,
  "cf-connecting-ip": 1, "cf-ipcountry": 1, "x-forwarded-for": 1, "x-forwarded-proto": 1, "x-real-ip": 1, forwarded: 1 };
var METHODS = { GET: "get", POST: "post", PUT: "put", DELETE: "delete", PATCH: "patch" };
var MAX_B64 = 8 * 1024 * 1024;
var SAFE_HEADERS = ["Accept", "Accept-Language", "Content-Type", "Cookie", "Authorization", "Referer", "Range", "If-None-Match", "If-Modified-Since"];

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
  var k;
  for (k in h) if (!SKIP[k.toLowerCase()] && k.toLowerCase().indexOf("proxy-") !== 0 && k.toLowerCase().indexOf("cf-") !== 0) headers[k] = String(h[k]);
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
    var si, n, k2;
    for (si = 0; si < SAFE_HEADERS.length; si++) {
      n = SAFE_HEADERS[si];
      for (k2 in headers) if (k2.toLowerCase() === n.toLowerCase()) safe[n] = headers[k2];
    }
    opt.headers = safe;
    r = UrlFetchApp.fetch(url, opt);
  }
  return { s: r.getResponseCode(), h: r.getAllHeaders(), b: Utilities.base64Encode(r.getContent()) };
}

function doGet() { return reply({ ok: true, relay: "cat-gp" }); }
function reply(o) { return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON); }
