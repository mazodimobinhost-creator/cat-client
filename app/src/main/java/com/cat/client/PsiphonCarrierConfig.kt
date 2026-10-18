package com.cat.client

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Minimal Psiphon proxy-only configuration for the isolated carrier process. */
internal object PsiphonCarrierConfig {
    private const val PROPAGATION_CHANNEL_ID = "FFFFFFFFFFFFFFFF"
    private const val SPONSOR_ID = "1111111111111111"
    private const val SERVER_ENTRY_SIGNATURE_PUBLIC_KEY =
        "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA="
    private const val REMOTE_SERVER_LIST_SIGNATURE_PUBLIC_KEY =
        "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="
    private const val REMOTE_SERVER_LIST_URL =
        "https://s3.amazonaws.com//psiphon/web/mjr4-p23r-puwl/server_list_compressed"

    fun render(context: Context, upstreamPort: Int, region: String = ""): String =
        render(File(context.noBackupFilesDir, "psiphon"), BuildConfig.VERSION_CODE, upstreamPort, region)

    internal fun render(dataRoot: File, clientVersion: Int, upstreamPort: Int, region: String): String {
        require(dataRoot.isAbsolute)
        require(upstreamPort in 1024..65535)
        require(region.isEmpty() || region.matches(Regex("[A-Z]{2}")))
        return JSONObject()
            .put("PropagationChannelId", PROPAGATION_CHANNEL_ID)
            .put("SponsorId", SPONSOR_ID)
            .put("ClientVersion", clientVersion.toString())
            .put("DataRootDirectory", dataRoot.absolutePath)
            .put("ServerEntrySignaturePublicKey", SERVER_ENTRY_SIGNATURE_PUBLIC_KEY)
            .put("RemoteServerListSignaturePublicKey", REMOTE_SERVER_LIST_SIGNATURE_PUBLIC_KEY)
            .put("RemoteServerListUrl", REMOTE_SERVER_LIST_URL)
            .put("EgressRegion", region)
            .put("UpstreamProxyUrl", "socks5://127.0.0.1:$upstreamPort")
            .put("EstablishTunnelTimeoutSeconds", 300)
            .put("ConnectionWorkerPoolSize", 12)
            .put("LocalSocksProxyPort", 0)
            .put("DisableLocalHTTPProxy", true)
            .put("EmitDiagnosticNotices", true)
            .put("EmitDiagnosticNetworkParameters", false)
            .toString()
    }
}
