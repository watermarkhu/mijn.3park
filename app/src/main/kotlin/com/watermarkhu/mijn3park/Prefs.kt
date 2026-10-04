package com.watermarkhu.mijn3park

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * A future parking session with absolute timestamps, merged from the server's
 * same-day legs. Persisted locally so notifications can be scheduled without
 * re-contacting the API; refreshed whenever the app opens.
 */
data class PlannedSession(val plate: String, val startAt: Long, val endAt: Long)

/**
 * App state persisted in AES256-GCM [EncryptedSharedPreferences], backed by a
 * key in the Android Keystore. Protects the stored account credentials at rest.
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    private fun createPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return try {
            buildEncrypted(context, masterKey)
        } catch (_: Exception) {
            // Keystore key or backing file became unreadable (e.g. corruption):
            // discard and recreate so the app stays usable. Requires re-login.
            deletePrefs(context)
            buildEncrypted(context, masterKey)
        }
    }

    private fun deletePrefs(context: Context) {
        context.deleteSharedPreferences(PREFS_NAME)
    }

    private fun buildEncrypted(context: Context, masterKey: MasterKey): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private companion object {
        const val PREFS_NAME = "mijn3park_secure"
        const val KEY_PLANNED_BY_PRODUCT = "planned_sessions_by_product"
        const val KEY_PLANNED_LEGACY = "planned_sessions"
    }

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

    var productCategoryId: String
        get() = prefs.getString("product_category_id", "") ?: ""
        set(value) = prefs.edit().putString("product_category_id", value).apply()

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
        } catch (_: Exception) {
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

    /**
     * Planned parking end, epoch millis. 0 means none: parking runs
     * open-ended (with the usual midnight renewals) until stopped manually.
     */
    var activeEndAt: Long
        get() = prefs.getLong("active_end_at", 0L)
        set(value) = prefs.edit().putLong("active_end_at", value).apply()

    /**
     * True while the running session was auto-started from a planned session, so
     * the ongoing notification can say so.
     */
    var activeFromPlan: Boolean
        get() = prefs.getBoolean("active_from_plan", false)
        set(value) = prefs.edit().putBoolean("active_from_plan", value).apply()

    val isParking: Boolean
        get() = activePlate.isNotBlank()

    /**
     * Internal planned-session state, keyed by product id. Refreshed from the
     * server whenever the app opens (and when the Planned tab is shown), so
     * externally added or removed sessions converge. Also keeps sessions that
     * already started but have not ended yet, so their end (and reminder) alarms
     * survive a server-side auto-start.
     */
    var plannedSessionsByProduct: Map<String, List<PlannedSession>>
        get() {
            val raw = prefs.getString(KEY_PLANNED_BY_PRODUCT, null)
                ?: return migrateLegacyPlannedSessions()
            return try {
                val obj = JSONObject(raw)
                buildMap {
                    for (productId in obj.keys()) {
                        put(productId, parsePlannedSessions(obj.optJSONArray(productId)))
                    }
                }
            } catch (_: Exception) {
                emptyMap()
            }
        }
        set(value) {
            val obj = JSONObject()
            value.forEach { (productId, sessions) ->
                obj.put(productId, plannedSessionsJson(sessions))
            }
            prefs.edit { putString(KEY_PLANNED_BY_PRODUCT, obj.toString()) }
        }

    /** Planned sessions recorded for [productId] (empty when none). */
    fun plannedSessions(productId: String): List<PlannedSession> =
        plannedSessionsByProduct[productId].orEmpty()

    /** Replace the stored planned sessions for [productId]. */
    fun setPlannedSessions(productId: String, sessions: List<PlannedSession>) {
        val updated = plannedSessionsByProduct.toMutableMap()
        if (sessions.isEmpty()) updated.remove(productId) else updated[productId] = sessions
        plannedSessionsByProduct = updated
    }

    /** Every recorded planned session, across all products. */
    fun allPlannedSessions(): List<PlannedSession> =
        plannedSessionsByProduct.values.flatten()

    fun clearPlannedSessions() {
        prefs.edit {
            remove(KEY_PLANNED_BY_PRODUCT)
            remove(KEY_PLANNED_LEGACY)
        }
    }

    /** One-time upgrade of the pre-per-product single list. */
    private fun migrateLegacyPlannedSessions(): Map<String, List<PlannedSession>> {
        val raw = prefs.getString(KEY_PLANNED_LEGACY, null) ?: return emptyMap()
        val pid = productId
        if (pid.isBlank()) return emptyMap()
        val sessions = try {
            parsePlannedSessions(JSONArray(raw))
        } catch (_: Exception) {
            emptyList()
        }
        if (sessions.isEmpty()) return emptyMap()
        val migrated = mapOf(pid to sessions)
        plannedSessionsByProduct = migrated
        prefs.edit { remove(KEY_PLANNED_LEGACY) }
        return migrated
    }

    private fun parsePlannedSessions(array: JSONArray?): List<PlannedSession> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val plate = obj.optString("plate")
            if (plate.isBlank()) return@mapNotNull null
            PlannedSession(plate, obj.optLong("start"), obj.optLong("end"))
        }
    }

    private fun plannedSessionsJson(sessions: List<PlannedSession>): JSONArray {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(
                JSONObject().apply {
                    put("plate", session.plate)
                    put("start", session.startAt)
                    put("end", session.endAt)
                },
            )
        }
        return array
    }

    /** Reminder cadence in minutes while a session is active; 0 means off. */
    var reminderIntervalMinutes: Int
        get() = prefs.getInt("reminder_interval_minutes", 0)
        set(value) = prefs.edit { putInt("reminder_interval_minutes", value) }

    /** Dedupe key ("plate|startAt") of the last posted session-started event. */
    var lastSessionEventKey: String
        get() = prefs.getString("last_session_event_key", "") ?: ""
        set(value) = prefs.edit { putString("last_session_event_key", value) }

    fun clearActiveParking() {
        prefs.edit {
            remove("active_plate")
            remove("active_since")
            remove("active_end_at")
            remove("active_from_plan")
        }
    }

    fun clearAll() {
        prefs.edit { clear() }
    }
}
