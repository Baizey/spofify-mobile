package dev.spofify.mobile.core.playback

private const val MAX_DEVICE_NAME_BYTES = 256
private const val MAX_DEVICE_NAME_CODE_POINTS = 64

/** Same rule for admission, rename and UI: original byte/control bounds, trimmed character count. */
fun isValidDeviceName(value: String): Boolean {
    if (value.isEmpty() || value.length > MAX_DEVICE_NAME_BYTES || value.any(Char::isISOControl)) return false
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (Character.isHighSurrogate(character)) {
            if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
            index += 2
        } else {
            if (Character.isLowSurrogate(character)) return false
            index++
        }
    }
    if (value.toByteArray(Charsets.UTF_8).size > MAX_DEVICE_NAME_BYTES) return false
    // The server also trims FEFF for the browser's String.trim character-count contract.
    val trimmed = value.trim { it.isWhitespace() || it == '\uFEFF' }
    return trimmed.isNotEmpty() && trimmed.codePointCount(0, trimmed.length) <= MAX_DEVICE_NAME_CODE_POINTS
}
