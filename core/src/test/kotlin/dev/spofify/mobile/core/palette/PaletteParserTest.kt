package dev.spofify.mobile.core.palette

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PaletteParserTest {
    private fun parse(body: String) = PaletteParser.parse(body.toByteArray(Charsets.UTF_8), "account")

    private fun rejects(body: String, expected: PaletteFailure = PaletteFailure.INVALID_RESPONSE) {
        val failure = runCatching { parse(body) }.exceptionOrNull()
        assertTrue(failure is PaletteException)
        assertEquals(expected, (failure as PaletteException).failure)
        assertEquals("Account palette request failed", failure.message)
        assertNull(failure.cause)
        assertFalse(failure.toString().contains("fixture-secret"))
    }

    @Test fun fixedCatalogProjectsBothModesAndAuthoritativeOwnerSelectionAndRevision() {
        val result = parse(PaletteTestData.document(id = "ember", revision = "42"))
        assertEquals("account", result.accountId)
        assertEquals(PaletteId.EMBER, result.paletteId)
        assertEquals(42L, result.revision)
        assertEquals(1, result.catalogVersion)
        assertEquals(PaletteId.JADE, result.defaultPaletteId)
        assertEquals(PaletteId.entries, result.presets.map { it.id })
        assertEquals("Ember", result.selected.name)
        assertEquals(PaletteTokens("#ffad89", "#31170d", "#c4b0ff", "#a4d7bd"), result.selected.dark)
        assertEquals(PaletteTokens("#aa431b", "#ffffff", "#654199", "#23644a"), result.selected.light)
        assertEquals(0L, parse(PaletteTestData.document()).revision)
        assertEquals(PaletteParser.MAX_REVISION, parse(PaletteTestData.document(revision = "9007199254740991")).revision)
        assertEquals("#66e3b4", parse(PaletteTestData.document().replace("#66e3b4", "#66E3B4")).presets.first().dark.primary)
    }

    @Test fun revisionsMustBeActualNonnegativeSafeIntegers() {
        for (revision in listOf("-1", "9007199254740992", "9223372036854775808", "1.0", "1e0", "\"0\"", "null", "true"))
            rejects(PaletteTestData.document(revision = revision))
        rejects(PaletteTestData.document().replace("\"revision\":0,", ""))
    }

    @Test fun exactOwnerAndKnownCatalogVersionAndIdsAreRequired() {
        rejects(PaletteTestData.document(owner = "another-account"))
        rejects(PaletteTestData.document(owner = "ACCOUNT"))
        rejects(PaletteTestData.document(owner = ""))
        rejects(PaletteTestData.document(id = "blue"))
        rejects(PaletteTestData.document(id = "JADE"))
        rejects(PaletteTestData.document(default = "ember"))
        rejects(PaletteTestData.document(default = "unknown"))
        rejects(PaletteTestData.document(version = "2"), PaletteFailure.CATALOG)
        for (version in listOf("0", "-1", "\"1\"", "1.0", "null", "9007199254740992"))
            rejects(PaletteTestData.document(version = version))
    }

    @Test fun catalogMustBeCompleteUniqueAndUnmodifiedNotJustHexLooking() {
        val entries = Json.parseToJsonElement(PaletteTestData.presets).jsonArray
        rejects(PaletteTestData.document(entries = JsonArray(entries.dropLast(1)).toString()))
        rejects(PaletteTestData.document(entries = JsonArray(listOf(entries[0], entries[0], entries[2])).toString()))
        rejects(PaletteTestData.document(entries = JsonArray(entries + entries[0]).toString()))
        rejects(PaletteTestData.document().replace("\"id\":\"jade\"", "\"id\":\"unknown\""))
        rejects(PaletteTestData.document().replace("\"name\":\"Jade\"", "\"name\":\"fixture-secret\""))
        for (token in listOf("#000000", "#fff", "#66e3b4ff", "66e3b4", "#gggggg", "var(--fixture-secret)", "url(fixture-secret)"))
            rejects(PaletteTestData.document().replace("#66e3b4", token))
        rejects(PaletteTestData.document().replace("\"onPrimary\":\"#08271d\",", ""))
        rejects(PaletteTestData.document().replace("\"primary\":\"#66e3b4\"", "\"primary\":false"))
        rejects(PaletteTestData.document().replace("\"dark\":{", "\"css\":\"fixture-secret\",\"dark\":{"))
    }

    @Test fun unknownFieldsEscapedDuplicatesMalformedUtf8AndBodyDepthAreRejected() {
        val valid = PaletteTestData.document()
        rejects(valid.replace("\"revision\":0", "\"revision\":0,\"revision\":1"))
        rejects(valid.replace("\"revision\":0", "\"revision\":0,\"rev\\u0069sion\":1"))
        rejects(valid.replace("\"primary\":\"#66e3b4\"", "\"primary\":\"#66e3b4\",\"pr\\u0069mary\":\"#000000\""))
        rejects(valid.replace("\"revision\":0", "\"revision\":0,\"url\":\"fixture-secret\""))
        rejects("[]")
        rejects("{\"error\":\"fixture-secret/private/path\"}")
        rejects(" ".repeat(16 * 1024 + 1))
        rejects("[".repeat(17) + "0" + "]".repeat(17))
        val failure = runCatching { PaletteParser.parse(byteArrayOf(0xc3.toByte(), 0x28), "account") }.exceptionOrNull()
        assertEquals(PaletteFailure.INVALID_RESPONSE, (failure as PaletteException).failure)
        assertNull(failure.cause)
    }
}
