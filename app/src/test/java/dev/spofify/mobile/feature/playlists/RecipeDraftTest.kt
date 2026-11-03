package dev.spofify.mobile.feature.playlists

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class RecipeDraftTest {
    private fun objectOf(json: String) = Json.parseToJsonElement(json).jsonObject
    private fun take(count: String, side: String = "first") = objectOf("""{"side":"$side","count":$count}""")
    private fun predicate(json: String) = blankRecipe().withField("include", objectOf(json))
    private fun valid(value: JsonObject) = assertNull(recipeValidationError(value))
    private fun invalid(value: JsonObject) = assertNotNull(recipeValidationError(value))

    @Test fun blankRecipesHaveIndependentPersistent128BitSlotIdentities() {
        val definitions = (1..100).map { blankRecipe() }
        definitions.forEach(::valid)
        val ids = definitions.map { ((it["sources"] as JsonArray)[0] as JsonObject).text("slotId")!! }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.matches(Regex("s_[0-9a-f]{32}")) })
        val source = (definitions.first()["sources"] as JsonArray)[0].jsonObject
        val changed = changeRecipeSource(source, "playlist:my_mix").withField("take", take("50", "last"))
        assertEquals(source.text("slotId"), changed.text("slotId"))
        assertEquals(source.text("slotId"), changeRecipeSource(changed, "allSaved").text("slotId"))
        assertFalse("playlistId" in changeRecipeSource(changed, "likes"))
        assertEquals(take("50", "last"), changeRecipeSource(changed, "likes")["take"])
        val twoSources = listOf(source, changed.withField("slotId", newRecipeSource()["slotId"]))
        val reordered = definitions.first().withField("sources", JsonArray(twoSources.reversed()))
        valid(reordered)
        assertEquals(twoSources.map { it.text("slotId") }.reversed(), (reordered["sources"] as JsonArray).map { it.jsonObject.text("slotId") })
    }

    @Test fun allowsAllThreeSourcesRepeatedCollectionsAndBothWindowStages() {
        val sources = listOf("likes", "allSaved", "playlist:same", "playlist:same").map {
            changeRecipeSource(newRecipeSource(), it).withField("take", take("2147483647", "last"))
        }
        valid(blankRecipe().withField("sources", JsonArray(sources)).withField("take", take("1001")))
        invalid(blankRecipe().withField("sources", JsonArray(emptyList())))
        invalid(blankRecipe().withField("sources", JsonArray(sources + newRecipeSource())))
        invalid(blankRecipe().withField("sources", JsonArray(listOf(sources[0], sources[0]))))
    }

    @Test fun invalidNumericTextIsRetainedAndNeverConvertedToAValidWindow() {
        for (text in listOf("", "0", "-1", "01", "+1", "1.0", "1e2", " 25", "2147483648", "999999999999999999999")) {
            val count = recipeTakeCount(text)
            assertTrue("$text must remain invalid text", count.isString)
            assertEquals(text, count.content)
            val definition = blankRecipe().withField("take", take("25").withField("count", count))
            invalid(definition)
            assertEquals(count, definition["take"]!!.jsonObject["count"])
        }
        for (text in listOf("1", "25", "1001", "2147483647")) {
            assertFalse(recipeTakeCount(text).isString)
            valid(blankRecipe().withField("take", take(text)))
        }
    }

    @Test fun rejectsWireNumericCoercionNullsAndExtraFieldsAtEveryLevel() {
        for (count in listOf("0", "-1", "1.5", "1e2", "2147483648", "\"25\"", "null", "true")) {
            invalid(blankRecipe().withField("take", take(count)))
            invalid(blankRecipe().withField("sources", JsonArray(listOf(newRecipeSource().withField("take", take(count))))))
        }
        invalid(blankRecipe().withField("version", JsonPrimitive("1")))
        invalid(blankRecipe().withField("version", Json.parseToJsonElement("1e0")))
        invalid(blankRecipe().withField("version", JsonPrimitive(2)))
        invalid(blankRecipe().withField("owner", JsonPrimitive("someone")))
        invalid(blankRecipe().withField("take", JsonNull))
        invalid(blankRecipe().withField("include", JsonNull))
        invalid(blankRecipe().withField("take", take("5").withField("extra", JsonPrimitive(true))))
        invalid(blankRecipe().withField("sources", JsonArray(listOf(newRecipeSource().withField("playlistId", JsonPrimitive("unused"))))))
        invalid(blankRecipe().withField("duplicates", JsonPrimitive("random")))
        invalid(blankRecipe().withField("sort", JsonPrimitive("recent")))
    }

    @Test fun everyPredicateUnionSupportsRecursiveAllAnyAndNot() {
        val leaves = listOf(
            """{"op":"genre","value":"jazz"}""",
            """{"op":"genreKnown","value":false}""",
            """{"op":"genreKnown","value":true}""",
            """{"op":"availability","value":"available"}""",
            """{"op":"availability","value":"unavailable"}""",
            """{"op":"artist","artistId":"manual:opaque id"}""",
            """{"op":"album","albumId":"album-one"}""",
            """{"op":"membership","source":{"kind":"likes"}}""",
            """{"op":"membership","source":{"kind":"playlist","playlistId":"saved-mix"}}""",
        ).map(::objectOf)
        leaves.forEach { leaf ->
            valid(blankRecipe().withField("include", leaf))
            val nested = objectOf("""{"op":"all","args":[{"op":"any","args":[{"op":"not","arg":$leaf}]}]}""")
            valid(blankRecipe().withField("include", nested).withField("exclude", leaf))
        }
    }

    @Test fun rejectsWrongPredicateTypesUnknownOpsAndInactiveUnionFields() {
        listOf(
            """{"op":"genreKnown","value":"false"}""",
            """{"op":"availability","value":true}""",
            """{"op":"availability","value":"available","artistId":"secret"}""",
            """{"op":"artist","artistId":""}""",
            """{"op":"album","albumId":null}""",
            """{"op":"membership","source":{"kind":"allSaved"}}""",
            """{"op":"membership","source":{"kind":"likes","playlistId":"unused"}}""",
            """{"op":"not","arg":null}""",
            """{"op":"all","args":[]}""",
            """{"op":"any","args":{}}""",
            """{"op":"sql","value":"anything"}""",
        ).forEach { invalid(predicate(it)) }
    }

    @Test fun localBoundsCountLeavesAcrossBothPredicatesAndDepthIncludesTheLeaf() {
        val leaf = initialRecipePredicate("availability")
        val all32 = buildJsonObject { put("op", "all"); put("args", JsonArray(List(32) { leaf })) }
        valid(blankRecipe().withField("include", all32))
        invalid(blankRecipe().withField("include", all32).withField("exclude", leaf))
        assertEquals(33, recipePredicateLeafCount(blankRecipe().withField("include", all32).withField("exclude", leaf)))
        var nested = leaf
        repeat(3) { nested = buildJsonObject { put("op", "not"); put("arg", nested) } }
        valid(blankRecipe().withField("include", nested))
        val tooDeep = buildJsonObject { put("op", "not"); put("arg", nested) }
        invalid(blankRecipe().withField("include", tooDeep))
        assertEquals(33, recipePredicateLeafCount(blankRecipe().withField("include", tooDeep)))
    }

    @Test fun screensGenrePlaceholdersCommasBoundsControlsAndUnpairedSurrogates() {
        for (text in listOf("", "  ", "Unknown", "ＵＮＤＥＦＩＮＥＤ", "n/a", "jazz,rock", "jazz，rock", "a\nb", "a\u0000b", "界".repeat(67), "a".repeat(101), "\uD800")) {
            invalid(blankRecipe().withField("include", initialRecipePredicate("genre").withField("value", JsonPrimitive(text))))
        }
        for (text in listOf("Jazz", "  synth　pop  ", "R&B/Soul", "界".repeat(66), "Straße")) {
            valid(blankRecipe().withField("include", initialRecipePredicate("genre").withField("value", JsonPrimitive(text))))
        }
    }

    @Test fun structuralValidityDoesNotPretendToProveGraphOrCatalogReferences() {
        valid(blankRecipe().withField("sources", JsonArray(listOf(changeRecipeSource(newRecipeSource(), "playlist:possibly-self"))))
            .withField("include", objectOf("""{"op":"artist","artistId":"not-yet-verified"}""")))
        invalid(predicate("""{"op":"artist","artistId":" padded "}"""))
        invalid(predicate("""{"op":"album","albumId":"${"x".repeat(513)}"}"""))
    }

    @Test fun labelReferencesAreTypedDeduplicatedAndBoundedWithoutEnumeratingSources() {
        val sameIdDifferentKinds = objectOf("""{"op":"any","args":[{"op":"artist","artistId":"same"},{"op":"album","albumId":"same"},{"op":"artist","artistId":"same"}]}""")
        assertEquals(listOf("artist" to "same", "album" to "same"), recipeMusicReferences(blankRecipe().withField("include", sameIdDifferentKinds)))
        val many = buildJsonObject {
            put("op", "all")
            put("args", JsonArray(List(40) { initialRecipePredicate("artist").withField("artistId", JsonPrimitive("artist-$it")) }))
        }
        assertEquals(32, recipeMusicReferences(blankRecipe().withField("include", many)).size)
        var deep = initialRecipePredicate("album").withField("albumId", JsonPrimitive("too-deep"))
        repeat(4) { deep = buildJsonObject { put("op", "not"); put("arg", deep) } }
        assertTrue(recipeMusicReferences(blankRecipe().withField("include", deep)).isEmpty())
        assertTrue(recipeMusicReferences(blankRecipe()).isEmpty())
    }

    @Test fun authorityRejectsOutOfOrderCancelledEditedAndAlreadyConsumedResults() {
        val authority = RecipeAuthority()
        assertTrue(authority.stale)
        val first = authority.begin()
        val second = authority.begin()
        assertFalse(authority.isCurrent(first))
        assertFalse(authority.complete(first))
        assertFalse(authority.fail(first))
        assertTrue(authority.pending)
        assertTrue(authority.complete(second))
        assertFalse(authority.pending)
        assertFalse(authority.stale)
        assertFalse(authority.complete(second))
        val beforeEdit = authority.begin()
        authority.invalidate()
        assertFalse(authority.isCurrent(beforeEdit))
        assertFalse(authority.complete(beforeEdit))
        assertTrue(authority.stale)
        val current = authority.begin()
        assertTrue(authority.fail(current))
        assertTrue(authority.stale)
        assertFalse(authority.pending)
        assertTrue(authority.complete(authority.begin()))
    }
}
