package com.watermarkhu.mijn3park

import kotlin.random.Random

/**
 * In-memory stand-in for the 2Park API used by the hardcoded demo account
 * (see [TwoParkApi.MOCK_EMAIL]). It lets the app be explored, screenshotted and
 * reviewed by app stores without a real account or any network access.
 *
 * State mutates within the process so start/stop, favorites and top-ups behave
 * interactively; a fresh instance is created on each (mock) login and on logout,
 * so the demo always starts from the same clean data set.
 */
class MockBackend {

    private data class Fav(val plate: String, val nickname: String?)
    private data class Active(
        val plate: String,
        val actionId: String,
        val start: String,
        val end: String,
    )

    private val prepaidId = "DEMO_PREPAID_100"
    private val permitId = "DEMO_PERMIT_200"

    // Synthetic plates generated per demo session, so no real license plate is
    // ever hardcoded or shown.
    private val plateA = randomPlate()
    private val plateB = randomPlate()
    private val permitFixedPlate = randomPlate()

    private val favorites = mutableListOf(
        Fav(plateA, "Foocar"),
        Fav(plateB, "Barcamper"),
    )
    private val active = mutableListOf<Active>()
    private var balanceAmount = 42.50
    private var actionCounter = 1000

    fun products(): List<Product> = listOf(
        Product(
            id = prepaidId,
            name = "Bezoekersregeling (demo)",
            category = "Demo Gemeente",
            location = "DEMO_CENTRUM",
            options = "EXTEND|MEMBER_GRANT|MEMBER_ADMIN",
            categoryId = "900",
        ),
        Product(
            id = permitId,
            name = "Bewonersvergunning (demo)",
            category = "Demo Gemeente",
            location = "DEMO_CENTRUM",
            options = "MEMBER_ADMIN|FLPN",
            categoryId = "900",
        ),
    )

    fun details(productId: String): ProductDetails {
        if (productId == permitId) {
            // Permit: fixed plate, always covered (no override active), no members.
            return ProductDetails(
                members = emptyList(),
                fixedPlate = permitFixedPlate,
                fixedPlateActive = true,
            )
        }
        val members = buildList {
            favorites.forEach { fav ->
                val act = active.firstOrNull { it.plate == fav.plate }
                add(
                    Member(
                        plate = fav.plate,
                        nickname = fav.nickname,
                        active = act != null,
                        actionId = act?.actionId,
                        timeStart = act?.start,
                        timeEnd = act?.end,
                    )
                )
            }
            // Plates parked but not saved as favorites.
            active.filter { a -> favorites.none { it.plate == a.plate } }.forEach { a ->
                add(Member(a.plate, null, true, a.actionId, a.start, a.end))
            }
        }
        return ProductDetails(members = members, fixedPlate = null, fixedPlateActive = false)
    }

    fun balance(@Suppress("UNUSED_PARAMETER") productId: String): Balance =
        Balance(amount = balanceAmount, currency = "€", lastModified = "20-09-2026 12:00")

    fun start(productId: String, plate: String): String {
        val p = normalizePlate(plate)
        active.firstOrNull { it.plate == p }?.let { return it.actionId }
        val id = "demo-action-${actionCounter++}"
        active.add(Active(p, id, TwoParkApi.nowTimestamp(), TwoParkApi.endOfTodayTimestamp()))
        return id
    }

    fun stopAction(productId: String, actionId: String) {
        active.removeAll { it.actionId == actionId }
    }

    fun topupOptions(): List<String> = listOf("10.00", "20.00", "30.00")

    /** Inert top-up: bumps the demo balance and hands back a harmless URL. */
    fun startTopup(payAmount: String): TopupForward {
        payAmount.toDoubleOrNull()?.let { balanceAmount += it }
        return TopupForward(url = "https://mijn.2park.nl/", method = "GET", parameters = emptyList())
    }

    fun handleFavorite(productId: String, action: String, plate: String, nickname: String?) {
        val p = normalizePlate(plate)
        favorites.removeAll { it.plate == p }
        if (action == "add") favorites.add(Fav(p, nickname?.takeIf { it.isNotBlank() }))
    }

    /** A plausible but random Dutch-style plate (consonants only, no vowels). */
    private fun randomPlate(): String {
        val letters = "BDFGHJKLMNPRSTVWXZ"
        fun l() = letters[Random.nextInt(letters.length)]
        fun d() = '0' + Random.nextInt(10)
        return "${l()}${l()}${d()}${d()}${d()}${l()}"
    }

    fun actionHistory(productId: String, startIndex: Int, stopIndex: Int): ActionHistoryPage {
        val all = if (productId == permitId) emptyList() else demoActions
        return ActionHistoryPage(
            startIndex = startIndex,
            stopIndex = stopIndex,
            maxIndex = all.size,
            actions = all.drop(startIndex).take((stopIndex - startIndex).coerceAtLeast(0)),
        )
    }

    fun mutationHistory(productId: String, startIndex: Int, stopIndex: Int): MutationHistoryPage {
        val all = if (productId == permitId) emptyList() else demoMutations
        return MutationHistoryPage(
            startIndex = startIndex,
            stopIndex = stopIndex,
            maxIndex = all.size,
            mutations = all.drop(startIndex).take((stopIndex - startIndex).coerceAtLeast(0)),
        )
    }

    private val demoActions = listOf(
        ParkingAction(
            id = "demo-h1", plate = plateA,
            timeStart = "20-09-2026 08:15:00", timeEnd = "20-09-2026 17:30:00",
            location = "Demo Centrum", cost = "3.20", costUnit = "€",
            state = "STOPPED", chained = false,
        ),
        ParkingAction(
            id = "demo-h2", plate = plateB,
            timeStart = "18-09-2026 19:05:00", timeEnd = "18-09-2026 23:59:59",
            location = "Demo Centrum", cost = "1.80", costUnit = "€",
            state = "STOPPED", chained = false,
        ),
        ParkingAction(
            id = "demo-h3", plate = plateA,
            timeStart = "15-09-2026 09:40:00", timeEnd = "15-09-2026 12:10:00",
            location = "Demo Centrum", cost = "1.10", costUnit = "€",
            state = "STOPPED", chained = false,
        ),
    )

    private val demoMutations = listOf(
        Mutation(type = "Afboeking", amount = "3.20", unit = "€", date = "20-09-2026", plate = plateA),
        Mutation(type = "Bijschrijving", amount = "20.00", unit = "€", date = "18-09-2026", plate = ""),
        Mutation(type = "Afboeking", amount = "1.80", unit = "€", date = "18-09-2026", plate = plateB),
        Mutation(type = "Afboeking", amount = "1.10", unit = "€", date = "15-09-2026", plate = plateA),
    )
}
