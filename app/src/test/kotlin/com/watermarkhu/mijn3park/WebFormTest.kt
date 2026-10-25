package com.watermarkhu.mijn3park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebFormTest {

    @Test
    fun encode_leavesEncodeUriReservedAndUnreservedCharactersAlone() {
        // The exact set JavaScript's encodeURI does not escape.
        val raw = "AZaz09-_.!~*'();/?:@&=+\$,#"
        assertEquals(raw, WebForm.encode(raw))
    }

    @Test
    fun encode_escapesEverythingElse() {
        assertEquals("a%20b", WebForm.encode("a b"))
        assertEquals("100%25", WebForm.encode("100%"))
        assertEquals("a%5Bb%5D", WebForm.encode("a[b]"))
        assertEquals("a%7Bb%7D", WebForm.encode("a{b}"))
        assertEquals("a%3Cb%3Ec", WebForm.encode("a<b>c"))
        assertEquals("%22quoted%22", WebForm.encode("\"quoted\""))
        assertEquals("%E2%82%AC", WebForm.encode("€"))
        assertEquals("%C3%A9", WebForm.encode("é"))
    }

    @Test
    fun encode_keepsAmpersandAndPlusRaw() {
        // This is the whole point: the website sends these unescaped, and the
        // server then treats them as a field separator / space respectively.
        assertEquals("a&b", WebForm.encode("a&b"))
        assertEquals("a+b", WebForm.encode("a+b"))
    }

    @Test
    fun body_concatenatesFieldsLikeTheWebsite() {
        assertEquals(
            "email=u@x.nl&password=a&b+c&locale=nl_NL",
            WebForm.body(
                "email" to "u@x.nl",
                "password" to "a&b+c",
                "locale" to "nl_NL",
            ),
        )
    }

    @Test
    fun body_encodesOnlyWhatEncodeUriWould() {
        assertEquals(
            "password=Str0ng&Pass+1#end",
            WebForm.body("password" to "Str0ng&Pass+1#end"),
        )
        assertEquals(
            "password=100%25%20done",
            WebForm.body("password" to "100% done"),
        )
    }

    @Test
    fun body_matchesTheVerifiedLiveLoginBodyForAPlusPassword() {
        // Verified against POST check_credentials.json: for a password holding
        // '+', the body the website sends (raw '+') is ACCEPTED, while the
        // RFC-encoded equivalent ('%2B', what OkHttp's FormBody produces) is
        // rejected with INVALID_CREDS. Keep these bytes stable.
        assertEquals(
            "email=user@example.com&password=#A%5E1bC!+XyZ0@QwErTy&locale=nl_NL",
            WebForm.body(
                "email" to "user@example.com",
                "password" to "#A^1bC!+XyZ0@QwErTy",
                "locale" to "nl_NL",
            ),
        )
        assertTrue(WebForm.differsFromFormEncoding("#A^1bC!+XyZ0@QwErTy"))
    }

    @Test
    fun loginPassword_stripsAmpersandsLikeTheWebClient() {
        // The site's form input sanitises values with replace(/[&]/g, "").
        assertEquals("", WebForm.loginPassword(""))
        assertEquals("Str0ngPass1!", WebForm.loginPassword("Str0ngPass1!"))
        assertEquals("ab", WebForm.loginPassword("a&b&&"))
        assertEquals("#A^1bC!+XyZ0@QwErTy", WebForm.loginPassword("#A^1bC&!+XyZ0@QwErTy"))
    }

    @Test
    fun body_forAStrippedPasswordKeepsThePlusRaw() {
        assertEquals(
            "password=#A%5E1bC!+XyZ0@QwErTy",
            WebForm.body("password" to WebForm.loginPassword("#A^1bC&!+XyZ0@QwErTy")),
        )
    }

    @Test
    fun differsFromFormEncoding_onlyForAmpersandAndPlus() {
        assertTrue(WebForm.differsFromFormEncoding("a&b"))
        assertTrue(WebForm.differsFromFormEncoding("a+b"))
        assertTrue(WebForm.differsFromFormEncoding("user+tag@example.com"))

        assertFalse(WebForm.differsFromFormEncoding(""))
        assertFalse(WebForm.differsFromFormEncoding("Str0ngPass1!"))
        assertFalse(WebForm.differsFromFormEncoding("a?b=c"))
        assertFalse(WebForm.differsFromFormEncoding("100%"))
        assertFalse(WebForm.differsFromFormEncoding("a#b@c\$d"))
    }
}
