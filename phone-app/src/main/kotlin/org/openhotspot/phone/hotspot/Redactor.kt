package org.openhotspot.phone.hotspot

/** Removes secrets from text before it is logged. The log and the diagnostics export must never contain an SSID or passphrase. */
object Redactor {
    fun redact(text: String, secrets: Collection<String>, maxLen: Int = 400): String {
        var out = text
        for (s in secrets) if (s.isNotEmpty()) out = out.replace(s, "<redacted>")
        out = out.trim().replace(Regex("\\s+"), " ")
        return if (out.length > maxLen) out.take(maxLen) + "…" else out
    }
}
