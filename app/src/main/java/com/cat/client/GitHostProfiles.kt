// beta238: Zerops / Seenode / Hostim — three more hosts that build the Cat panel from the private GitHub repository the app creates; each with its own port/domain/limits and the steps for its own dashboard.
package com.cat.client

/**
 * Hosts that build from a GitHub repository like Lucity / Shiprr, but each needs something different (Nov 2026
 * sources, re-checked when added):
 *  • Zerops (docs.zerops.io): $15 promo credit, no card; reads `zerops.yml` from the repo (build + run + the HTTP
 *    port); the public address is a Zerops subdomain (`…zerops.app`, HTTPS on 443) that must be switched on once;
 *    free project plan includes 100 GB egress a month.
 *  • Seenode (seenode.com/docs): 7-day free trial; builds Node.js from Git; WebSockets are supported; the «Port»
 *    field defaults to 80 and must be the port the app listens on (the launcher's default 8080); address
 *    `*.seenode.app` with SSL; hosted in the EU.
 *  • Hostim (hostim.dev/docs): free 5-day trial project (one at a time); a Git app is built from a Dockerfile;
 *    «HTTP Service → Public website» + «HTTP Port»; address `*.hostim.dev` with HTTPS.
 * None of them keeps the container's disk between deploys (Seenode/Hostim need a paid volume), so the panel runs
 * with CAT_NO_DISK: the main config (UUID in cat-config.json) survives every deploy, extra panel users do not.
 *
 * Not added: Slipway — a self-hosted panel for your own server (or a $29/month managed box), not a free host; on a
 * VPS the normal «node index.js» install already works.
 *
 * Pure Kotlin (no Android) so the JVM tests can pin it.
 */
internal object GitHostProfiles {
    const val PORT = 8080

    data class Profile(
        val kind: String,
        val name: String,
        val site: String,
        val signup: String,
        val addressExample: String,
        val creditFa: String,
        val creditEn: String,
        val accountFa: String,
        val accountEn: String,
        val appFa: String,
        val appEn: String,
        val extraFiles: Map<String, String>,
    )

    /** Zerops: build with Node 22, ship the 4 files (+ npm's node_modules), run `node index.js` on 8080 (HTTP). */
    fun zeropsYml(): String =
        "# Cat panel on Zerops - the app writes this file; docs.zerops.io/zerops-yaml/specification\n" +
            "zerops:\n" +
            "  - setup: app\n" +
            "    build:\n" +
            "      base: nodejs@22\n" +
            "      buildCommands:\n" +
            "        - npm install --omit=dev --no-audit --no-fund\n" +
            "      deployFiles: ./\n" +
            "    run:\n" +
            "      base: nodejs@22\n" +
            "      ports:\n" +
            "        - port: $PORT\n" +
            "          httpSupport: true\n" +
            "      envVariables:\n" +
            "        PORT: \"$PORT\"\n" +
            "      start: node index.js\n"

    /** Hostim builds a Git app from a Dockerfile. */
    fun dockerfile(): String =
        "# Cat panel - the app writes this file (Hostim builds Git apps from a Dockerfile)\n" +
            "FROM node:22-alpine\n" +
            "WORKDIR /app\n" +
            "COPY package.json ./\n" +
            "RUN npm install --omit=dev --no-audit --no-fund\n" +
            "COPY . .\n" +
            "ENV PORT=$PORT\n" +
            "EXPOSE $PORT\n" +
            "CMD [\"node\", \"index.js\"]\n"

    private val ZEROPS = Profile(
        kind = NodeHostPanel.ZEROPS,
        name = "Zerops",
        site = "https://app.zerops.io",
        signup = "https://app.zerops.io/registration",
        addressExample = "https://app-….prg1.zerops.app",
        creditFa = "۱۵ دلار اعتبار رایگان (بدون کارت)",
        creditEn = "\$15 free credit (no card)",
        accountFa = "1) «🌐 ثبت‌نام Zerops» ← ایمیل یا GitHub ← ثبت‌نام (۱۵ دلار اعتبار هدیه، کارت لازم نیست).\n" +
            "2) ایمیل تأیید را باز کنید (Spam را هم ببینید).\n" +
            "3) وقتی Zerops خواست GitHub را وصل کنید، اجازهٔ دسترسی به همان cat-node-panel-xxxxx را بدهید.",
        accountEn = "1) «🌐 Zerops sign-up» → e-mail or GitHub → sign up (\$15 gift credit, no card needed).\n" +
            "2) Open the confirmation e-mail (check Spam).\n" +
            "3) When Zerops asks to connect GitHub, allow access to that cat-node-panel-xxxxx.",
        appFa = "1) Add new project ← یک نام ← ساخت. سپس Add new service ← Node.js ← نام: app (همین نام مهم است؛ در zerops.yml نوشته شده).\n" +
            "2) در همان سرویس: Connect GitHub repository ← مخزن cat-node-panel-xxxxx ← شاخهٔ main. Zerops فایل zerops.yml را خودش می‌خواند (پورت 8080 HTTP).\n" +
            "3) وقتی Build سبز شد: Public access ← Zerops subdomain ← Enable. آدرس شبیه https://app-xxxx-8080.prg1.zerops.app است.\n" +
            "4) «✍️ پنلم را اضافه کن» ← همان آدرس ← تست و اضافه.",
        appEn = "1) Add new project → a name → create. Then Add new service → Node.js → name: app (this exact name matters; zerops.yml uses it).\n" +
            "2) In that service: Connect GitHub repository → cat-node-panel-xxxxx → branch main. Zerops reads zerops.yml itself (port 8080 HTTP).\n" +
            "3) When the build is green: Public access → Zerops subdomain → Enable. The address looks like https://app-xxxx-8080.prg1.zerops.app.\n" +
            "4) «✍️ Add my panel» → that address → Test and add.",
        extraFiles = mapOf("zerops.yml" to zeropsYml()),
    )

    private val SEENODE = Profile(
        kind = NodeHostPanel.SEENODE,
        name = "Seenode",
        site = "https://cloud.seenode.com",
        signup = "https://seenode.com",
        addressExample = "https://….seenode.app",
        creditFa = "۷ روز آزمایشی رایگان",
        creditEn = "7-day free trial",
        accountFa = "1) «🌐 ثبت‌نام Seenode» ← Sign up (با GitHub ساده‌تر است). ۷ روز آزمایشی رایگان دارد، بعد پولی می‌شود.\n" +
            "2) وقتی خواست GitHub را وصل کنید، اجازهٔ دسترسی به همان cat-node-panel-xxxxx را بدهید.",
        accountEn = "1) «🌐 Seenode sign-up» → Sign up (GitHub is easiest). 7-day free trial, then paid.\n" +
            "2) When it asks to connect GitHub, allow access to that cat-node-panel-xxxxx.",
        appFa = "1) New ← Web Service ← مخزن cat-node-panel-xxxxx.\n" +
            "2) Build Command: npm install — Start Command: node index.js\n" +
            "3) ⚠️ مهم: کادر «Port» را از 80 به 8080 تغییر دهید (Seenode ترافیک را به همین عدد می‌فرستد و پنل روی 8080 گوش می‌دهد).\n" +
            "4) ساخت ← وقتی سبز شد آدرس https://نام.seenode.app را کپی کنید ← «✍️ پنلم را اضافه کن». Seenode رسماً WebSocket را پشتیبانی می‌کند.",
        appEn = "1) New → Web Service → repository cat-node-panel-xxxxx.\n" +
            "2) Build Command: npm install — Start Command: node index.js\n" +
            "3) ⚠️ Important: change the «Port» field from 80 to 8080 (Seenode sends traffic to that number and the panel listens on 8080).\n" +
            "4) Create → when green copy the https://name.seenode.app address → «✍️ Add my panel». Seenode officially supports WebSockets.",
        extraFiles = emptyMap(),
    )

    private val HOSTIM = Profile(
        kind = NodeHostPanel.HOSTIM,
        name = "Hostim",
        site = "https://console.hostim.dev",
        signup = "https://hostim.dev",
        addressExample = "https://….hostim.dev",
        creditFa = "پروژهٔ آزمایشی ۵ روزه رایگان",
        creditEn = "free 5-day trial project",
        accountFa = "1) «🌐 ثبت‌نام Hostim» ← ثبت‌نام. هر بار یک پروژهٔ آزمایشی ۵ روزهٔ رایگان می‌دهد (بعدش پلن پولی از ۲٫۵ یورو).\n" +
            "2) اگر Hostim برای مخزن خصوصی GitHub اجازه خواست، دسترسی به همان cat-node-panel-xxxxx را بدهید.",
        accountEn = "1) «🌐 Hostim sign-up» → sign up. It gives one free 5-day trial project at a time (then paid plans from 2.5 EUR).\n" +
            "2) If Hostim asks for access to a private GitHub repository, allow that cat-node-panel-xxxxx.",
        appFa = "1) New Project (آزمایشی) ← Create Service ← New App ← Deployment type: Git ← آدرس مخزن cat-node-panel-xxxxx ← شاخه main ← Dockerfile path: Dockerfile (برنامه این فایل را در مخزن گذاشته).\n" +
            "2) HTTP Service ← Public website ← HTTP Port: 8080 ← Create App.\n" +
            "3) وقتی بالا آمد آدرس https://….hostim.dev را کپی کنید ← «✍️ پنلم را اضافه کن». برنامه واقعاً تست می‌کند WebSocket رد می‌شود یا نه.",
        appEn = "1) New Project (trial) → Create Service → New App → Deployment type: Git → the cat-node-panel-xxxxx repository → branch main → Dockerfile path: Dockerfile (the app put that file in the repository).\n" +
            "2) HTTP Service → Public website → HTTP Port: 8080 → Create App.\n" +
            "3) When it is up copy the https://….hostim.dev address → «✍️ Add my panel». The app really tests whether WebSocket passes.",
        extraFiles = mapOf("Dockerfile" to dockerfile()),
    )

    val ALL: List<Profile> = listOf(ZEROPS, SEENODE, HOSTIM)

    fun of(kind: String): Profile? = ALL.firstOrNull { it.kind == kind }
}
