package com.watermarkhu.mijn3park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    @Test
    fun normalizePlate_uppercasesAndStripsSeparators() {
        assertEquals("AB12CD", normalizePlate(" ab-12 cd "))
        assertEquals("GX472N", normalizePlate("gx-47 2n"))
    }

    @Test
    fun normalizePlate_blankStaysEmpty() {
        assertEquals("", normalizePlate("   "))
    }

    @Test
    fun product_displayName_includesCategoryWhenDifferent() {
        val product = Product(id = "1", name = "Straatparkeren", category = "Centrum", location = null)
        assertEquals("Straatparkeren (Centrum)", product.displayName)
    }

    @Test
    fun product_displayName_omitsRedundantCategory() {
        val same = Product(id = "1", name = "Straatparkeren", category = "Straatparkeren", location = null)
        assertEquals("Straatparkeren", same.displayName)

        val blank = Product(id = "1", name = "Straatparkeren", category = "", location = null)
        assertEquals("Straatparkeren", blank.displayName)
    }

    @Test
    fun product_detectsFixedPlateOption() {
        assertTrue(Product(id = "1", name = "X", category = "", location = null, options = "FLPN").hasFixedPlate)
        assertTrue(Product(id = "1", name = "X", category = "", location = null, options = "A|FLPN|B").hasFixedPlate)
        assertFalse(Product(id = "1", name = "X", category = "", location = null, options = "LPN").hasFixedPlate)
        assertFalse(Product(id = "1", name = "X", category = "", location = null).hasFixedPlate)
    }

    @Test
    fun mutation_detectsDebits() {
        assertTrue(Mutation(type = "Afboeking", amount = "2,50", unit = "€", date = "", plate = "").isDebit)
        assertTrue(Mutation(type = "Foo", amount = "-2.50", unit = "€", date = "", plate = "").isDebit)
        assertFalse(Mutation(type = "Bijschrijving", amount = "10.00", unit = "€", date = "", plate = "").isDebit)
    }

    @Test
    fun balance_formatsUsingDutchLocale() {
        assertEquals("€ 12,50", Balance(amount = 12.5, currency = "€", lastModified = null).formatted)
        assertEquals("—", Balance(amount = null, currency = "€", lastModified = null).formatted)
    }

    @Test
    fun timestampsFollowTheServerFormat() {
        val timestamp = Regex("""\d{2}-\d{2}-\d{4} \d{2}:\d{2}:\d{2}""")
        assertTrue(TwoParkApi.nowTimestamp().matches(timestamp))
        assertTrue(TwoParkApi.endOfTodayTimestamp().matches(timestamp))
        assertTrue(TwoParkApi.endOfTodayTimestamp().endsWith(" 23:59:59"))
    }
}
