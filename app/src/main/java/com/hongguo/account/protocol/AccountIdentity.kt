package com.hongguo.account.protocol

import java.math.BigInteger

/** Account identifiers are opaque provider strings, independent of numeric content/device IDs.
 * The size and character limits are local storage/display safeguards, not a platform format.
 */
internal fun validBusinessAccountId(value: String): Boolean {
    if (value.length !in 1..1024 || value.isBlank() || value.all { it == '0' } ||
        value.equals("null", ignoreCase = true) || value.equals("undefined", ignoreCase = true)) return false
    var at = 0
    while (at < value.length) {
        val point = value.codePointAt(at)
        if (point in 0xD800..0xDFFF || Character.isISOControl(point) || Character.isWhitespace(point) || Character.isSpaceChar(point) ||
            Character.getType(point) in setOf(Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt())) return false
        at += Character.charCount(point)
    }
    return true
}

/** Do not coerce objects, arrays, booleans or floating-point values into an account identity. */
internal fun businessAccountId(raw: Any?): String? {
    val value = when (raw) {
        is String -> raw
        is Byte -> raw.takeIf { it > 0 }?.toString()
        is Short -> raw.takeIf { it > 0 }?.toString()
        is Int -> raw.takeIf { it > 0 }?.toString()
        is Long -> raw.takeIf { it > 0 }?.toString()
        is BigInteger -> raw.takeIf { it.signum() > 0 }?.toString()
        else -> null
    }
    return value?.takeIf(::validBusinessAccountId)
}
