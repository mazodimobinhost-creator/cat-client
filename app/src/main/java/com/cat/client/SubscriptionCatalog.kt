package com.cat.client

data class SubscriptionCatalog(
    val profiles: List<ConnectionProfile>,
    val fetchedAt: Long,
)
