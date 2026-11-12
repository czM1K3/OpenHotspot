package org.openhotspot.tablet.client

import org.openhotspot.common.Proto

/** The label the phone shows for this tablet: at most [Proto.MAX_LABEL_BYTES] bytes of UTF-8 (PROTOCOL.md 4.3). */
object Labels {
    fun fit(label: String, fallback: String = "Android tablet"): String {
        val trimmed = label.trim().ifEmpty { fallback }
        var out = trimmed
        while (out.toByteArray(Charsets.UTF_8).size > Proto.MAX_LABEL_BYTES) out = out.dropLast(1) // drops whole characters, never half of one
        // A trailing high surrogate would be an invalid half character; dropLast(1) works on UTF-16 units.
        if (out.isNotEmpty() && Character.isHighSurrogate(out.last())) out = out.dropLast(1)
        return out.trimEnd().ifEmpty { fallback }
    }
}
