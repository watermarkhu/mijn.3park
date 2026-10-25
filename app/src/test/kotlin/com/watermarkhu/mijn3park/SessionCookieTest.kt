package com.watermarkhu.mijn3park

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCookieTest {

    @Test
    fun encode_joinsNameValuePairs() {
        assertEquals(
            "[PERSON_NAME]=1A2B3C",
            SessionCookie.encode(listOf("[PERSON_NAME]" to "1A2B3C")),
        )
        assertEquals(
            "[PERSON_NAME]=1A2B3C; other=2",
            SessionCookie.encode(listOf("[PERSON_NAME]" to "1A2B3C", "other" to "2")),
        )
    }

    @Test
    fun encode_skipsNamelessEntries() {
        assertEquals("a=1", SessionCookie.encode(listOf("" to "ignored", "a" to "1")))
        assertEquals("", SessionCookie.encode(emptyList()))
    }

    @Test
    fun decode_roundTripsWhatEncodeProduces() {
        val pairs = listOf("[PERSON_NAME]" to "1A2B3C", "other" to "2")
        assertEquals(pairs, SessionCookie.decode(SessionCookie.encode(pairs)))
    }

    @Test
    fun decode_keepsEqualsSignsInsideTheValue() {
        // Session ids are opaque: only the first '=' separates name and value.
        val expected = listOf("token" to "a=b=c")
        val decoded = SessionCookie.decode("token=a=b=c")
        assertEquals(expected, decoded)
    }

    @Test
    fun decode_ignoresMalformedEntriesAndWhitespace() {
        assertEquals(
            listOf("a" to "1", "b" to "2"),
            SessionCookie.decode(" a=1 ; nonsense ; b=2; =3; "),
        )
        assertEquals(emptyList<Pair<String, String>>(), SessionCookie.decode(""))
    }
}
