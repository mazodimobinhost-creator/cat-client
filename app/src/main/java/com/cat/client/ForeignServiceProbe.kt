package com.cat.client

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Public HTTPS endpoints used by the in-app, on-device foreign-service check. */
internal data class ForeignServiceTarget(
    val id: String,
    val label: String,
    val url: String,
)

internal enum class ForeignServiceStatus {
    OPEN,
    HTTP_RESPONSE,
    RATE_LIMITED,
    SERVER_ERROR,
    UNREACHABLE,
}

/** Deliberately coarse failure buckets: no exception messages or network identifiers are shared. */
internal enum class ForeignServiceFailure {
    DNS,
    TIMEOUT,
    TLS,
    NETWORK,
}

internal data class ForeignServiceProbeResult(
    val target: ForeignServiceTarget,
    val httpStatus: Int,
    val latencyMs: Int,
    val failure: ForeignServiceFailure? = null,
) {
    val status: ForeignServiceStatus get() = classifyForeignServiceStatus(httpStatus)
}

internal object ForeignServiceCatalog {
    val targets: List<ForeignServiceTarget> = listOf(
        ForeignServiceTarget("google", "Google", "https://www.google.com/generate_204"),
        ForeignServiceTarget("youtube", "YouTube", "https://www.youtube.com/generate_204"),
        ForeignServiceTarget("play_store", "Play Store", "https://play.google.com/store/games"),
        ForeignServiceTarget("telegram", "Telegram", "https://web.telegram.org/"),
        ForeignServiceTarget("whatsapp", "WhatsApp", "https://web.whatsapp.com/"),
        ForeignServiceTarget("instagram", "Instagram", "https://www.instagram.com/"),
    )
}

/** A 2xx response proves this endpoint answered, not that every app feature is usable. */
internal fun classifyForeignServiceStatus(httpStatus: Int): ForeignServiceStatus = when {
    httpStatus in 200..299 -> ForeignServiceStatus.OPEN
    httpStatus == 429 -> ForeignServiceStatus.RATE_LIMITED
    httpStatus in 300..499 -> ForeignServiceStatus.HTTP_RESPONSE
    httpStatus in 500..599 -> ForeignServiceStatus.SERVER_ERROR
    else -> ForeignServiceStatus.UNREACHABLE
}

/** Maps exception types only; exception text can contain request or network details and is discarded. */
internal fun classifyForeignServiceFailure(error: Throwable): ForeignServiceFailure {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    return when {
        causes.any { it is SocketTimeoutException } -> ForeignServiceFailure.TIMEOUT
        causes.any { it is UnknownHostException } -> ForeignServiceFailure.DNS
        causes.any { it is SSLException } -> ForeignServiceFailure.TLS
        causes.any { it is ConnectException || it is NoRouteToHostException } -> ForeignServiceFailure.NETWORK
        else -> ForeignServiceFailure.NETWORK
    }
}
