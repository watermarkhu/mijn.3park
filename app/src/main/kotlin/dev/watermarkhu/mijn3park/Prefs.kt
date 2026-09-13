package dev.watermarkhu.mijn3park

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** Simple app state persisted in private SharedPreferences. */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("mijn3park", Context.MODE_PRIVATE)

    var email: String
        get() = prefs.getString("email", "") ?: ""
        set(value) = prefs.edit().putString("email", value).apply()

    var password: String
        get() = prefs.getString("password", "") ?: ""
        set(value) = prefs.edit().putString("password", value).apply()

    val hasCredentials: Boolean
        get() = email.isNotBlank() && password.isNotBlank()

    var productId: String
        get() = prefs.getString("product_id", "") ?: ""
        set(value) = prefs.edit().putString("product_id", value).apply()

    var productName: String
        get() = prefs.getString("product_name", "") ?: ""
        set(value) = prefs.edit().putString("product_name", value).apply()

    var productLocation: String
        get() = prefs.getString("product_location", "") ?: ""
        set(value) = prefs.edit().putString("product_location", value).apply()

    /** Product preselected at app start; empty means "last used". */
    var defaultProductId: String
        get() = prefs.getString("default_product_id", "") ?: ""
        set(value) = prefs.edit().putString("default_product_id", value).apply()

    /** Locally saved plates, most recently used first. */
    var savedPlates: List<String>
        get() {
            val raw = prefs.getString("saved_plates", "[]") ?: "[]"
            return try {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { array.optString(it).takeIf { p -> p.isNotBlank() } }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val array = JSONArray()
            value.forEach { array.put(it) }
            prefs.edit().putString("saved_plates", array.toString()).apply()
        }

    fun rememberPlate(plate: String) {
        val normalized = normalizePlate(plate)
        savedPlates = (listOf(normalized) + savedPlates.filter { it != normalized }).take(10)
    }

    /** Last known account balance, formatted for display (e.g. "€ 12,34"). */
    var lastBalance: String
        get() = prefs.getString("last_balance", "") ?: ""
        set(value) = prefs.edit().putString("last_balance", value).apply()

    // Active parking session state, kept in sync by ParkingService.

    var activePlate: String
        get() = prefs.getString("active_plate", "") ?: ""
        set(value) = prefs.edit().putString("active_plate", value).apply()

    var activeSince: Long
        get() = prefs.getLong("active_since", 0L)
        set(value) = prefs.edit().putLong("active_since", value).apply()

    val isParking: Boolean
        get() = activePlate.isNotBlank()

    fun clearActiveParking() {
        prefs.edit().remove("active_plate").remove("active_since").apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
