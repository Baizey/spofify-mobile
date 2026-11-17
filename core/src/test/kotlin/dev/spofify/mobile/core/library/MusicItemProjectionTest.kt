package dev.spofify.mobile.core.library

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MusicItemProjectionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun appendedMetadataDefaultsPreserveExistingPositionalConstruction() {
        val legacy = MusicItem(MusicKind.ALBUM, "release", "Display", listOf(Artist("", "Manual artist")),
            "", "", "2025", 0, 0, false, 2, 3, listOf("rock"))
        assertEquals(listOf("rock"), legacy.genres)
        assertEquals("", legacy.albumType)
        assertEquals("", legacy.relation)
        val projected = PositiveMusicItemProjection.decode(item())
        assertEquals(legacy, projected)
    }

    @Test fun positiveProjectionRetainsBothFieldsUnchangedForEveryEntityKind() {
        for (kind in MusicKind.entries) {
            val row = JsonObject(item(kind) + buildJsonObject {
                put("albumType", "single")
                put("relation", "appearance")
            })
            val projected = PositiveMusicItemProjection.decode(row)
            assertEquals(kind, projected.kind)
            assertEquals("single", projected.albumType)
            assertEquals("appearance", projected.relation)
            assertEquals(listOf(Artist("", "Manual artist")), projected.artists)
            assertEquals(listOf("rock"), projected.genres)
            assertFalse(projected.hasAudio)
        }
    }

    @Test fun optionalBlankAndUnknownFutureStringsAreNotNormalizedOrMappedToInventedEnums() {
        for ((albumType, relation) in listOf(null to null, "" to "", " Future / 日本 " to " future-relation-v2 ")) {
            val row = JsonObject(item() + buildJsonObject {
                albumType?.let { put("albumType", it) }
                relation?.let { put("relation", it) }
            })
            val wire = json.decodeFromString<MusicItemWire>(row.toString())
            for (projected in listOf(projectMusicItem(wire), PositiveMusicItemProjection.decode(row))) {
                assertEquals(albumType.orEmpty(), projected.albumType)
                assertEquals(relation.orEmpty(), projected.relation)
            }
        }
    }

    @Test fun presentNullOrNonStringMetadataIsRejectedByBothWireAndPositiveDecoders() {
        val invalidValues = listOf(JsonNull, JsonPrimitive(false), JsonPrimitive(42), buildJsonArray {}, buildJsonObject {})
        for (field in listOf("albumType", "relation")) for (value in invalidValues) {
            val row = JsonObject(item() + (field to value))
            assertTrue("Positive $field must be a string", runCatching { PositiveMusicItemProjection.decode(row) }.isFailure)
            assertTrue("Wire $field must be a string", runCatching {
                json.decodeFromString<MusicItemWire>(row.toString())
            }.isFailure)
        }
    }

    @Test fun releaseMetadataDoesNotChangeExplicitAudioOrLegacyAdministratorFallbackRules() {
        val row = JsonObject(item() + buildJsonObject {
            put("albumType", "compilation")
            put("relation", "compilation")
            put("libraryPath", "private-do-not-publish.flac")
        })
        val positive = PositiveMusicItemProjection.decode(row)
        assertFalse(positive.hasAudio)
        assertEquals("compilation", positive.albumType)
        assertEquals("compilation", positive.relation)
        val explicitWire = json.decodeFromString<MusicItemWire>(row.toString())
        assertFalse(projectMusicItem(explicitWire, legacyAdministratorFallback = true).hasAudio)
        val legacyRow = JsonObject(row - "hasAudio")
        assertTrue(runCatching { PositiveMusicItemProjection.decode(legacyRow) }.isFailure)
        val legacyWire = json.decodeFromString<MusicItemWire>(legacyRow.toString())
        assertTrue(runCatching { projectMusicItem(legacyWire) }.isFailure)
        val administrator = projectMusicItem(legacyWire, legacyAdministratorFallback = true)
        assertTrue(administrator.hasAudio)
        assertEquals(positive.copy(hasAudio = true), administrator)
        assertFalse(administrator.toString().contains("private-do-not-publish"))
    }

    private fun item(kind: MusicKind = MusicKind.ALBUM): JsonObject = buildJsonObject {
        put("kind", kind.plural.dropLast(1))
        put("id", "release")
        put("name", "Display")
        putJsonArray("artists") {
            add(buildJsonObject { put("id", ""); put("name", "Manual artist") })
        }
        put("releaseDate", "2025")
        put("hasAudio", false)
        putJsonArray("genres") { add(JsonPrimitive("rock")) }
        putJsonObject("coverage") { put("tracks", 3); put("inLibrary", 2); put("albums", 1) }
    }
}
