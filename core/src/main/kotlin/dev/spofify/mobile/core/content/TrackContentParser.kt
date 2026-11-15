package dev.spofify.mobile.core.content

import kotlinx.serialization.json.*
import java.net.URI
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max

/** Strict, bounded v1 projections. No wire diagnostics or unknown fields enter presentation state. */
internal object TrackContentParser {
    private const val ALGORITHM = "peak-u8-two-pass-v1-half-up"
    private val json = Json
    private val integer = Regex("-?(0|[1-9][0-9]*)")
    private val utc = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z")

    fun waveform(bytes: ByteArray, requestedId: String): TrackWaveform = safely {
        val root = document(bytes, 16 * 1024, setOf("version", "trackId", "state", "sourceGeneration",
            "algorithmVersion", "productVersion", "frames", "sampleRate", "channels", "durationSeconds", "bins"))
        require(root.long("version") == 1L && root.string("trackId") == validTrackId(requestedId))
        val state = enumValue<WaveformState>(root.string("state"))
        val source = opaque(root.string("sourceGeneration"), empty = state != WaveformState.READY)
        val product = root.optionalString("productVersion")?.let { opaque(it) }
        require(root.string("algorithmVersion") == ALGORITHM)
        val payload = setOf("frames", "sampleRate", "channels", "durationSeconds", "bins")
        if (state != WaveformState.READY) {
            require(payload.none(root::containsKey))
            TrackWaveform(requestedId, state, source, product, ALGORITHM)
        } else {
            require(source.isNotEmpty() && product != null)
            val frames = root.long("frames")
            val rate = root.long("sampleRate")
            val channels = root.long("channels")
            require(rate in 1..192_000 && channels in 1..2 && frames in 1..rate * 7200)
            val duration = root.number("durationSeconds")
            val computed = frames.toDouble() / rate
            require(duration.isFinite() && duration > 0 && abs(duration - computed) <= max(1e-9, computed * 1e-12))
            val bins = root.array("bins").map { value -> value.integer().also { require(it in 0..255) }.toInt() }
            require(bins.size == minOf(frames, 2048).toInt())
            TrackWaveform(requestedId, state, source, product, ALGORITHM, frames, rate.toInt(),
                channels.toInt(), duration, bins)
        }
    }

    fun lyrics(bytes: ByteArray, requestedId: String, now: Instant = Instant.now()): TrackLyrics = safely {
        val root = document(bytes, 260 * 1024, setOf("version", "trackId", "recordingVersion", "sourceGeneration",
            "state", "plainText", "lines", "provenance", "attribution", "reason", "storedAt", "expiresAt",
            "nextAttemptAt", "offlineTransferAllowed"))
        require(root.long("version") == 1L && root.string("trackId") == validTrackId(requestedId))
        val transfer = root.getValue("offlineTransferAllowed") as? JsonPrimitive
        require(transfer != null && !transfer.isString && transfer.content == "false")
        val recording = opaque(root.string("recordingVersion"))
        val source = opaque(root.string("sourceGeneration"))
        val state = enumValue<LyricsState>(root.string("state"))
        val plain = root.optionalString("plainText").orEmpty()
        text(plain, 64 * 1024)
        val plainLines = plain.split('\n')
        require(plainLines.size <= 2048)
        plainLines.forEach { text(it, 4096) }
        val lines = root.array("lines").also { require(it.size <= 2048) }.map { element ->
            val line = element as? JsonObject ?: error("Invalid line")
            require(line.keys == setOf("startMs", "text"))
            LyricLine(line.long("startMs"), text(line.string("text"), 4096))
        }
        require(lines.sumOf { it.text.toByteArray(Charsets.UTF_8).size } <= 64 * 1024)
        var previous = -1L
        lines.forEach {
            require(it.startMs in 0..MAX_LYRIC_DURATION_MS && it.startMs >= previous)
            previous = it.startMs
        }
        when (state) {
            LyricsState.PLAIN -> require(plain.isNotBlank() && lines.isEmpty())
            LyricsState.TIMED -> require(lines.isNotEmpty() && lines.any { it.text.isNotBlank() })
            else -> require(plain.isEmpty() && lines.isEmpty())
        }
        val provenance = root.optionalString("provenance")?.let { enumValue<LyricProvenance>(it) }
        val reason = root.optionalString("reason")?.let { enumValue<LyricReason>(it) }
        val attribution = root["attribution"]?.let { element ->
            val value = element as? JsonObject ?: error("Invalid attribution")
            require(value.keys.all { it in setOf("name", "url", "ownership") })
            val name = text(value.string("name"), 256).also { require(it.isNotBlank()) }
            val ownership = enumValue<LyricOwnership>(value.string("ownership"))
            val url = value.optionalString("url")?.let(::attributionUrl)
            LyricAttribution(name, url, ownership)
        }
        val content = state in setOf(LyricsState.PLAIN, LyricsState.TIMED, LyricsState.INSTRUMENTAL)
        if (content) require(attribution != null)
        when (provenance) {
            LyricProvenance.LOCAL_LRC, LyricProvenance.EMBEDDED -> require(attribution?.ownership == LyricOwnership.LIBRARY)
            LyricProvenance.PROVIDER -> require(attribution?.ownership == LyricOwnership.PROVIDER)
            null -> Unit
        }
        val stored = root.optionalString("storedAt")?.also { timestamp(it) }
        val expires = root.optionalString("expiresAt")?.also { timestamp(it) }
        val next = root.optionalString("nextAttemptAt")?.also { timestamp(it) }
        if (content && attribution?.ownership == LyricOwnership.PROVIDER) {
            require(stored != null && expires != null)
            require(timestamp(stored) <= timestamp(expires) && now < timestamp(expires))
        }
        TrackLyrics(requestedId, state, recording, source, plain, lines, provenance, attribution, reason,
            stored, expires, next)
    }

    private fun document(bytes: ByteArray, limit: Int, fields: Set<String>): JsonObject {
        require(bytes.size <= limit)
        val source = bytes.decodeToString(throwOnInvalidSequence = true)
        rejectDuplicateKeys(source)
        val root = json.parseToJsonElement(source) as? JsonObject ?: error("Invalid document")
        require(root.keys.all { it in fields })
        return root
    }

    private fun JsonObject.string(key: String): String = (getValue(key) as? JsonPrimitive)
        ?.takeIf { it.isString }?.content ?: error("Invalid string")
    private fun JsonObject.optionalString(key: String): String? = if (containsKey(key)) string(key) else null
    private fun JsonElement.integer(): Long {
        val value = this as? JsonPrimitive ?: error("Invalid integer")
        require(!value.isString && integer.matches(value.content))
        return value.content.toLong()
    }
    private fun JsonObject.long(key: String): Long = getValue(key).integer()
    private fun JsonObject.number(key: String): Double {
        val value = getValue(key) as? JsonPrimitive ?: error("Invalid number")
        require(!value.isString)
        return value.content.toDouble()
    }
    private fun JsonObject.array(key: String): JsonArray = getValue(key) as? JsonArray ?: error("Invalid array")
    private inline fun <reified T : Enum<T>> enumValue(wire: String): T =
        enumValues<T>().single { it.name.lowercase(java.util.Locale.ROOT) == wire }

    private fun opaque(value: String, empty: Boolean = false): String = text(value, 512).also {
        require((empty || it.isNotBlank()) && it == it.trim() && it.none(Char::isISOControl))
    }

    private fun text(value: String, limit: Int): String = value.also {
        require(it.toByteArray(Charsets.UTF_8).size <= limit)
        require(it.none { character -> character.isISOControl() && character != '\n' && character != '\t' })
        var index = 0
        while (index < it.length) {
            val character = it[index++]
            if (Character.isHighSurrogate(character)) {
                require(index < it.length && Character.isLowSurrogate(it[index++]))
            } else require(!Character.isLowSurrogate(character))
        }
    }

    private fun attributionUrl(value: String): String = text(value, 1024).also {
        require(it.none(Char::isWhitespace) && it.none(Char::isISOControl))
        val uri = URI(it)
        require(uri.scheme == "https" && !uri.isOpaque && !uri.host.isNullOrEmpty() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535))
    }

    private fun timestamp(value: String): Instant {
        require(utc.matches(value))
        return Instant.parse(value)
    }

    /** JsonObject normally keeps the last duplicate; ambiguity must instead reject the whole DTO. */
    private fun rejectDuplicateKeys(source: String) {
        data class Frame(val objectKeys: MutableSet<String>?, var expectingKey: Boolean)
        val stack = mutableListOf<Frame>()
        var index = 0
        while (index < source.length) {
            when (source[index]) {
                '{' -> { require(stack.size < 32); stack.add(Frame(mutableSetOf(), true)) }
                '[' -> { require(stack.size < 32); stack.add(Frame(null, false)) }
                '}', ']' -> { require(stack.isNotEmpty()); stack.removeAt(stack.lastIndex) }
                ',' -> stack.lastOrNull()?.let { it.expectingKey = it.objectKeys != null }
                '"' -> {
                    val start = index++
                    while (index < source.length && source[index] != '"') {
                        if (source[index] == '\\') index++
                        index++
                    }
                    require(index < source.length)
                    stack.lastOrNull()?.let { frame ->
                        if (frame.expectingKey) {
                            val key = json.decodeFromString<String>(source.substring(start, index + 1))
                            require(frame.objectKeys!!.add(key))
                            frame.expectingKey = false
                        }
                    }
                }
            }
            index++
        }
        require(stack.isEmpty())
    }

    private inline fun <T> safely(block: () -> T): T = try { block() } catch (_: Exception) {
        throw TrackContentException(TrackContentFailure.INVALID_RESPONSE)
    }

    internal fun validTrackId(value: String): String = opaque(value)
}
