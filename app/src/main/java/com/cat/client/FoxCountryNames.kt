package com.cat.client

import java.util.Locale

/**
 * Names and flags for Cat Fox VPN country codes. Builtin nodes have their own
 * titles; this fills in countries Mozilla adds later on `vpn-serverlist`.
 * Public ISO 3166 names — not copied from another client.
 */
internal object FoxCountryNames {
    data class Names(val fa: String, val en: String, val flag: String)

    fun of(code: String, fallbackEn: String = "", city: String = ""): Names {
        val c = code.trim().uppercase(Locale.US)
        TABLE[c]?.let { row ->
            val en = if (city.isNotBlank() && !row.en.contains(city, ignoreCase = true)) {
                "${row.en} ($city)"
            } else row.en
            val fa = if (city.isNotBlank() && !row.fa.contains(city)) {
                "${row.fa} ($city)"
            } else row.fa
            return Names(fa, en, row.flag)
        }
        val en = fallbackEn.ifBlank { c }.let { if (city.isNotBlank()) "$it ($city)" else it }
        return Names(en, en, flagEmoji(c))
    }

    fun flagEmoji(code: String): String {
        val c = code.trim().uppercase(Locale.US)
        if (c == "REC") return "⭐"
        if (c.length != 2) return "🌍"
        val a = c[0].code - 'A'.code + 0x1F1E6
        val b = c[1].code - 'A'.code + 0x1F1E6
        if (a !in 0x1F1E6..0x1F1FF || b !in 0x1F1E6..0x1F1FF) return "🌍"
        return String(intArrayOf(a, b), 0, 2)
    }

    private val TABLE: Map<String, Names> = mapOf(
        "REC" to Names("پیشنهاد موزیلا (نزدیک‌ترین سرور)", "Mozilla Recommended", "⭐"),
        "DE" to Names("آلمان", "Germany", "🇩🇪"),
        "NL" to Names("هلند", "Netherlands", "🇳🇱"),
        "GB" to Names("انگلستان", "United Kingdom", "🇬🇧"),
        "US" to Names("آمریکا", "United States", "🇺🇸"),
        "FR" to Names("فرانسه", "France", "🇫🇷"),
        "SE" to Names("سوئد", "Sweden", "🇸🇪"),
        "AT" to Names("اتریش", "Austria", "🇦🇹"),
        "FI" to Names("فنلاند", "Finland", "🇫🇮"),
        "CA" to Names("کانادا", "Canada", "🇨🇦"),
        "SG" to Names("سنگاپور", "Singapore", "🇸🇬"),
        "JP" to Names("ژاپن", "Japan", "🇯🇵"),
        "IT" to Names("ایتالیا", "Italy", "🇮🇹"),
        "ES" to Names("اسپانیا", "Spain", "🇪🇸"),
        "BE" to Names("بلژیک", "Belgium", "🇧🇪"),
        "DK" to Names("دانمارک", "Denmark", "🇩🇰"),
        "NO" to Names("نروژ", "Norway", "🇳🇴"),
        "IE" to Names("ایرلند", "Ireland", "🇮🇪"),
        "BG" to Names("بلغارستان", "Bulgaria", "🇧🇬"),
        "PT" to Names("پرتغال", "Portugal", "🇵🇹"),
        "AU" to Names("استرالیا", "Australia", "🇦🇺"),
        "NZ" to Names("نیوزیلند", "New Zealand", "🇳🇿"),
        "MX" to Names("مکزیک", "Mexico", "🇲🇽"),
        "BR" to Names("برزیل", "Brazil", "🇧🇷"),
        "AR" to Names("آرژانتین", "Argentina", "🇦🇷"),
        "CL" to Names("شیلی", "Chile", "🇨🇱"),
        "CO" to Names("کلمبیا", "Colombia", "🇨🇴"),
        "PE" to Names("پرو", "Peru", "🇵🇪"),
        "ZA" to Names("آفریقای جنوبی", "South Africa", "🇿🇦"),
        "GH" to Names("غنا", "Ghana", "🇬🇭"),
        "KR" to Names("کره جنوبی", "South Korea", "🇰🇷"),
        "MY" to Names("مالزی", "Malaysia", "🇲🇾"),
        "PH" to Names("فیلیپین", "Philippines", "🇵🇭"),
        "TH" to Names("تایلند", "Thailand", "🇹🇭"),
        "CH" to Names("سوئیس", "Switzerland", "🇨🇭"),
        "PL" to Names("لهستان", "Poland", "🇵🇱"),
        "GR" to Names("یونان", "Greece", "🇬🇷"),
        "CZ" to Names("چک", "Czechia", "🇨🇿"),
        "RO" to Names("رومانی", "Romania", "🇷🇴"),
        "HU" to Names("مجارستان", "Hungary", "🇭🇺"),
        "SK" to Names("اسلواکی", "Slovakia", "🇸🇰"),
        "HR" to Names("کرواسی", "Croatia", "🇭🇷"),
        "RS" to Names("صربستان", "Serbia", "🇷🇸"),
        "EE" to Names("استونی", "Estonia", "🇪🇪"),
        "LV" to Names("لتونی", "Latvia", "🇱🇻"),
        "LT" to Names("لیتوانی", "Lithuania", "🇱🇹"),
        "IS" to Names("ایسلند", "Iceland", "🇮🇸"),
        "LU" to Names("لوکزامبورگ", "Luxembourg", "🇱🇺"),
        "UA" to Names("اوکراین", "Ukraine", "🇺🇦"),
        "TR" to Names("ترکیه", "Türkiye", "🇹🇷"),
        "IN" to Names("هند", "India", "🇮🇳"),
        "ID" to Names("اندونزی", "Indonesia", "🇮🇩"),
        "VN" to Names("ویتنام", "Vietnam", "🇻🇳"),
        "TW" to Names("تایوان", "Taiwan", "🇹🇼"),
        "HK" to Names("هنگ‌کنگ", "Hong Kong", "🇭🇰"),
        "AE" to Names("امارات", "United Arab Emirates", "🇦🇪"),
        "IL" to Names("اسرائیل", "Israel", "🇮🇱"),
        "EG" to Names("مصر", "Egypt", "🇪🇬"),
        "NG" to Names("نیجریه", "Nigeria", "🇳🇬"),
        "KE" to Names("کنیا", "Kenya", "🇰🇪"),
        "MA" to Names("مراکش", "Morocco", "🇲🇦"),
        "PK" to Names("پاکستان", "Pakistan", "🇵🇰"),
        "BD" to Names("بنگلادش", "Bangladesh", "🇧🇩"),
        "KZ" to Names("قزاقستان", "Kazakhstan", "🇰🇿"),
        "GE" to Names("گرجستان", "Georgia", "🇬🇪"),
        "AM" to Names("ارمنستان", "Armenia", "🇦🇲"),
        "AZ" to Names("آذربایجان", "Azerbaijan", "🇦🇿"),
        "UY" to Names("اروگوئه", "Uruguay", "🇺🇾"),
        "PY" to Names("پاراگوئه", "Paraguay", "🇵🇾"),
        "EC" to Names("اکوادور", "Ecuador", "🇪🇨"),
        "CR" to Names("کاستاریکا", "Costa Rica", "🇨🇷"),
        "PA" to Names("پاناما", "Panama", "🇵🇦"),
        "QA" to Names("قطر", "Qatar", "🇶🇦"),
        "SA" to Names("عربستان", "Saudi Arabia", "🇸🇦"),
        "KW" to Names("کویت", "Kuwait", "🇰🇼"),
        "BH" to Names("بحرین", "Bahrain", "🇧🇭"),
        "OM" to Names("عمان", "Oman", "🇴🇲"),
        "JO" to Names("اردن", "Jordan", "🇯🇴"),
        "LB" to Names("لبنان", "Lebanon", "🇱🇧"),
        "IQ" to Names("عراق", "Iraq", "🇮🇶"),
        "IR" to Names("ایران", "Iran", "🇮🇷"),
        "CN" to Names("چین", "China", "🇨🇳"),
        "RU" to Names("روسیه", "Russia", "🇷🇺"),
        "BY" to Names("بلاروس", "Belarus", "🇧🇾"),
        "MD" to Names("مولداوی", "Moldova", "🇲🇩"),
        "SI" to Names("اسلوونی", "Slovenia", "🇸🇮"),
        "BA" to Names("بوسنی", "Bosnia & Herzegovina", "🇧🇦"),
        "MK" to Names("مقدونیه شمالی", "North Macedonia", "🇲🇰"),
        "AL" to Names("آلبانی", "Albania", "🇦🇱"),
        "MT" to Names("مالت", "Malta", "🇲🇹"),
        "CY" to Names("قبرس", "Cyprus", "🇨🇾"),
        "LI" to Names("لیختن‌اشتاین", "Liechtenstein", "🇱🇮"),
        "MC" to Names("موناکو", "Monaco", "🇲🇨"),
        "AD" to Names("آندورا", "Andorra", "🇦🇩"),
        "SM" to Names("سان‌مارینو", "San Marino", "🇸🇲"),
        "VA" to Names("واتیکان", "Vatican City", "🇻🇦"),
        "LK" to Names("سری‌لانکا", "Sri Lanka", "🇱🇰"),
        "NP" to Names("نپال", "Nepal", "🇳🇵"),
        "KH" to Names("کامبوج", "Cambodia", "🇰🇭"),
        "MM" to Names("میانمار", "Myanmar", "🇲🇲"),
        "LA" to Names("لائوس", "Laos", "🇱🇦"),
        "BN" to Names("برونئی", "Brunei", "🇧🇳"),
        "MN" to Names("مغولستان", "Mongolia", "🇲🇳"),
        "MO" to Names("ماکائو", "Macao", "🇲🇴"),
        "FJ" to Names("فیجی", "Fiji", "🇫🇯"),
        "PG" to Names("پاپوآ گینه نو", "Papua New Guinea", "🇵🇬"),
        "TZ" to Names("تانزانیا", "Tanzania", "🇹🇿"),
        "UG" to Names("اوگاندا", "Uganda", "🇺🇬"),
        "ET" to Names("اتیوپی", "Ethiopia", "🇪🇹"),
        "SN" to Names("سنگال", "Senegal", "🇸🇳"),
        "CI" to Names("ساحل عاج", "Côte d’Ivoire", "🇨🇮"),
        "TN" to Names("تونس", "Tunisia", "🇹🇳"),
        "DZ" to Names("الجزایر", "Algeria", "🇩🇿"),
        "LY" to Names("لیبی", "Libya", "🇱🇾"),
        "SD" to Names("سودان", "Sudan", "🇸🇩"),
        "AO" to Names("آنگولا", "Angola", "🇦🇴"),
        "MZ" to Names("موزامبیک", "Mozambique", "🇲🇿"),
        "ZW" to Names("زیمبابوه", "Zimbabwe", "🇿🇼"),
        "BW" to Names("بوتسوانا", "Botswana", "🇧🇼"),
        "NA" to Names("نامیبیا", "Namibia", "🇳🇦"),
        "MU" to Names("موریس", "Mauritius", "🇲🇺"),
        "RE" to Names("رئونیون", "Réunion", "🇷🇪"),
        "PR" to Names("پورتوریکو", "Puerto Rico", "🇵🇷"),
        "DO" to Names("جمهوری دومینیکن", "Dominican Republic", "🇩🇴"),
        "GT" to Names("گواتمالا", "Guatemala", "🇬🇹"),
        "HN" to Names("هندوراس", "Honduras", "🇭🇳"),
        "SV" to Names("السالوادور", "El Salvador", "🇸🇻"),
        "NI" to Names("نیکاراگوئه", "Nicaragua", "🇳🇮"),
        "BO" to Names("بولیوی", "Bolivia", "🇧🇴"),
        "VE" to Names("ونزوئلا", "Venezuela", "🇻🇪"),
        "GY" to Names("گویانا", "Guyana", "🇬🇾"),
        "JM" to Names("جامائیکا", "Jamaica", "🇯🇲"),
        "TT" to Names("ترینیداد", "Trinidad & Tobago", "🇹🇹"),
        "CU" to Names("کوبا", "Cuba", "🇨🇺"),
        "BS" to Names("باهاما", "Bahamas", "🇧🇸"),
    )
}
