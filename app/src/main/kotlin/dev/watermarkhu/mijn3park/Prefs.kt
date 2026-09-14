package dev.watermarkhu.mijn3park

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import java.io.File

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
        } catch (e: Exception) {
            // Keystore key or backing file became unreadable (e.g. corruption):
            // discard and recreate so the app stays usable. Requires re-login.
            deletePrefs(context)
            buildEncrypted(context, masterKey)
        }
    }

    private fun deletePrefs(context: Context) {
        if (Build.VERSION.SDK_INT >= 24) {
            context.deleteSharedPreferences(PREFS_NAME)
        } else {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit()
            File(File(context.applicationInfo.dataDir, "shared_prefs"), "$PREFS_NAME.xml").delete()
        }
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

    /**
     * Planned parking end, epoch millis. 0 means none: parking runs
     * open-ended (with the usual midnight renewals) until stopped manually.
     */
    var activeEndAt: Long
        get() = prefs.getLong("active_end_at", 0L)
        set(value) = prefs.edit().putLong("active_end_at", value).apply()

    val isParking: Boolean
        get() = activePlate.isNotBlank()

    fun clearActiveParking() {
        prefs.edit().remove("active_plate").remove("active_since").remove("active_end_at").apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
