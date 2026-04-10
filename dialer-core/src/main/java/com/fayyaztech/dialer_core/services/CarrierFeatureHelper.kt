package com.fayyaztech.dialer_core.services

/**
 * Well-known carrier feature codes and GSM supplementary-service codes.
 *
 * These are standardised MMI (Man-Machine Interface) codes that work on most GSM/UMTS/LTE
 * networks. Some are carrier-proprietary and may not work on all operators.
 *
 * The dialer shows matching entries from this list as suggestions when the user types a
 * string that starts with `*` or `#`.
 */
object CarrierFeatureHelper {

    /** A single well-known carrier feature or supplementary-service code. */
    data class FeatureCode(
        val code: String,
        val label: String,
        val description: String,
    )

    private val FEATURE_CODES = listOf(
        // ── Voicemail ──────────────────────────────────────────────────────────
        FeatureCode("*86",   "Voicemail",                "Access your voicemail (common US carriers)"),
        FeatureCode("*98",   "Voicemail (alt)",           "Alternative voicemail access code"),
        FeatureCode("*67",   "Block caller ID",           "Hide your number for this call"),

        // ── Call forwarding (GSM standard) ────────────────────────────────────
        FeatureCode("*21#",  "Activate call forwarding",  "Forward all calls unconditionally"),
        FeatureCode("#21#",  "Deactivate forwarding",     "Cancel unconditional call forwarding"),
        FeatureCode("*#21#", "Check call forwarding",     "Query unconditional forwarding status"),
        FeatureCode("*61#",  "Fwd on no answer",          "Forward calls when not answered"),
        FeatureCode("#61#",  "Disable no-answer fwd",     "Cancel no-answer call forwarding"),
        FeatureCode("*67#",  "Fwd when busy",             "Forward calls when line is busy"),
        FeatureCode("#67#",  "Disable busy forwarding",   "Cancel busy call forwarding"),
        FeatureCode("*62#",  "Fwd when unreachable",      "Forward calls when device is off/no signal"),
        FeatureCode("#62#",  "Disable unreachable fwd",   "Cancel unreachable forwarding"),
        FeatureCode("*#62#", "Check unreachable fwd",     "Query unreachable forwarding status"),
        FeatureCode("**21*", "Set forwarding number",     "Set the number to forward all calls to"),

        // ── Call waiting ──────────────────────────────────────────────────────
        FeatureCode("*43#",  "Enable call waiting",       "Activate the call-waiting service"),
        FeatureCode("#43#",  "Disable call waiting",      "Deactivate the call-waiting service"),
        FeatureCode("*#43#", "Check call waiting",        "Query call-waiting status"),

        // ── Caller ID ─────────────────────────────────────────────────────────
        FeatureCode("*31#",  "Hide caller ID (always)",   "Permanently suppress your number"),
        FeatureCode("#31#",  "Show caller ID (always)",   "Unblock your number permanently"),

        // ── Device info ───────────────────────────────────────────────────────
        FeatureCode("*#06#", "Show IMEI",                 "Display the device IMEI number"),
        FeatureCode("*#0#",  "Hardware test menu",        "OEM diagnostic screen (Samsung etc.)"),

        // ── Carrier balance & usage (varies by carrier) ───────────────────────
        FeatureCode("*646#", "Data / minute balance",     "Check remaining plan usage (AT&T)"),
        FeatureCode("*225#", "Account balance",           "Check account balance (T-Mobile/Sprint)"),
        FeatureCode("*3282#","Data usage",                "Check remaining data (AT&T alternative)"),
        FeatureCode("*611",  "Customer support",          "Call carrier customer support line"),
        FeatureCode("*72",   "Call forward setup",        "Activate call forwarding (CDMA)"),
        FeatureCode("*73",   "Cancel call forward",       "Disable call forwarding (CDMA)"),

        // ── Network info ──────────────────────────────────────────────────────
        FeatureCode("*#*#4636#*#*", "Phone info",         "Android built-in phone info screen"),
        FeatureCode("*#*#0*#*#*",   "Display test",       "Screen colour test (Samsung)"),
    )

    /**
     * Returns all [FeatureCode]s whose [FeatureCode.code] starts with [prefix].
     *
     * Returns an empty list when [prefix] is blank or does not begin with `*` or `#`.
     */
    fun getSuggestions(prefix: String): List<FeatureCode> {
        if (prefix.isBlank()) return emptyList()
        if (!prefix.startsWith("*") && !prefix.startsWith("#")) return emptyList()
        return FEATURE_CODES.filter { it.code.startsWith(prefix, ignoreCase = false) }
            .distinctBy { it.code }
    }
}
