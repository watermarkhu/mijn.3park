package com.watermarkhu.mijn3park

/**
 * Percent-encoding that mirrors JavaScript's `encodeURI`.
 *
 * 2Park's own web client builds the login body by concatenating the values and
 * running `encodeURI()` over the whole string:
 *
 * ```
 * params: "email=" + email + "&password=" + password + "&locale=" + locale
 * ...
 * body = encodeURI(params)
 * ```
 *
 * `encodeURI` leaves reserved characters such as `&` and `+` untouched, so the
 * server's form parser treats them as syntax: a `+` in the password decodes to
 * a space and a `&` ends the field, truncating the password. Passwords are set
 * through that same client, so the server has stored the "deformed" value.
 *
 * A standards-compliant body (OkHttp's `FormBody`, which sends `%2B` / `%26`)
 * therefore sends different credentials than the website does and is rejected
 * with `INVALID_CREDS`. This encoder reproduces the website byte for byte.
 * It is used for the login request only; every other endpoint is form-encoded
 * normally (product IDs contain characters like `$` that must be escaped).
 */
object WebForm {

    /**
     * Characters JavaScript's `encodeURI` leaves alone:
     * unreserved (`A-Z a-z 0-9 - _ . ! ~ * ' ( )`) plus the reserved set
     * (`; / ? : @ & = + $ , #`).
     */
    private const val UNESCAPED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" +
            "-_.!~*'();/?:@&=+$,#"

    private const val HEX = "0123456789ABCDEF"

    /** UTF-8 percent-encode [value] the way `encodeURI` would. */
    fun encode(value: String): String {
        val out = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            if (code < 0x80 && UNESCAPED.indexOf(code.toChar()) >= 0) {
                out.append(code.toChar())
            } else {
                out.append('%')
                out.append(HEX[code shr 4])
                out.append(HEX[code and 0x0F])
            }
        }
        return out.toString()
    }

    /** Build `key=value&key=value…` with [encode] applied to keys and values. */
    fun body(vararg fields: Pair<String, String>): String =
        fields.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }

    /**
     * True when [encode] makes the server see a different value than OkHttp's
     * RFC form encoding does: only `&` (field separator, truncates the value)
     * and `+` (decoded as a space). For every other character the two encodings
     * decode to the same string, so no fallback attempt is needed.
     */
    fun differsFromFormEncoding(value: String): Boolean =
        value.any { it == '&' || it == '+' }
}
