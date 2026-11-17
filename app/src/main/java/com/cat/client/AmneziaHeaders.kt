package com.cat.client

/**
 * AmneziaWG H1-H4 header values. They are uint32 (often larger than Int.MAX_VALUE) and AmneziaWG
 * 2.0 also allows ranges ("a-b"); mihomo accepts both. Returns a Long for a single value, the
 * normalized "a-b" string for a range, or null for anything invalid.
 */
internal object AmneziaHeaders {
    fun value(raw: String?): Any? {
        val value = raw?.trim()?.replace(" ", "").orEmpty()
        if (value.isEmpty()) return null
        Regex("""^(\d{1,10})(?:-(\d{1,10}))?$""").matchEntire(value)?.let { m ->
            val start = m.groupValues[1].toLong()
            val end = m.groupValues[2].takeIf(String::isNotEmpty)?.toLong()
            if (start > 0xFFFFFFFFL || (end != null && (end > 0xFFFFFFFFL || end < start))) return null
            return if (end == null) start else value
        }
        return null
    }
}
