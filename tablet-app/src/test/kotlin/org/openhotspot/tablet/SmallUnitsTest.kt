package org.openhotspot.tablet

import android.Manifest
import android.os.Build
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.openhotspot.common.ErrorCode
import org.openhotspot.common.PairingQr
import org.openhotspot.common.PhoneException
import org.openhotspot.common.Proto
import org.openhotspot.common.Security
import org.openhotspot.tablet.ble.BleException
import org.openhotspot.tablet.ble.BleProblem
import org.openhotspot.tablet.client.ErrorText
import org.openhotspot.tablet.client.HotspotNotReadyException
import org.openhotspot.tablet.client.Labels
import org.openhotspot.tablet.client.PhoneNotFoundException
import org.openhotspot.tablet.data.AesGcmCipher
import org.openhotspot.tablet.data.EncryptedPhoneStore
import org.openhotspot.tablet.data.JoinMethod
import org.openhotspot.tablet.data.PhoneRecord
import org.openhotspot.tablet.perm.Permissions
import org.openhotspot.tablet.qr.QrGate
import org.openhotspot.tablet.ui.connectedNote
import org.openhotspot.tablet.ui.cooldownLabel
import org.openhotspot.tablet.ui.stageText
import org.openhotspot.tablet.flow.Stage
import org.openhotspot.tablet.wifi.NetworkProbe
import org.openhotspot.tablet.wifi.ProbeResult
import org.openhotspot.tablet.wifi.WifiJoinException
import org.openhotspot.tablet.wifi.WifiProblem
import org.openhotspot.tablet.wifi.WifiSpec

/** A plain-JVM AES-GCM key standing in for the Keystore key (same blob format and code path). */
private class JvmCipher(private val k: SecretKey = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")) : AesGcmCipher() {
    override fun key() = k
}

class EncryptedPhoneStoreTest {
    private lateinit var dir: File
    private lateinit var file: File
    private val cipher = JvmCipher()

    @BeforeTest fun setUp() { dir = Files.createTempDirectory("ohs-tablet").toFile(); file = File(dir, "phones.bin") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun rec(id: String, b: Int, name: String = "Phone $id") =
        PhoneRecord(id, ByteArray(32) { b.toByte() }, ByteArray(32) { (b + 1).toByte() }, "My tablet ünï", name, 1_700_000_000_000L)

    @Test fun recordsAndCountersSurviveARestart() {
        val a = EncryptedPhoneStore(file, cipher)
        a.add(rec("aa", 1)); a.add(rec("bb", 2, "Zażółć"))
        assertEquals(1, a.nextCounter("bb")); assertEquals(2, a.nextCounter("bb"))
        val b = EncryptedPhoneStore(file, cipher)
        assertEquals(listOf("aa", "bb"), b.list().map { it.phoneId })
        assertEquals(2, b.list()[1].lastCounter)
        assertEquals("Zażółć", b.list()[1].name)
        assertEquals("My tablet ünï", b.list()[1].ourLabel)
        assertContentEquals(ByteArray(32) { 2 }, b.list()[1].ltk)
        assertContentEquals(ByteArray(32) { 3 }, b.list()[1].phonePub)
        assertEquals(3, b.nextCounter("bb")) // continues, never repeats
    }

    @Test fun counterIsOnDiskBeforeNextCounterReturns() {
        val s = EncryptedPhoneStore(file, cipher)
        s.add(rec("aa", 1))
        val c = s.nextCounter("aa")
        // A second store reading the file (a "crash" right after the call) already sees it.
        assertEquals(c, EncryptedPhoneStore(file, cipher).list().single().lastCounter)
    }

    @Test fun renameAndRemove() {
        val s = EncryptedPhoneStore(file, cipher)
        s.add(rec("aa", 1)); s.rename("aa", "Work phone")
        assertEquals("Work phone", EncryptedPhoneStore(file, cipher).list().single().name)
        assertTrue(s.remove("aa")); assertFalse(s.remove("aa"))
        assertTrue(EncryptedPhoneStore(file, cipher).list().isEmpty())
    }

    @Test fun fileIsCiphertextOnly() {
        EncryptedPhoneStore(file, cipher).add(rec("aa", 7))
        val raw = file.readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("My tablet"))
        assertFalse(raw.toList().windowed(32).any { w -> w.all { it == 7.toByte() } }) // the key bytes are not visible
    }

    @Test fun anUnreadableFileIsKeptNotOverwritten() {
        EncryptedPhoneStore(file, cipher).add(rec("aa", 1))
        val problems = mutableListOf<String>()
        val other = EncryptedPhoneStore(file, JvmCipher()) { problems += it } // a different key: cannot decrypt
        assertTrue(other.list().isEmpty())
        assertEquals(1, problems.size)
        other.add(rec("bb", 2))
        assertTrue(File(file.path + ".corrupt").exists())
    }

    @Test fun tamperingIsDetected() {
        EncryptedPhoneStore(file, cipher).add(rec("aa", 1))
        val raw = file.readBytes(); raw[20] = (raw[20].toInt() xor 1).toByte(); file.writeBytes(raw)
        assertTrue(EncryptedPhoneStore(file, cipher).list().isEmpty())
    }
}

class QrGateTest {
    private fun validQr(): String {
        val raw = ByteArray(Proto.QR_RAW_LEN)
        raw[0] = 1
        Proto.SERVICE_UUID_BYTES.copyInto(raw, 1)
        return Proto.QR_PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    @Test fun acceptsAPairingCodeExactlyOnce() {
        val got = mutableListOf<PairingQr>()
        val g = QrGate({ got += it })
        repeat(30) { g.offer(validQr()) } // a camera reports the same code every frame
        assertEquals(1, got.size)
        g.reset()
        g.offer(validQr())
        assertEquals(2, got.size)
    }

    @Test fun ignoresOtherCodesAndReportsEachOnce() {
        val rejected = mutableListOf<String>()
        val g = QrGate({ error("should not accept") }, { rejected += it })
        repeat(10) { g.offer("https://example.com") }
        g.offer("openhotspot://pair/AAAA")
        assertEquals(2, rejected.size)
        assertTrue(rejected.none { it.contains("example.com") || it.contains("AAAA") }) // payloads are never echoed
    }
}

class LabelsTest {
    @Test fun fitsTheProtocolLimitOnCharacterBoundaries() {
        assertEquals("Samsung SM-X710", Labels.fit("  Samsung SM-X710 "))
        val long = Labels.fit("ż".repeat(40)) // 2 bytes each
        assertTrue(long.toByteArray().size <= Proto.MAX_LABEL_BYTES)
        assertEquals("ż".repeat(16), long)
        val emoji = Labels.fit("a" + "😀".repeat(10)) // 4 bytes, two UTF-16 units each
        assertTrue(emoji.toByteArray().size <= Proto.MAX_LABEL_BYTES)
        assertEquals(emoji, String(emoji.toByteArray(Charsets.UTF_8), Charsets.UTF_8)) // no half characters
        assertEquals("Android tablet", Labels.fit("   "))
    }
}

class PermissionsTest {
    @Test fun perApiLevel() {
        // API 30: scanning needs location; no runtime Nearby-devices permission exists yet.
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), Permissions.forConnecting(Build.VERSION_CODES.R))
        // API 31/32: Nearby devices only.
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            Permissions.forConnecting(Build.VERSION_CODES.S_V2),
        )
        // API 33+: plus Wi-Fi, and notifications for the service.
        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in Permissions.forConnecting(Build.VERSION_CODES.TIRAMISU))
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in Permissions.forAutoService(Build.VERSION_CODES.TIRAMISU))
        assertFalse(Manifest.permission.POST_NOTIFICATIONS in Permissions.forConnecting(Build.VERSION_CODES.TIRAMISU))
        assertTrue(Manifest.permission.CAMERA in Permissions.forPairing(Build.VERSION_CODES.R))
        // Location is never requested where it is not needed.
        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in Permissions.forAutoService(Build.VERSION_CODES.TIRAMISU))
    }
}

class ErrorTextTest {
    @Test fun everyPhoneErrorCodeHasAMessage() {
        for (c in ErrorCode.entries) {
            val e = ErrorText.describe(PhoneException(c, c.code, 7, authenticated = true))
            assertTrue(e.title.isNotBlank(), "no title for $c")
        }
        assertTrue(ErrorText.describe(PhoneException(null, 0x77, 0, true)).hint!!.contains("0x77"))
    }

    @Test fun unauthenticatedAuthFailureSaysThePairingWasKept() {
        val e = ErrorText.describe(PhoneException(ErrorCode.AUTH_FAILED, 3, 0, authenticated = false))
        assertTrue(e.hint!!.contains("kept"))
        val auth = ErrorText.describe(PhoneException(ErrorCode.AUTH_FAILED, 3, 0, authenticated = true))
        assertFalse(auth.hint!!.contains("kept"))
    }

    @Test fun rateLimitShowsTheWaitTime() {
        assertTrue(ErrorText.describe(PhoneException(ErrorCode.RATE_LIMITED, 4, 42, false)).hint!!.contains("42"))
    }

    @Test fun otherLayersAreTranslated() {
        assertEquals("Bluetooth is off", ErrorText.describe(BleException(BleProblem.BLUETOOTH_OFF, "x")).title)
        assertEquals("Phone not found nearby", ErrorText.describe(PhoneNotFoundException()).title)
        assertEquals("Wi-Fi is off", ErrorText.describe(WifiJoinException(WifiProblem.WIFI_OFF)).title)
        assertNotNull(ErrorText.describe(WifiJoinException(WifiProblem.NOT_AVAILABLE)).hint)
        assertTrue(ErrorText.describe(HotspotNotReadyException("late")).hint!!.contains("late"))
        // An unknown exception does not leak a stack trace or a long message.
        assertTrue(ErrorText.describe(IllegalStateException("x".repeat(500))).hint!!.length <= 120)
    }

    @Test fun noMessageEverContainsAPassphrase() {
        val spec = WifiSpec("SSID".toByteArray(), "hunter2hunter2", Security.WPA2_PSK, false)
        assertFalse(spec.toString().contains("hunter2"))
        assertFalse(spec.toString().contains("SSID"))
    }
}

class NetworkProbeTest {
    @Test fun interpretsTheCaptivePortalCheck() {
        assertEquals(ProbeResult.Online, NetworkProbe.interpret(204))
        assertEquals(ProbeResult.CaptivePortal, NetworkProbe.interpret(200))
        assertEquals(ProbeResult.CaptivePortal, NetworkProbe.interpret(302))
        assertEquals(ProbeResult.Offline("HTTP 503"), NetworkProbe.interpret(503))
        assertEquals(ProbeResult.Offline("HTTP 404"), NetworkProbe.interpret(404))
    }
}

class UiTextTest {
    @Test fun joiningTextTellsTheUserToApproveThePrompt() {
        assertTrue(stageText(Stage.JOINING, JoinMethod.SPECIFIER).contains("Approve"))
        assertFalse(stageText(Stage.JOINING, JoinMethod.SUGGESTION).contains("Approve"))
        Stage.entries.forEach { assertTrue(stageText(it, JoinMethod.SPECIFIER).isNotBlank()) }
    }

    @Test fun theSpecifierLimitationIsStatedPlainly() {
        assertTrue(connectedNote(JoinMethod.SPECIFIER).contains("OpenHotspot only"))
        assertTrue(connectedNote(JoinMethod.SUGGESTION).contains("whole tablet"))
    }

    @Test fun cooldownLabels() {
        assertEquals("1 min", cooldownLabel(60)); assertEquals("15 min", cooldownLabel(900)); assertEquals("1 h", cooldownLabel(3600))
    }
}
