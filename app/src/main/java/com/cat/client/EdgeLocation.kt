package com.cat.client

/**
 * Human-friendly locations for the edge codes returned by /cdn-cgi/trace.
 *
 * An anycast IP does not have one permanent country. The edge code is the
 * location that actually answered the probe, so it is more useful than trying
 * to geolocate the shared IP address itself.
 */
data class EdgeLocation(
    val colo: String,
    val city: String,
    val country: String,
    val countryCode: String,
) {
    val flag: String get() = countryCode.toFlagEmoji()
    val label: String get() = "$city, $country"
}

object EdgeLocationCatalog {
    private val locations = mapOf(
        "AMS" to EdgeLocation("AMS", "Amsterdam", "Netherlands", "NL"),
        "ATH" to EdgeLocation("ATH", "Athens", "Greece", "GR"),
        "ATL" to EdgeLocation("ATL", "Atlanta", "United States", "US"),
        "AUH" to EdgeLocation("AUH", "Abu Dhabi", "United Arab Emirates", "AE"),
        "BAH" to EdgeLocation("BAH", "Manama", "Bahrain", "BH"),
        "BCN" to EdgeLocation("BCN", "Barcelona", "Spain", "ES"),
        "BEG" to EdgeLocation("BEG", "Belgrade", "Serbia", "RS"),
        "BER" to EdgeLocation("BER", "Berlin", "Germany", "DE"),
        "BOM" to EdgeLocation("BOM", "Mumbai", "India", "IN"),
        "BOS" to EdgeLocation("BOS", "Boston", "United States", "US"),
        "BRU" to EdgeLocation("BRU", "Brussels", "Belgium", "BE"),
        "BUD" to EdgeLocation("BUD", "Budapest", "Hungary", "HU"),
        "CAI" to EdgeLocation("CAI", "Cairo", "Egypt", "EG"),
        "CDG" to EdgeLocation("CDG", "Paris", "France", "FR"),
        "CGK" to EdgeLocation("CGK", "Jakarta", "Indonesia", "ID"),
        "CLT" to EdgeLocation("CLT", "Charlotte", "United States", "US"),
        "CMB" to EdgeLocation("CMB", "Colombo", "Sri Lanka", "LK"),
        "CPH" to EdgeLocation("CPH", "Copenhagen", "Denmark", "DK"),
        "CPT" to EdgeLocation("CPT", "Cape Town", "South Africa", "ZA"),
        "DEL" to EdgeLocation("DEL", "Delhi", "India", "IN"),
        "DFW" to EdgeLocation("DFW", "Dallas", "United States", "US"),
        "DME" to EdgeLocation("DME", "Moscow", "Russia", "RU"),
        "DOH" to EdgeLocation("DOH", "Doha", "Qatar", "QA"),
        "DUB" to EdgeLocation("DUB", "Dublin", "Ireland", "IE"),
        "DUS" to EdgeLocation("DUS", "Dusseldorf", "Germany", "DE"),
        "DXB" to EdgeLocation("DXB", "Dubai", "United Arab Emirates", "AE"),
        "EWR" to EdgeLocation("EWR", "Newark", "United States", "US"),
        "EZE" to EdgeLocation("EZE", "Buenos Aires", "Argentina", "AR"),
        "FCO" to EdgeLocation("FCO", "Rome", "Italy", "IT"),
        "FRA" to EdgeLocation("FRA", "Frankfurt", "Germany", "DE"),
        "GIG" to EdgeLocation("GIG", "Rio de Janeiro", "Brazil", "BR"),
        "GRU" to EdgeLocation("GRU", "Sao Paulo", "Brazil", "BR"),
        "HEL" to EdgeLocation("HEL", "Helsinki", "Finland", "FI"),
        "HKG" to EdgeLocation("HKG", "Hong Kong", "Hong Kong", "HK"),
        "IAD" to EdgeLocation("IAD", "Washington", "United States", "US"),
        "IAH" to EdgeLocation("IAH", "Houston", "United States", "US"),
        "ICN" to EdgeLocation("ICN", "Seoul", "South Korea", "KR"),
        "JNB" to EdgeLocation("JNB", "Johannesburg", "South Africa", "ZA"),
        "JFK" to EdgeLocation("JFK", "New York", "United States", "US"),
        "KIX" to EdgeLocation("KIX", "Osaka", "Japan", "JP"),
        "KUL" to EdgeLocation("KUL", "Kuala Lumpur", "Malaysia", "MY"),
        "LAX" to EdgeLocation("LAX", "Los Angeles", "United States", "US"),
        "LHR" to EdgeLocation("LHR", "London", "United Kingdom", "GB"),
        "LIM" to EdgeLocation("LIM", "Lima", "Peru", "PE"),
        "LIS" to EdgeLocation("LIS", "Lisbon", "Portugal", "PT"),
        "LON" to EdgeLocation("LON", "London", "United Kingdom", "GB"),
        "MAD" to EdgeLocation("MAD", "Madrid", "Spain", "ES"),
        "MAN" to EdgeLocation("MAN", "Manchester", "United Kingdom", "GB"),
        "MEL" to EdgeLocation("MEL", "Melbourne", "Australia", "AU"),
        "MEX" to EdgeLocation("MEX", "Mexico City", "Mexico", "MX"),
        "MIA" to EdgeLocation("MIA", "Miami", "United States", "US"),
        "MNL" to EdgeLocation("MNL", "Manila", "Philippines", "PH"),
        "MRS" to EdgeLocation("MRS", "Marseille", "France", "FR"),
        "MUC" to EdgeLocation("MUC", "Munich", "Germany", "DE"),
        "NRT" to EdgeLocation("NRT", "Tokyo", "Japan", "JP"),
        "ORD" to EdgeLocation("ORD", "Chicago", "United States", "US"),
        "OSL" to EdgeLocation("OSL", "Oslo", "Norway", "NO"),
        "OTP" to EdgeLocation("OTP", "Bucharest", "Romania", "RO"),
        "PEK" to EdgeLocation("PEK", "Beijing", "China", "CN"),
        "PHX" to EdgeLocation("PHX", "Phoenix", "United States", "US"),
        "PRG" to EdgeLocation("PRG", "Prague", "Czechia", "CZ"),
        "RUH" to EdgeLocation("RUH", "Riyadh", "Saudi Arabia", "SA"),
        "SAN" to EdgeLocation("SAN", "San Diego", "United States", "US"),
        "SEA" to EdgeLocation("SEA", "Seattle", "United States", "US"),
        "SFO" to EdgeLocation("SFO", "San Francisco", "United States", "US"),
        "SIN" to EdgeLocation("SIN", "Singapore", "Singapore", "SG"),
        "SOF" to EdgeLocation("SOF", "Sofia", "Bulgaria", "BG"),
        "SYD" to EdgeLocation("SYD", "Sydney", "Australia", "AU"),
        "TPE" to EdgeLocation("TPE", "Taipei", "Taiwan", "TW"),
        "TLV" to EdgeLocation("TLV", "Tel Aviv", "Israel", "IL"),
        "VIE" to EdgeLocation("VIE", "Vienna", "Austria", "AT"),
        "WAW" to EdgeLocation("WAW", "Warsaw", "Poland", "PL"),
        "YUL" to EdgeLocation("YUL", "Montreal", "Canada", "CA"),
        "YYZ" to EdgeLocation("YYZ", "Toronto", "Canada", "CA"),
        "ZRH" to EdgeLocation("ZRH", "Zurich", "Switzerland", "CH"),
    )

    fun fromColo(raw: String?): EdgeLocation? =
        raw?.trim()?.uppercase()?.let(locations::get)

    fun countryForColo(raw: String?): String? = fromColo(raw)?.countryCode
}
