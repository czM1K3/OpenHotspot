package org.openhotspot.tablet.qr

import org.openhotspot.common.PairingException
import org.openhotspot.common.PairingQr

/**
 * Filters what the camera reads. Cameras report the same code many times a second and also see
 * unrelated codes; only an OpenHotspot pairing QR that parses is accepted, once.
 */
class QrGate(private val onPairingQr: (PairingQr) -> Unit, private val onRejected: (String) -> Unit = {}) {
    private var accepted = false
    private var lastRejected: String? = null

    @Synchronized
    fun offer(text: String) {
        if (accepted) return
        val qr = try {
            PairingQr.parse(text)
        } catch (e: PairingException) {
            // Report each distinct bad code once, not once per frame. Never echo the payload: it may hold a secret.
            if (text != lastRejected) {
                lastRejected = text
                onRejected(e.message ?: "Not a pairing code")
            }
            return
        }
        accepted = true
        onPairingQr(qr)
    }

    @Synchronized fun reset() { accepted = false; lastRejected = null }
}
