package dev.watermarkhu.mijn3park

import java.text.SimpleDateFormat
import java.util.Locale

private val SERVER_TIME_WITH_SECONDS = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.ROOT)
private val SERVER_TIME_WITHOUT_SECONDS = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.ROOT)

/** Format a 2Park `dd-MM-yyyy HH:mm[:ss]` timestamp for display, else return it as-is. */
fun prettyTime(raw: String?): String {
    if (raw.isNullOrBlank()) return "—"
    val parsed = try {
        SERVER_TIME_WITH_SECONDS.parse(raw)
    } catch (_: Exception) {
        null
    } ?: try {
        SERVER_TIME_WITHOUT_SECONDS.parse(raw)
    } catch (_: Exception) {
        null
    } ?: return raw
    return SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(parsed)
}

/** Build a display amount from a raw amount and unit ("€", "#", "Minuut"). */
fun formatAmount(amount: String?, unit: String?): String? {
    if (amount.isNullOrBlank()) return null
    return when (unit) {
        null, "", "€" -> "€ " + amount.replace('.', ',')
        "#" -> amount
        "Minuut" -> "$amount min"
        else -> "$unit $amount"
    }
}
