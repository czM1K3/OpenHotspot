package org.openhotspot.phone.data

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
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.openhotspot.common.ClientPairing
import org.openhotspot.common.EnableBody
import org.openhotspot.common.ErrorCode
import org.openhotspot.common.Frame
import org.openhotspot.common.HotspotInfo
import org.openhotspot.common.HotspotState
import org.openhotspot.common.MsgType
import org.openhotspot.common.PhoneException
import org.openhotspot.common.Chunking
import org.openhotspot.common.Reassembler
import org.openhotspot.common.Session
import org.openhotspot.common.phone.ClientRecord
import org.openhotspot.common.phone.Clock
import org.openhotspot.common.phone.EngineLog
import org.openhotspot.common.phone.LogLevel
import org.openhotspot.common.phone.PhoneEngine
import org.openhotspot.phone.hotspot.FakeBackend

/** A plain-JVM AES-GCM key standing in for the Android Keystore key (same blob format and code path). */
private class JvmCipher(private val k: SecretKey = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")) : AesGcmCipher() {
    override fun key() = k
}

class EncryptedClientStoreTest {
    private lateinit var dir: File
    private lateinit var file: File
    private val cipher = JvmCipher()

    @BeforeTest fun setUp() { dir = Files.createTempDirectory("ohs").toFile(); file = File(dir, "clients.bin") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun record(id: String, ltkByte: Int, label: String = "Laptop $id", counter: Long = 0) =
        ClientRecord(id, ByteArray(32) { ltkByte.toByte() }, label, 1_700_000_000_000L, counter)

    @Test fun recordsSurviveARestart() {
        val a = EncryptedClientStore(file, cipher)
        a.add(record("aa", 1)); a.add(record("bb", 2, "Tablet ünï"))
        a.updateCounter("bb", -2L) // unsigned 2^64-2 must round-trip
        val b = EncryptedClientStore(file, cipher)
        assertEquals(listOf("aa", "bb"), b.list().map { it.id })
        assertContentEquals(ByteArray(32) { 2 }, b.list()[1].ltk)
        assertEquals("Tablet ünï", b.list()[1].label)
        assertEquals(-2L, b.list()[1].lastCounter)
        assertTrue(b.remove("aa")); assertFalse(b.remove("aa"))
        assertEquals(listOf("bb"), EncryptedClientStore(file, cipher).list().map { it.id })
    }

    @Test fun nothingReadableIsStoredOnDisk() {
        val ltk = ByteArray(32) { (it * 7 + 3).toByte() }
        EncryptedClientStore(file, cipher).add(ClientRecord("aa", ltk, "SecretLabelXYZ", 0, 0))
        val raw = file.readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("SecretLabelXYZ"))
        assertFalse(String(raw, Charsets.ISO_8859_1).contains(String(ltk, Charsets.ISO_8859_1)))
        assertFalse(File(file.path + ".tmp").exists(), "no temp file left behind")
    }

    @Test fun flowReflectsChanges() {
        val s = EncryptedClientStore(file, cipher)
        assertTrue(s.clients.value.isEmpty())
        s.add(record("aa", 1))
        assertEquals(1, s.clients.value.size)
        s.remove("aa")
        assertTrue(s.clients.value.isEmpty())
    }

    @Test fun tamperedFileIsRejectedAndKeptAsCorrupt() {
        EncryptedClientStore(file, cipher).add(record("aa", 1))
        val raw = file.readBytes().also { it[20] = (it[20].toInt() xor 1).toByte() }
        file.writeBytes(raw)
        val errors = mutableListOf<String>()
        val s = EncryptedClientStore(file, cipher, EngineLog { l, m -> if (l == LogLevel.ERROR) errors += m })
        assertTrue(s.list().isEmpty(), "a file that fails authentication must never be trusted")
        assertEquals(1, errors.size)
        s.add(record("bb", 2)) // first write must not destroy the unreadable file
        assertContentEquals(raw, File(file.path + ".corrupt").readBytes())
        assertEquals(listOf("bb"), EncryptedClientStore(file, cipher).list().map { it.id })
    }

    @Test fun lostKeyBehavesLikeCorruption() {
        EncryptedClientStore(file, cipher).add(record("aa", 1))
        val other = EncryptedClientStore(file, JvmCipher())
        assertTrue(other.list().isEmpty())
    }

    // ---- the real engine on top of the encrypted store, with the fake backend ----

    private class FixedClock(var now: Long = 1_800_000_000_000L) : Clock {
        override fun wallMs() = now
        override fun monotonicMs() = now
    }

    private fun exchange(engine: PhoneEngine, frame: Frame): Frame = runBlocking {
        val link = engine.openLink(247)
        var notes = emptyList<ByteArray>()
        for (c in Chunking.chunkFrame(frame.encode(), 247)) notes = link.onWrite(c)
        val r = Reassembler({ 0L })
        Frame.decode(notes.mapNotNull { r.feed(it) }.single())
    }

    @Test fun validRequestReturnsFakeCredentialsAndCounterSurvivesARestart() {
        val clock = FixedClock()
        val backend = FakeBackend(simulatedDelayMs = 0)
        val engine = PhoneEngine(EncryptedClientStore(file, cipher), backend, clock)

        val pairing = ClientPairing(engine.openPairing(), "Test Laptop", { clock.now })
        val phone = pairing.finish(exchange(engine, pairing.requestFrame()))

        val built = Session.buildRequest(phone.keys.kReq, MsgType.ENABLE, 1, clock.now, 1, EnableBody.pack())
        val body = Session.parseResponse(phone.keys.kResp, exchange(engine, built.frame), MsgType.ENABLE, 1, built.nonce, 1, clock.now)
        val info = HotspotInfo.decode(body)
        assertEquals(HotspotState.ON, info.state)
        assertEquals(FakeBackend.FAKE_SSID, info.ssid.toString(Charsets.UTF_8))
        assertEquals(FakeBackend.FAKE_PASSPHRASE, info.passphrase)

        // "Restart" the phone: same file, new objects. The stored counter must still block a replay.
        val restarted = PhoneEngine(EncryptedClientStore(file, cipher), FakeBackend(simulatedDelayMs = 0), clock)
        val replay = runCatching {
            Session.parseResponse(phone.keys.kResp, exchange(restarted, built.frame), MsgType.ENABLE, 1, built.nonce, 1)
        }.exceptionOrNull() as PhoneException
        assertEquals(ErrorCode.REPLAY, replay.code)
        assertTrue(replay.authenticated)

        val next = Session.buildRequest(phone.keys.kReq, MsgType.QUERY, 2, clock.now, 2)
        val q = HotspotInfo.decode(Session.parseResponse(phone.keys.kResp, exchange(restarted, next.frame), MsgType.QUERY, 2, next.nonce, 2))
        assertEquals(HotspotState.OFF, q.state, "the restarted FakeBackend starts off")
    }

    @Test fun revokedClientCannotUseItsOldKeyAfterARestart() {
        val clock = FixedClock()
        val store = EncryptedClientStore(file, cipher)
        val engine = PhoneEngine(store, FakeBackend(simulatedDelayMs = 0), clock)
        val pairing = ClientPairing(engine.openPairing(), "Laptop", { clock.now })
        val phone = pairing.finish(exchange(engine, pairing.requestFrame()))
        assertTrue(engine.revoke(store.list().single().id))
        val restarted = PhoneEngine(EncryptedClientStore(file, cipher), FakeBackend(simulatedDelayMs = 0), clock)
        assertTrue(restarted.clients().isEmpty())
        val built = Session.buildRequest(phone.keys.kReq, MsgType.QUERY, 1, clock.now, 1)
        val e = runCatching { Session.parseResponse(phone.keys.kResp, exchange(restarted, built.frame), MsgType.QUERY, 1, built.nonce, 1) }
            .exceptionOrNull() as PhoneException
        assertEquals(ErrorCode.AUTH_FAILED, e.code)
    }
}
