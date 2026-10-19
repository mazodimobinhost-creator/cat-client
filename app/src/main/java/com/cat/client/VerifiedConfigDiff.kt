package com.cat.client

internal data class ConfigFieldChange(val field: String, val before: String?, val after: String?)

/** Preview shown before optimizer/failover applies a config change. Secrets are
 * represented only as changed/unchanged and never copied into the diff. */
internal object VerifiedConfigDiff {
    fun between(before: VerifiedConfigUri, after: VerifiedConfigUri): List<ConfigFieldChange> = buildList {
        addIfChanged("protocol", before.protocol.scheme, after.protocol.scheme)
        addIfChanged("server", before.endpoint.host, after.endpoint.host)
        addIfChanged("port", before.endpoint.port.toString(), after.endpoint.port.toString())
        addIfChanged("host", before.workerDomain, after.workerDomain)
        addIfChanged("sni", before.workerDomain, after.workerDomain)
        addIfChanged("path", before.path, after.path)
        addIfChanged("tls", before.tls.toString(), after.tls.toString())
        addIfChanged("fingerprint", before.fingerprint, after.fingerprint)
        addIfChanged("proxyIp", before.proxyIp?.authority, after.proxyIp?.authority)
        if (before.credential != after.credential) add(ConfigFieldChange("credential", "changed", "changed"))
    }

    private fun MutableList<ConfigFieldChange>.addIfChanged(field: String, before: String?, after: String?) {
        if (before != after) add(ConfigFieldChange(field, before, after))
    }
}
