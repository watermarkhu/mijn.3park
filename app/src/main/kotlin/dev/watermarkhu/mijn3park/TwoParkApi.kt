package dev.watermarkhu.mijn3park

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class TwoParkException(message: String) : Exception(message)

fun normalizePlate(plate: String): String =
    plate.trim().uppercase(Locale.ROOT).replace("-", "").replace(" ", "")

data class Product(
    val id: String,
    val name: String,
    val category: String,
    val location: String?,
    val options: String = "",
    val categoryId: String = "",
) {
    val displayName: String
        get() = if (category.isNotBlank() && category != name) "$name ($category)" else name

    /** FLPN products have a fixed license plate bound to the permit. */
    val hasFixedPlate: Boolean
        get() = options.split("|").contains("FLPN")
}

data class Member(
    val plate: String,
    val nickname: String?,
    val active: Boolean,
    val actionId: String?,
    val timeStart: String?,
    val timeEnd: String?,
)

data class ProductDetails(
    val members: List<Member>,
    /** Fixed plate bound to the permit (FLPN products), normalized. */
    val fixedPlate: String?,
    /**
     * True when the fixed plate is currently covered, i.e. no temporary
     * plate override (LPN action) is active. Mirrors the web app logic.
     */
    val fixedPlateActive: Boolean,
)

data class TopupForward(
    val url: String,
    val method: String,
    val parameters: List<Pair<String, String>>,
)

data class Balance(
    val amount: Double?,
    val currency: String,
    val lastModified: String?,
) {
    val formatted: String
        get() = amount?.let { String.format(Locale("nl", "NL"), "%s %.2f", currency, it) } ?: "—"
}

/**
 * Async client for the undocumented mijn.2park.nl web endpoints.
 *
 * Session state is cookie based; the in-memory cookie jar lives as long as the
 * process, and every call transparently re-authenticates when the session has
 * expired.
 */
class TwoParkApi {

    companion object {
        const val BASE_URL = "https://mijn.2park.nl"
        const val LOCALE = "nl_NL"
        private val TIME_FORMAT = "dd-MM-yyyy HH:mm:ss"
        private val DATE_FORMAT = "dd-MM-yyyy"

        // Shared instance so MainActivity and ParkingService reuse one session.
        val instance: TwoParkApi by lazy { TwoParkApi() }

        fun nowTimestamp(): String =
            SimpleDateFormat(TIME_FORMAT, Locale.ROOT).format(Date())

        fun endOfTodayTimestamp(): String =
            SimpleDateFormat(DATE_FORMAT, Locale.ROOT).format(Date()) + " 23:59:59"
    }

    private val cookieStore = mutableMapOf<String, List<Cookie>>()

    private val client = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                synchronized(cookieStore) {
                    val existing = cookieStore[url.host].orEmpty()
                        .filter { old -> cookies.none { it.name == old.name } }
                    cookieStore[url.host] = existing + cookies
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                synchronized(cookieStore) { cookieStore[url.host].orEmpty() }
        })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Shares the cookie jar but does not auto-follow redirects, so the payment
    // provider's redirect URL can be captured and opened in the system browser.
    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val loginMutex = Mutex()

    @Volatile
    private var loggedIn = false

    var email: String = ""
    var password: String = ""

    private suspend fun postForm(endpoint: String, fields: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val url = "$BASE_URL/gsmpark-app-www/json/$endpoint"
            val body = FormBody.Builder().apply {
                fields.forEach { (k, v) -> add(k, v) }
            }.build()
            val request = Request.Builder()
                .url(url)
                .post(body)
                .header("Accept", "*/*")
                .header("Origin", BASE_URL)
                .header("Referer", "$BASE_URL/")
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw TwoParkException("HTTP ${response.code} for $endpoint")
                }
                val text = response.body?.string()
                    ?: throw TwoParkException("Empty response from $endpoint")
                try {
                    JSONObject(text)
                } catch (e: Exception) {
                    throw TwoParkException("Invalid JSON from $endpoint")
                }
            }
        }

    private fun assertOk(payload: JSONObject, expectedMinor: String? = null) {
        val status = payload.optJSONObject("status") ?: JSONObject()
        val code = status.optJSONObject("code") ?: JSONObject()
        val major = code.optString("major")
        val minor = code.optString("minor")
        val message = status.optString("message")

        if (major != "OK") {
            throw TwoParkException("2Park error: major=$major, minor=$minor, message=$message")
        }
        if (expectedMinor != null && minor != expectedMinor) {
            throw TwoParkException("Unexpected 2Park status: expected $expectedMinor, got $minor")
        }
    }

    suspend fun login(email: String, password: String) {
        this.email = email
        this.password = password
        loginMutex.withLock { doLogin() }
    }

    private suspend fun doLogin() {
        if (email.isBlank() || password.isBlank()) {
            throw TwoParkException("No credentials configured")
        }
        val payload = postForm(
            "check_credentials.json",
            mapOf("email" to email, "password" to password, "locale" to LOCALE),
        )
        assertOk(payload, expectedMinor = "AUTHENTICATED")
        loggedIn = true
    }

    private suspend fun ensureLoggedIn() {
        if (loggedIn) return
        loginMutex.withLock {
            if (!loggedIn) doLogin()
        }
    }

    /** Run [block]; on failure assume the session expired, re-login and retry once. */
    private suspend fun <T> withAuthRetry(block: suspend () -> T): T {
        ensureLoggedIn()
        return try {
            block()
        } catch (e: TwoParkException) {
            loggedIn = false
            ensureLoggedIn()
            block()
        }
    }

    private fun findDefaultLocation(product: JSONObject): String? {
        val groups = product.optJSONArray("pdt_parameter_groups") ?: return null
        for (i in 0 until groups.length()) {
            val group = groups.optJSONObject(i) ?: continue
            if (group.optString("pgp_label") != "START") continue
            val params = group.optJSONArray("pgp_parameters") ?: continue
            for (j in 0 until params.length()) {
                val param = params.optJSONObject(j) ?: continue
                if (param.optString("prr_label") == "LOCATION") {
                    return param.optString("prr_default_value").takeIf { it.isNotBlank() }
                }
            }
        }
        return null
    }

    suspend fun getProducts(): List<Product> = withAuthRetry {
        val payload = postForm("get_categories.json", mapOf("locale" to LOCALE))
        assertOk(payload, expectedMinor = "SUCCESS")

        val products = mutableListOf<Product>()
        val categories = payload.optJSONObject("data")?.optJSONArray("categories") ?: JSONArray()
        for (i in 0 until categories.length()) {
            val category = categories.optJSONObject(i) ?: continue
            val categoryName = category.optString("cty_name")
            val categoryId = category.optString("cty_id")
            val ctyProducts = category.optJSONArray("cty_products") ?: continue
            for (j in 0 until ctyProducts.length()) {
                val product = ctyProducts.optJSONObject(j) ?: continue
                if (product.optString("pdt_is_blocked") == "true") continue
                val id = product.optString("pdt_id")
                if (id.isBlank()) continue
                products.add(
                    Product(
                        id = id,
                        name = product.optString("pdt_name"),
                        category = categoryName,
                        location = findDefaultLocation(product),
                        options = product.optString("pdt_options"),
                        categoryId = categoryId,
                    )
                )
            }
        }
        if (products.isEmpty()) throw TwoParkException("No usable 2Park product found")
        products
    }

    private suspend fun getProductDetails(productId: String): JSONObject = withAuthRetry {
        val payload = postForm(
            "get_category_product_details.json",
            mapOf("product_id" to productId, "locale" to LOCALE),
        )
        assertOk(payload, expectedMinor = "SUCCESS")
        payload
    }

    private fun extractParam(params: JSONArray?, label: String): String? {
        if (params == null) return null
        for (i in 0 until params.length()) {
            val param = params.optJSONObject(i) ?: continue
            if (param.optString("prr_label") == label) {
                return param.optString("prr_value").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun extractActiveAction(member: JSONObject): JSONObject? {
        val actions = member.optJSONArray("mbr_actions") ?: return null
        for (i in 0 until actions.length()) {
            val action = actions.optJSONObject(i) ?: continue
            if (action.optString("atn_state") == "ACTIVE") return action
        }
        return null
    }

    suspend fun getDetails(productId: String): ProductDetails {
        val payload = getProductDetails(productId)
        val data = payload.optJSONObject("data") ?: JSONObject()

        val members = mutableListOf<Member>()
        val rawMembers = data.optJSONArray("pdt_members") ?: JSONArray()
        for (i in 0 until rawMembers.length()) {
            val member = rawMembers.optJSONObject(i) ?: continue
            val action = extractActiveAction(member)
            members.add(
                Member(
                    plate = normalizePlate(member.optString("mbr_identifier")),
                    nickname = extractParam(member.optJSONArray("mbr_parameters"), "NICKNAME"),
                    active = member.optString("mbr_active") == "YES",
                    actionId = action?.optString("atn_id")?.takeIf { it.isNotBlank() },
                    timeStart = extractParam(action?.optJSONArray("atn_parameters"), "TIMESTART"),
                    timeEnd = extractParam(action?.optJSONArray("atn_parameters"), "TIMEEND"),
                )
            )
        }

        // FLPN products list the permit's fixed plate under pdt_identifications:
        // the idn_member with mbr_type "FLPN" is the fixed plate, and it is
        // covered whenever no LPN member of the identification is active.
        var fixedPlate: String? = null
        var overrideActive = false
        val identifications = data.optJSONArray("pdt_identifications") ?: JSONArray()
        for (i in 0 until identifications.length()) {
            val idnMembers = identifications.optJSONObject(i)
                ?.optJSONArray("idn_members") ?: continue
            for (j in 0 until idnMembers.length()) {
                val member = idnMembers.optJSONObject(j) ?: continue
                when (member.optString("mbr_type")) {
                    "FLPN" -> fixedPlate = normalizePlate(member.optString("mbr_identifier"))
                    "LPN" -> if (member.optString("mbr_active") == "YES") overrideActive = true
                }
            }
        }

        return ProductDetails(
            members = members,
            fixedPlate = fixedPlate?.takeIf { it.isNotBlank() },
            fixedPlateActive = fixedPlate != null && !overrideActive,
        )
    }

    suspend fun getMembers(productId: String): List<Member> = getDetails(productId).members

    suspend fun getBalance(productId: String): Balance = withAuthRetry {
        val payload = postForm(
            "get_balance.json",
            mapOf("product_id" to productId, "locale" to LOCALE),
        )
        assertOk(payload, expectedMinor = "SUCCESS")
        val params = payload.optJSONObject("data")
            ?.optJSONObject("balance")
            ?.optJSONArray("ble_parameters")
        Balance(
            amount = extractParam(params, "AMOUNT")?.toDoubleOrNull(),
            currency = extractParam(params, "CURRENCY_DESC") ?: "€",
            lastModified = extractParam(params, "LAST_MODIFIED"),
        )
    }

    suspend fun findActiveMember(productId: String, plate: String): Member? {
        val plateNorm = normalizePlate(plate)
        return getMembers(productId).firstOrNull { it.plate == plateNorm && it.active && it.actionId != null }
    }

    /**
     * Start parking now until 23:59:59 today.
     *
     * The web API treats "today" differently from planning future days: a
     * simple start action always ends at midnight. Parking that must span
     * multiple days is handled by re-issuing a start action after midnight
     * (see [ParkingService]).
     *
     * Returns the action id of the newly started (verified) action.
     */
    suspend fun start(productId: String, location: String?, plate: String): String {
        val plateNorm = normalizePlate(plate)
        val action = JSONObject().put(
            "action",
            JSONObject().put(
                "atn_parameters",
                JSONArray().apply {
                    put(JSONObject().put("prr_label", "MBR_IDENT").put("prr_value", plateNorm))
                    put(JSONObject().put("prr_label", "TIMESTART").put("prr_value", nowTimestamp()))
                    put(JSONObject().put("prr_label", "TIMEEND").put("prr_value", endOfTodayTimestamp()))
                    put(JSONObject().put("prr_label", "LOCATION").put("prr_value", location ?: ""))
                }
            )
        )

        withAuthRetry {
            val payload = postForm(
                "start_action.json",
                mapOf(
                    "data" to action.toString(),
                    "locale" to LOCALE,
                    "product_id" to productId,
                ),
            )
            assertOk(payload)
        }

        // Verify the action is actually active and fetch its id.
        repeat(3) {
            findActiveMember(productId, plateNorm)?.actionId?.let { return it }
            delay(1000)
        }
        throw TwoParkException("Start not confirmed for $plateNorm")
    }

    suspend fun stopAction(productId: String, actionId: String) {
        withAuthRetry {
            val payload = postForm(
                "stop_action.json",
                mapOf(
                    "action_id" to actionId,
                    "locale" to LOCALE,
                    "product_id" to productId,
                ),
            )
            assertOk(payload, expectedMinor = "SUCCESS")
        }
    }

    /** Available top-up amounts (PAY_AMOUNT values like "10.00"). */
    suspend fun getTopupOptions(productId: String): List<String> = withAuthRetry {
        val payload = postForm(
            "get_upgrade_units.json",
            mapOf(
                "product_id" to productId,
                "locale" to LOCALE,
                "startindex" to "1",
                "stopindex" to "20",
            ),
        )
        assertOk(payload, expectedMinor = "SUCCESS")
        val units = payload.optJSONObject("data")?.optJSONArray("upgrade_units") ?: JSONArray()
        val amounts = mutableListOf<String>()
        for (i in 0 until units.length()) {
            val params = units.optJSONObject(i)?.optJSONArray("uut_parameters") ?: continue
            extractParam(params, "PAY_AMOUNT")?.let { amounts.add(it) }
        }
        amounts
    }

    /**
     * Start a top-up payment. Returns the payment-provider forward data;
     * the caller must submit [TopupForward.parameters] as a form to
     * [TopupForward.url] (same as the website's hidden auto-submit form).
     */
    suspend fun startTopup(
        categoryId: String,
        productId: String,
        payAmount: String,
    ): TopupForward = withAuthRetry {
        val payload = postForm(
            "start_transaction.json",
            mapOf(
                "category_id" to categoryId,
                "locale" to LOCALE,
                "pay_amount" to payAmount,
                "product_id" to productId,
            ),
        )
        assertOk(payload)
        val data = payload.optJSONObject("data")
            ?: throw TwoParkException("No transaction data received")
        val url = data.optString("forwarding_url")
        if (url.isBlank()) throw TwoParkException("No payment URL received")

        val parameters = mutableListOf<Pair<String, String>>()
        val rawParams = data.optJSONArray("parameters") ?: JSONArray()
        for (i in 0 until rawParams.length()) {
            val param = rawParams.optJSONObject(i) ?: continue
            parameters.add(param.optString("prr_label") to param.optString("prr_value"))
        }
        TopupForward(
            url = url,
            method = data.optString("forwarding_method").ifBlank { "POST" },
            parameters = parameters,
        )
    }

    /**
     * Resolve a URL that can be opened in the system browser for [forward].
     *
     * The payment handoff is normally a form POST, which a browser Intent
     * cannot express. We therefore perform the initial request ourselves
     * (without following redirects) and return the payment provider's
     * redirect target. For GET handoffs we simply build the query URL.
     */
    suspend fun resolveTopupBrowserUrl(forward: TopupForward): String = withContext(Dispatchers.IO) {
        val base = forward.url.toHttpUrlOrNull()
            ?: throw TwoParkException("Invalid payment URL")

        if (forward.method.equals("GET", ignoreCase = true)) {
            val builder = base.newBuilder()
            forward.parameters.forEach { (k, v) -> builder.addQueryParameter(k, v) }
            return@withContext builder.build().toString()
        }

        val body = FormBody.Builder().apply {
            forward.parameters.forEach { (k, v) -> add(k, v) }
        }.build()
        val request = Request.Builder()
            .url(base)
            .post(body)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        // Follow redirects manually so we can capture the URL to hand to the
        // browser, and stop at the payment provider's landing page.
        var current: Request? = request
        var lastUrl = forward.url
        var hops = 0
        while (current != null && hops < 5) {
            noRedirectClient.newCall(current).execute().use { resp ->
                lastUrl = resp.request.url.toString()
                val location = resp.header("Location")
                if (resp.isRedirect && location != null) {
                    val next = resp.request.url.resolve(location)
                        ?: throw TwoParkException("Invalid payment redirect")
                    lastUrl = next.toString()
                    // Once we leave 2park, hand the provider URL to the browser.
                    val stillOn2park =
                        next.host == "2park.nl" || next.host.endsWith(".2park.nl")
                    if (next.host != base.host || !stillOn2park) {
                        current = null
                    } else {
                        current = Request.Builder().url(next).get()
                            .header("User-Agent", "Mozilla/5.0").build()
                        hops++
                    }
                } else {
                    current = null
                }
            }
        }
        lastUrl
    }

    /**
     * Add or remove a named plate (favorite) on the account.
     * Mirrors the web app: data={"favorite":{"fav_parameters":[{NICKNAME}],"action":add|remove,"mbr_ident":plate}}
     */
    private suspend fun handleFavorite(
        productId: String,
        action: String,
        plate: String,
        nickname: String?,
    ) {
        val data = JSONObject().put(
            "favorite",
            JSONObject()
                .put(
                    "fav_parameters",
                    JSONArray().put(
                        JSONObject()
                            .put("prr_label", "NICKNAME")
                            .put("prr_value", nickname.orEmpty())
                    )
                )
                .put("action", action)
                .put("mbr_ident", normalizePlate(plate))
        )
        withAuthRetry {
            val payload = postForm(
                "handle_favorite.json",
                mapOf(
                    "data" to data.toString(),
                    "locale" to LOCALE,
                    "product_id" to productId,
                ),
            )
            assertOk(payload, expectedMinor = "SUCCESS")
        }
    }

    suspend fun addFavorite(productId: String, plate: String, nickname: String?) =
        handleFavorite(productId, "add", plate, nickname)

    suspend fun removeFavorite(productId: String, plate: String, nickname: String?) =
        handleFavorite(productId, "remove", plate, nickname)

    /** Stop any active action for [plate]. Returns true if something was stopped. */
    suspend fun stop(productId: String, plate: String): Boolean {
        val member = findActiveMember(productId, plate) ?: return false
        stopAction(productId, member.actionId!!)
        return true
    }
}
