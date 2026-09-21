package com.watermarkhu.mijn3park

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reliability of the 2Park backend as seen by this app.
 *
 * - [CHECKING] — a health check is running.
 * - [OK] — every probed endpoint answered as expected.
 * - [UNAVAILABLE] — the server cannot be reached (network, timeout, 5xx).
 * - [UNRELIABLE] — the server answered, but not in the expected shape.
 */
enum class HealthState { CHECKING, OK, UNAVAILABLE, UNRELIABLE }

/**
 * Shared account/product state for the whole activity. Owns the product list,
 * the selected product's details, members and balance, and keeps the local
 * parking session in sync with the server. Fragments observe [state]; the host
 * activity reacts to [health], [sessionExpired] and [messages].
 */
data class AppState(
    val products: List<Product> = emptyList(),
    val selectedProduct: Product? = null,
    val details: ProductDetails? = null,
    val members: List<Member> = emptyList(),
    val balance: String = "",
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = Prefs(app)
    private val api get() = TwoParkApi.instance

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _health = MutableStateFlow(HealthState.CHECKING)
    val health: StateFlow<HealthState> = _health.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    private val _refreshDone = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshDone: SharedFlow<Unit> = _refreshDone.asSharedFlow()

    private var appliedDefaultProduct = false
    private var checksStarted = false

    /** The selected product, derived from the stored product id. */
    private fun selectedProduct(products: List<Product> = _state.value.products): Product? =
        products.firstOrNull { it.id == prefs.productId }

    /** First health check of the process; safe to call again after rotation. */
    fun start() {
        if (checksStarted) return
        checksStarted = true
        runChecks(showChecking = true)
    }

    /** User-initiated full retry (from a failure screen). */
    fun retry() = runChecks(showChecking = true)

    /** Pull-to-refresh: re-probe without flashing the "checking" screen. */
    fun refresh() = runChecks(showChecking = false)

    /**
     * Probe the core read-only endpoints the app depends on for parking
     * (categories, product details, balance); never the mutating ones, and not
     * the paged history endpoints so the check stays fast. Drives the failure
     * screens and whether parking may be enabled.
     */
    private fun runChecks(showChecking: Boolean) {
        viewModelScope.launch {
            if (showChecking) _health.value = HealthState.CHECKING
            try {
                ensureLoggedIn()
                val products = api.getProducts()

                // Preselect the default product once per app start.
                if (!appliedDefaultProduct) {
                    appliedDefaultProduct = true
                    products.firstOrNull { it.id == prefs.defaultProductId }
                        ?.takeIf { (it.id != prefs.productId) && !prefs.isParking }
                        ?.let { applyProduct(it) }
                }
                // Keep the stored selection valid.
                if (products.none { it.id == prefs.productId }) {
                    products.firstOrNull()?.let { applyProduct(it) }
                }
                _state.update {
                    it.copy(products = products, selectedProduct = selectedProduct(products))
                }

                val product = selectedProduct(products)
                    ?: throw ApiIncompatibleException("No 2Park product available")

                val details = api.getDetails(product.id)
                _state.update {
                    it.copy(details = details, members = details.members, selectedProduct = product)
                }

                // Permits are not prepaid: no balance.
                if (details.fixedPlate == null) {
                    val balance = api.getBalance(product.id)
                    prefs.lastBalance = balance.formatted
                    _state.update { it.copy(balance = balance.formatted) }
                } else {
                    _state.update { it.copy(balance = "") }
                }

                // Sync local state with the server (parking started/stopped elsewhere).
                syncParkingSession(details)

                _health.value = HealthState.OK
            } catch (_: AuthFailedException) {
                _sessionExpired.tryEmit(Unit)
            } catch (_: SessionExpiredException) {
                _sessionExpired.tryEmit(Unit)
            } catch (_: ApiUnavailableException) {
                _health.value = HealthState.UNAVAILABLE
            } catch (_: ApiIncompatibleException) {
                _health.value = HealthState.UNRELIABLE
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Anything unexpected is treated as an unreliable API.
                _health.value = HealthState.UNRELIABLE
            } finally {
                _refreshDone.tryEmit(Unit)
            }
        }
    }

    fun selectProduct(product: Product) {
        applyProduct(product)
        _state.update { it.copy(selectedProduct = product) }
        refreshRemoteData()
    }

    private fun applyProduct(product: Product) {
        prefs.productId = product.id
        prefs.productName = product.displayName
        prefs.productLocation = product.location.orEmpty()
        prefs.productCategoryId = product.categoryId
        _state.update { it.copy(details = null, members = emptyList(), balance = "") }
    }

    fun setDefaultProduct(enabled: Boolean) {
        prefs.defaultProductId = if (enabled) prefs.productId else ""
    }

    /** Lightweight per-product refresh (details + balance); never upgrades health. */
    fun refreshRemoteData() {
        if (prefs.productId.isBlank()) return
        viewModelScope.launch {
            try {
                ensureLoggedIn()
                val details = api.getDetails(prefs.productId)
                _state.update {
                    it.copy(
                        details = details,
                        members = details.members,
                        selectedProduct = selectedProduct(),
                    )
                }
                if (details.fixedPlate == null) {
                    val balance = api.getBalance(prefs.productId)
                    prefs.lastBalance = balance.formatted
                    _state.update { it.copy(balance = balance.formatted) }
                }
                syncParkingSession(details)
            } catch (_: AuthFailedException) {
                _sessionExpired.tryEmit(Unit)
            } catch (_: SessionExpiredException) {
                _sessionExpired.tryEmit(Unit)
            } catch (_: ApiUnavailableException) {
                _health.value = HealthState.UNAVAILABLE
            } catch (_: ApiIncompatibleException) {
                _health.value = HealthState.UNRELIABLE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: e.toString())
            }
        }
    }

    /** Align the local parking session with what the server reports. */
    private fun syncParkingSession(details: ProductDetails) {
        val activeMember = details.members.firstOrNull { it.active && it.actionId != null }
        if (activeMember != null && !prefs.isParking) {
            prefs.activePlate = activeMember.plate
            prefs.activeSince = System.currentTimeMillis()
            ParkingService.start(getApplication(), activeMember.plate)
        } else if (activeMember == null && prefs.isParking) {
            prefs.clearActiveParking()
            ParkingService.stop(getApplication())
        }
    }

    private suspend fun ensureLoggedIn() {
        if (api.email.isBlank()) {
            api.login(prefs.email, prefs.password)
        }
    }

    /** Signal that a direct API call failed because the credentials are dead. */
    fun reportSessionExpired() {
        _sessionExpired.tryEmit(Unit)
    }

    /** Route a direct API failure from a fragment into the shared health state. */
    fun reportApiFailure(error: Throwable) {
        when (error) {
            is ApiUnavailableException -> _health.value = HealthState.UNAVAILABLE
            is ApiIncompatibleException -> _health.value = HealthState.UNRELIABLE
        }
    }

    /** Full wipe shared by manual logout and expired-session auto-logout. */
    fun logout() {
        ParkingService.stop(getApplication())
        api.logout()
        prefs.clearAll()
        appliedDefaultProduct = false
        checksStarted = false
        _health.value = HealthState.CHECKING
    }
}
