package com.cat.client

/**
 * Turns a raw connection failure (core/engine exception text, including its cause chain) into a
 * short, plain-language reason and a "what to do" hint, so a failed connect is never shown as just
 * "Connection error". Inspired by ZedSecure 3.1.6's ConnectionFault (CluvexStudio, AGPL-3.0);
 * the rules and texts here are written for Cat Client's own engines (mihomo, SlipNet, Paqet...).
 */
internal object ConnectionFaultExplainer {

    enum class Cause {
        PortBusy,
        LocalProxyMissing,
        EngineBusy,
        Config,
        Certificate,
        Reality,
        NeedsRoot,
        Permission,
        NoNetwork,
        Dns,
        Auth,
        Refused,
        Timeout,
        HealthCheck,
        Unknown,
    }

    data class Fault(
        val cause: Cause,
        val titleFa: String,
        val titleEn: String,
        val hintFa: String,
        val hintEn: String,
        val raw: String,
    ) {
        fun title(persian: Boolean) = if (persian) titleFa else titleEn
        fun hint(persian: Boolean) = if (persian) hintFa else hintEn
    }

    // Order matters: the first matching rule wins, most specific first.
    private val RULES: List<Pair<Cause, List<String>>> = listOf(
        Cause.LocalProxyMissing to listOf("only a local proxy at 127.0.0.1"),
        Cause.EngineBusy to listOf("engine is already running", "engine did not shut down in time",
            "route preparation is already running", "session is already active", "carrier is already active"),
        Cause.PortBusy to listOf("address already in use", "eaddrinuse", "failed to listen", "bind: ", "port is busy"),
        Cause.NeedsRoot to listOf("requires root", "needs root", "root access", "su binary", "raw socket"),
        Cause.Permission to listOf("vpn permission", "not prepared", "vpnservice.prepare", "failed to establish",
            "establish() returned null", "always-on vpn"),
        Cause.Certificate to listOf("x509", "certificate signed by unknown authority", "certificate is not valid",
            "certificate has expired", "certificate is valid for", "bad certificate"),
        Cause.Reality to listOf("reality"),
        Cause.Config to listOf("yaml:", "failed to parse", "parse error", "parseerror", "unmarshal", "invalid config",
            "config error", "unknown field", "unsupported", "not supported", "missing required", "invalid uuid",
            "invalid private key", "invalid public key", "proxy not found", "no proxies"),
        Cause.Auth to listOf("unauthorized", "authentication failed", "auth failed", "invalid user", "http 401",
            "http 403", "status code 403", "forbidden", "account required", "password"),
        Cause.NoNetwork to listOf("network is unreachable", "enetunreach", "no usable default network",
            "no internet", "no route to host", "ehostunreach", "airplane"),
        Cause.Dns to listOf("no such host", "unknownhostexception", "unable to resolve host", "lookup ",
            "dns resolve", "nxdomain", "servfail"),
        Cause.Refused to listOf("connection refused", "econnrefused", "connection reset", "econnreset",
            "broken pipe", "unexpected eof", "server closed", "eof"),
        Cause.HealthCheck to listOf("health check", "passed the runtime", "passed mihomo delay", "no working",
            "all candidates failed", "no candidate"),
        Cause.Timeout to listOf("timeout", "timed out", "deadline exceeded", "i/o timeout", "handshake did not complete"),
    )

    fun classify(raw: String?): Cause {
        val text = raw.orEmpty().lowercase()
        if (text.isBlank()) return Cause.Unknown
        return RULES.firstOrNull { (_, needles) -> needles.any { it in text } }?.first ?: Cause.Unknown
    }

    fun explain(raw: String?): Fault {
        val cause = classify(raw)
        val text = raw.orEmpty().trim()
        return when (cause) {
            Cause.PortBusy -> Fault(cause,
                "پورت محلی اشغال است",
                "Local port already in use",
                "یک برنامهٔ دیگر (معمولاً یک VPN یا پروکسی دیگر) همین پورت را گرفته. آن برنامه را ببندید و دوباره «اتصال» را بزنید.",
                "Another app (usually another VPN or proxy) holds the same port. Close it and tap Connect again.",
                text)
            Cause.LocalProxyMissing -> Fault(cause,
                "این ردیف سرور ندارد (فقط پروکسی محلی)",
                "This row has no server (local proxy only)",
                "این ردیف به پروکسی یک برنامهٔ دیگر روی همین گوشی (127.0.0.1) اشاره می‌کند که اجرا نیست. Cat Warp را از کارت «⚡ Cat Warp» بسازید یا این سابسکریپشن را حذف کنید.",
                "This row points at another app's proxy on this phone (127.0.0.1) that is not running. Build Cat Warp from the «⚡ Cat Warp» card or delete this subscription.",
                text)
            Cause.EngineBusy -> Fault(cause,
                "هستهٔ Cat Warp هنوز اتصال قبلی را می‌بست",
                "The Cat Warp engine was still closing the previous connection",
                "چند ثانیه صبر کنید و دوباره «اتصال» را بزنید. اگر تکرار شد، یک بار قطع و وصل کنید.",
                "Wait a few seconds and tap Connect again. If it repeats, disconnect once and reconnect.",
                text)
            Cause.NeedsRoot -> Fault(cause,
                "این روش به روت نیاز دارد",
                "This method needs root",
                "این نوع کانفیگ روی گوشی بدون روت مستقیم کار نمی‌کند. یک کانفیگ دیگر انتخاب کنید یا «پیشنهادی» را بزنید.",
                "This config type cannot run directly on a non-rooted phone. Pick another config or use the Recommended option.",
                text)
            Cause.Permission -> Fault(cause,
                "اجازهٔ VPN داده نشد",
                "VPN permission missing",
                "اجازهٔ ساخت VPN را تأیید کنید. اگر VPN دیگری روی «همیشه روشن» است، آن را خاموش کنید.",
                "Allow the VPN request. If another VPN is set to Always-on, turn it off.",
                text)
            Cause.Certificate -> Fault(cause,
                "گواهی TLS سرور معتبر نیست",
                "Server TLS certificate rejected",
                "SNI یا دامنهٔ کانفیگ با سرور جور نیست یا گواهی منقضی شده. کانفیگ را از منبع دوباره بگیرید.",
                "The SNI/domain does not match the server or the certificate expired. Re-fetch the config from its source.",
                text)
            Cause.Reality -> Fault(cause,
                "دست‌دهی Reality ناموفق بود",
                "Reality handshake failed",
                "کلید عمومی (pbk)، shortId یا SNI کانفیگ اشتباه است یا سرور تغییر کرده. کانفیگ تازه بگیرید.",
                "The public key (pbk), shortId or SNI is wrong, or the server changed. Get a fresh config.",
                text)
            Cause.Config -> Fault(cause,
                "کانفیگ ناقص یا نامعتبر است",
                "Config is invalid or incomplete",
                "این کانفیگ فیلد اشتباه یا پشتیبانی‌نشده دارد. آن را دوباره کپی کنید یا کانفیگ دیگری بزنید.",
                "This config has a wrong or unsupported field. Copy it again or pick another config.",
                text)
            Cause.Auth -> Fault(cause,
                "احراز هویت رد شد",
                "Authentication rejected",
                "رمز، UUID یا حساب کاربری درست نیست یا اشتراک منقضی شده.",
                "The password, UUID or account is wrong, or the subscription expired.",
                text)
            Cause.NoNetwork -> Fault(cause,
                "اینترنت گوشی در دسترس نیست",
                "No network",
                "وای‌فای یا دیتای موبایل را بررسی کنید و دوباره امتحان کنید.",
                "Check Wi-Fi or mobile data and try again.",
                text)
            Cause.Dns -> Fault(cause,
                "نام سرور پیدا نشد (DNS)",
                "Server name could not be resolved",
                "DNS شبکه دامنهٔ سرور را باز نمی‌کند. از اسکنر IP تمیز استفاده کنید یا کانفیگ با IP مستقیم بزنید.",
                "The network DNS cannot resolve the server. Use the clean-IP scanner or a config with a direct IP.",
                text)
            Cause.Refused -> Fault(cause,
                "سرور اتصال را قطع کرد",
                "Server closed the connection",
                "سرور خاموش است یا فایروال اپراتور این مسیر را بسته. سرور/کانفیگ دیگری امتحان کنید.",
                "The server is down or the operator firewall blocks this path. Try another server/config.",
                text)
            Cause.HealthCheck -> Fault(cause,
                "هیچ کانفیگی از تست سلامت رد نشد",
                "No config passed the health check",
                "کانفیگ‌ها روی این شبکه کار نمی‌کنند. «پیشنهادی» یا اسکنر را بزنید، یا اشتراک را به‌روز کنید.",
                "None of the configs work on this network. Use Recommended or the scanner, or refresh the subscription.",
                text)
            Cause.Timeout -> Fault(cause,
                "زمان اتصال تمام شد",
                "Connection timed out",
                "شبکه خیلی کند است یا مسیر فیلتر شده. کانفیگ یا IP تمیز دیگری امتحان کنید.",
                "The network is too slow or the path is filtered. Try another config or clean IP.",
                text)
            Cause.Unknown -> Fault(cause,
                "اتصال ناموفق بود",
                "Connection failed",
                "دوباره امتحان کنید؛ اگر تکرار شد کانفیگ دیگری انتخاب کنید یا جزئیات را کپی کنید.",
                "Try again; if it repeats, pick another config or copy the details.",
                text)
        }
    }

    /** Message plus distinct cause messages ("a ← b ← c"), so the real reason is not lost. */
    fun describeChain(error: Throwable?, maxDepth: Int = 5): String {
        val parts = mutableListOf<String>()
        var current = error
        var depth = 0
        while (current != null && depth < maxDepth) {
            val msg = current.message?.trim()?.takeIf(String::isNotEmpty) ?: current.javaClass.simpleName
            if (parts.none { it == msg || it.contains(msg) }) parts += msg
            if (current.cause === current) break
            current = current.cause
            depth++
        }
        return parts.joinToString(" ← ").take(600)
    }
}
