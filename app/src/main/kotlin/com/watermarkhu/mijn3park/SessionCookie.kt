package com.watermarkhu.mijn3park

/**
 * Serialisation of the 2Park session cookie for [Prefs] — the app's "refresh
 * token".
 *
 * 2Park authenticates with a `JSESSIONID` cookie; there is no OAuth-style
 * refresh token, so that cookie *is* the session. Keeping it lets a cold start
 * talk to the API without sending the password again (and without burning
 * another login attempt, which 2Park rate-limits).
 *
 * It lives in the AES256-GCM encrypted preferences, never in plain text.
 */
internal object SessionCookie {

    /** `name=value; name2=value2`, skipping nameless entries. */
    fun encode(cookies: List<Pair<String, String>>): String =
        cookies
            .filter { (name, _) -> name.isNotBlank() }
            .joinToString("; ") { (name, value) -> "$name=$value" }

    /** Parse [encode]'s output; malformed entries are dropped. */
    fun decode(raw: String): List<Pair<String, String>> =
        raw.split(';').mapNotNull { part ->
            val entry = part.trim()
            val separator = entry.indexOf('=')
            if (separator <= 0) {
                null
            } else {
                entry.substring(0, separator) to entry.substring(separator + 1)
            }
        }
}
