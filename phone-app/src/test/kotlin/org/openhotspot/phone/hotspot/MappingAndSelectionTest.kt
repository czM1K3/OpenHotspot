package org.openhotspot.phone.hotspot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.openhotspot.common.Band
import org.openhotspot.common.ErrorCode
import org.openhotspot.common.Security

@OptIn(ExperimentalCoroutinesApi::class)
class MappingAndSelectionTest {
    @Test fun softApConfigMapsToCredentials() {
        val ok = SoftApMapping.toCredentials("Net".toByteArray(), "passw0rd!", SoftApMapping.SEC_WPA2_PSK, SoftApMapping.BAND_5GHZ)
        val c = assertIs<ConfigRead.Found>(ok).credentials
        assertEquals(Security.WPA2_PSK, c.security); assertEquals(Band.GHZ_5, c.band); assertEquals(CredentialSource.SYSTEM, c.source)
        assertEquals(Security.WPA3_SAE, (SoftApMapping.toCredentials("N".toByteArray(), "passw0rd!", SoftApMapping.SEC_WPA3_SAE, 1) as ConfigRead.Found).credentials.security)
        assertEquals(Security.WPA2_WPA3, (SoftApMapping.toCredentials("N".toByteArray(), "passw0rd!", SoftApMapping.SEC_WPA3_SAE_TRANSITION, 1) as ConfigRead.Found).credentials.security)
        assertEquals(Band.UNKNOWN, SoftApMapping.band(SoftApMapping.BAND_2GHZ or SoftApMapping.BAND_5GHZ), "multi-band masks are unknown")
    }

    @Test fun unusableConfigsAreRejectedWithAReason() {
        fun reason(ssid: ByteArray?, pass: String?, type: Int) =
            assertIs<ConfigRead.Unavailable>(SoftApMapping.toCredentials(ssid, pass, type, 1)).reason
        assertTrue("no saved" in reason(null, "passw0rd!", 1))
        assertTrue("no saved" in reason(ByteArray(0), "passw0rd!", 1))
        assertTrue("open or OWE" in reason("N".toByteArray(), null, SoftApMapping.SEC_OPEN))
        assertTrue("open or OWE" in reason("N".toByteArray(), "passw0rd!", SoftApMapping.SEC_WPA3_OWE))
        assertTrue("passphrase" in reason("N".toByteArray(), null, 1))
        assertTrue("passphrase" in reason("N".toByteArray(), "short", 1))
        assertTrue("32" in reason(ByteArray(33), "passw0rd!", 1))
    }

    @Test fun credentialRules() {
        assertNull(CredentialRules.validate("Phone", "12345678"))
        assertNotNull(CredentialRules.validate("", "12345678"))
        assertNotNull(CredentialRules.validate("x".repeat(33), "12345678"))
        assertNotNull(CredentialRules.validate("é".repeat(17), "12345678"), "32-byte limit is in UTF-8 bytes")
        assertNotNull(CredentialRules.validate("Phone", "1234567"))
        assertNotNull(CredentialRules.validate("Phone", "x".repeat(64)))
        assertNotNull(CredentialRules.validate("Phone", "abcdefg\nh"))
    }

    @Test fun credentialsToStringHidesSecrets() {
        val s = creds("TopSecretNet", "TopSecretPass").toString()
        assertFalse("TopSecret" in s)
    }

    @Test fun failureKindsMapToProtocolErrors() {
        assertEquals(ErrorCode.SHIZUKU_UNAVAILABLE, FailureKind.SHIZUKU_NOT_RUNNING.errorCode)
        assertEquals(ErrorCode.PERMISSION_DENIED, FailureKind.SHIZUKU_NOT_AUTHORIZED.errorCode)
        assertEquals(ErrorCode.HOTSPOT_FAILED, FailureKind.START_FAILED.errorCode)
        assertFalse(FailureKind.NO_CREDENTIALS.allowsFallback)
        assertTrue(FailureKind.START_FAILED.allowsFallback)
    }

    @Test fun tetherErrorNamesMatchTheSdk() {
        assertEquals("NO_CHANGE_TETHERING_PERMISSION", TetherErrors.name(14))
        assertEquals("DUPLICATE_REQUEST", TetherErrors.name(18))
        assertEquals("UNKNOWN(99)", TetherErrors.name(99))
    }

    @Test fun redactorRemovesSecretsAndTruncates() {
        assertEquals("ssid <redacted> pw <redacted>", Redactor.redact("ssid  Foo \n pw Bar123", listOf("Foo", "Bar123")))
        assertEquals(10 + 1, Redactor.redact("x".repeat(50), emptyList(), maxLen = 10).length)
    }

    @Test fun aHangingProbeTimesOutAndTheNextBackendIsStillConsidered() = runTest {
        val base = StubBackend(BackendId.SHIZUKU_TETHERING)
        val hanging = object : HotspotBackend by base {
            override suspend fun probe(): ProbeResult { delay(60_000); return ProbeResult.Available("late") }
        }
        val ok = StubBackend(BackendId.SHELL_COMMAND)
        val report = BackendSelector(listOf(hanging, ok), FakeSettings(), TestClock(mono = { testScheduler.currentTime }), probeTimeoutMs = 1_000).probeAll()
        assertEquals(FailureKind.TIMEOUT, (report.entries[0].result as ProbeResult.Unavailable).kind)
        assertEquals(BackendId.SHELL_COMMAND, report.chosen)
    }
}
