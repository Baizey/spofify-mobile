package dev.spofify.mobile.playback.cache

import android.app.Application
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AutoCacheSettingsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val gb = AutoCacheSettings.BYTES_PER_GB
    private val reserve = AutoCacheSettings.FREE_SPACE_RESERVE_BYTES

    @Test fun defaultsUseCountDependentDecimalMinimum() {
        val settings = AutoCacheSettings()
        assertEquals(10, settings.maxSongs)
        assertNull(settings.storageGb)
        assertEquals(600_000_000L, settings.minimumBytes)
        assertEquals(settings.minimumBytes, settings.maxBytes)
        assertEquals(200_000_000L, settings.copy(maxSongs = 2).maxBytes)
        assertEquals(450_000_000L, settings.copy(maxSongs = 7).maxBytes)
    }

    @Test fun fixedStorageUsesDecimalGbAndDoesNotFollowCountChanges() {
        val settings = AutoCacheSettings(2, 1)
        assertEquals(gb, settings.maxBytes)
        assertEquals(gb, settings.copy(maxSongs = 10).maxBytes)
        assertTrue(settings.copy(maxSongs = 10).maxBytes >= settings.copy(maxSongs = 10).minimumBytes)
    }

    @Test fun normalizationClampsLegacyCountsAndPreservesValidFixedChoice() {
        assertEquals(AutoCacheSettings(10, 2), AutoCacheSettings(100, 2).normalized())
        assertEquals(AutoCacheSettings(10), AutoCacheSettings(Int.MAX_VALUE).normalized())
        assertEquals(AutoCacheSettings(2), AutoCacheSettings(Int.MIN_VALUE).normalized())
        assertEquals(AutoCacheSettings(2), AutoCacheSettings(0).normalized())
        assertEquals(AutoCacheSettings(2), AutoCacheSettings(1).normalized())
        for (count in 2..10) {
            val settings = AutoCacheSettings(count, 1)
            assertEquals(settings, settings.normalized())
            assertEquals(settings.normalized(), settings.normalized().normalized())
        }
    }

    @Test fun malformedFixedStorageFallsBackToMinimumWithoutRaisingAFixedTarget() {
        for (fixed in listOf(0, -1, Int.MIN_VALUE)) {
            assertEquals(AutoCacheSettings(4), AutoCacheSettings(4, fixed).normalized())
            assertEquals(300_000_000L, AutoCacheSettings(4, fixed).maxBytes)
        }
    }

    @Test fun invalidUserEditsAreRejectedRatherThanNormalized() {
        for (count in listOf(Int.MIN_VALUE, 0, 1, 11, 100, Int.MAX_VALUE)) {
            assertEquals("Choose 2–10 songs", AutoCacheSettings(count).validationError(20 * gb, 20 * gb, 0))
        }
        for (fixed in listOf(0, -1, Int.MIN_VALUE)) {
            assertEquals("Choose Minimum or a positive whole GB",
                AutoCacheSettings(10, fixed).validationError(20 * gb, 20 * gb, 0))
        }
    }

    @Test fun minimumAndFixedCeilingsMustFitHalfFilesystemCapacity() {
        assertNull(AutoCacheSettings().validationError(1_200_000_000L, gb, 0))
        assertNotNull(AutoCacheSettings().validationError(1_199_999_999L, gb, 0))
        assertNull(AutoCacheSettings(10, 1).validationError(2 * gb, 2 * gb, 0))
        assertNotNull(AutoCacheSettings(10, 1).validationError(2 * gb - 1, 2 * gb, 0))
        assertNotNull(AutoCacheSettings(2).validationError(399_999_999L, gb, 0))
    }

    @Test fun freeSpaceAndItsSafetyReserveAreIndependentOfCapacity() {
        val settings = AutoCacheSettings(10, 1)
        assertNull(settings.validationError(100 * gb, gb + reserve, 0))
        assertEquals("Not enough free storage", settings.validationError(100 * gb, gb + reserve - 1, 0))
        assertEquals("Not enough free storage", settings.validationError(100 * gb, reserve - 1, gb - 1))
        assertEquals("Not enough free storage", settings.validationError(100 * gb, 0, 0))
    }

    @Test fun ownedBytesAreNotAllocatedAgainAndPinnedDecreasesNeedNoFreeSpace() {
        val settings = AutoCacheSettings(10, 1)
        assertNull(settings.validationError(20 * gb, 100_000_000L + reserve, 900_000_000L))
        assertNotNull(settings.validationError(20 * gb, 100_000_000L + reserve - 1, 900_000_000L))
        assertNull(settings.validationError(20 * gb, 0, gb))
        assertNull(settings.validationError(20 * gb, 0, 4 * gb))
        // Owned bytes do not relax the separate half-capacity ceiling.
        assertNotNull(settings.validationError(gb, 0, 4 * gb))
    }

    @Test fun unavailableFilesystemInformationFailsClosed() {
        val settings = AutoCacheSettings()
        assertEquals("Storage information is unavailable", settings.validationError(0, gb, 0))
        assertEquals("Storage information is unavailable", settings.validationError(-1, gb, 0))
        assertEquals("Storage information is unavailable", settings.validationError(10 * gb, -1, 0))
        assertEquals("Storage information is unavailable", settings.validationError(10 * gb, gb, -1))
    }

    @Test fun extremeValuesDoNotOverflowByteArithmetic() {
        val settings = AutoCacheSettings(10, Int.MAX_VALUE)
        assertEquals(2_147_483_647_000_000_000L, settings.maxBytes)
        assertNull(settings.validationError(Long.MAX_VALUE, Long.MAX_VALUE, 0))
        assertNull(settings.validationError(Long.MAX_VALUE, 0, Long.MAX_VALUE))
        assertNotNull(settings.validationError(Long.MAX_VALUE, 0, 0))
        assertEquals(600_000_000L, AutoCacheSettings(Int.MAX_VALUE).minimumBytes)
        assertEquals(200_000_000L, AutoCacheSettings(Int.MIN_VALUE).minimumBytes)
    }

    @Test fun missingConfigurationUsesDefaultsWithoutWritingAnything() {
        val directory = temporary.newFolder("files")
        assertEquals(AutoCacheSettings(), AutoCacheSettingsStore(directory).read())
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun committedFixedAndMinimumSettingsSurviveStoreRecreation() {
        val directory = temporary.newFolder("files")
        AutoCacheSettingsStore(directory).write(AutoCacheSettings(3, 2))
        assertEquals(AutoCacheSettings(3, 2), AutoCacheSettingsStore(directory).read())
        AutoCacheSettingsStore(directory).write(AutoCacheSettings(8))
        assertEquals(AutoCacheSettings(8), AutoCacheSettingsStore(directory).read())
        assertEquals(listOf(AutoCacheSettingsStore.FILE_NAME), directory.listFiles()!!.map { it.name })
    }

    @Test fun legacyCountAndMalformedFixedValueAreRecoveredWithoutRewriteOrMediaDeletion() {
        val directory = temporary.newFolder("files")
        val config = File(directory, AutoCacheSettingsStore.FILE_NAME)
        val media = File(directory, "protected-media").apply { writeText("untouched") }
        config.writeBytes(record(100, 2))
        assertEquals(AutoCacheSettings(10, 2), AutoCacheSettingsStore(directory).read())
        config.writeBytes(record(100, 0))
        val before = config.readBytes()
        assertEquals(AutoCacheSettings(10), AutoCacheSettingsStore(directory).read())
        assertArrayEquals(before, config.readBytes())
        assertEquals("untouched", media.readText())
    }

    @Test fun corruptTruncatedUnknownOrOversizedRecordsFallBackWithoutRewrite() {
        val directory = temporary.newFolder("files")
        val config = File(directory, AutoCacheSettingsStore.FILE_NAME)
        val complete = record(3, 1)
        val corrupt = listOf(byteArrayOf(), byteArrayOf(1, 2, 3),
            record(3, 1, version = 2), record(3, 1, magic = 0), complete + byteArrayOf(0)) +
            (0 until complete.size).map { complete.copyOf(it) }
        for (bytes in corrupt) {
            config.writeBytes(bytes)
            assertEquals(AutoCacheSettings(), AutoCacheSettingsStore(directory).read())
            assertArrayEquals(bytes, config.readBytes())
        }
    }

    @Test fun rejectedWritePreservesPreviouslyCommittedConfiguration() {
        val directory = temporary.newFolder("files")
        val store = AutoCacheSettingsStore(directory)
        val previous = AutoCacheSettings(4, 1)
        store.write(previous)
        for (invalid in listOf(AutoCacheSettings(100, 1), AutoCacheSettings(4, 0))) {
            assertThrows(IllegalArgumentException::class.java) { store.write(invalid) }
            assertEquals(previous, AutoCacheSettingsStore(directory).read())
        }
    }

    @Test fun interruptedAtomicWriteRecoversPreviousCommittedPolicy() {
        val directory = temporary.newFolder("files")
        val previous = AutoCacheSettings(4, 1)
        AutoCacheSettingsStore(directory).write(previous)
        val atomic = AtomicFile(File(directory, AutoCacheSettingsStore.FILE_NAME))
        // Model process death before finishWrite/failWrite: the partial generation is never valid.
        atomic.startWrite().use { stream ->
            stream.write(byteArrayOf(1, 2, 3))
            stream.fd.sync()
        }
        assertEquals(previous, AutoCacheSettingsStore(directory).read())
    }

    @Test fun filesystemWriteFailureIsReportedInsteadOfPublishingSuccess() {
        val notDirectory = temporary.newFile("not-a-directory")
        val store = AutoCacheSettingsStore(notDirectory)
        assertEquals(AutoCacheSettings(), store.read())
        assertThrows(IOException::class.java) { store.write(AutoCacheSettings(3, 1)) }
    }

    private fun record(count: Int, fixed: Int, version: Int = 1, magic: Int = 0x41435331): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(magic)
                output.writeInt(version)
                output.writeInt(count)
                output.writeInt(fixed)
            }
        }.toByteArray()
}
