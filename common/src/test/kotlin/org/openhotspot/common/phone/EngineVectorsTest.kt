package org.openhotspot.common.phone

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.openhotspot.common.*

/**
 * Interoperability proof: the Kotlin phone engine is fed the frames the Python reference produced
 * (docs/test-vectors.json) and must answer with exactly the bytes the Python reference expects.
 */
class EngineVectorsTest {
    private val v = VectorsTest.v
    private fun obj(n: String) = v.getAsJsonObject(n)
    private fun com.google.gson.JsonObject.hex(n: String) = get(n).asString.hexToBytes()

    private fun reassemble(chunks: List<ByteArray>): ByteArray {
        val r = Reassembler({ 0L })
        return chunks.mapNotNull { r.feed(it) }.single()
    }

    @Test fun engineReproducesPythonPairingAndEnableResponsesByteForByte() = runBlocking {
        val kd = obj("key_derivation"); val qr = obj("qr"); val pm = obj("pairing_messages")
        val enableReq = obj("enable_request"); val enableResp = obj("enable_response")
        val ts = VectorsTest.NOW_S * 1000
        val clock = ManualClock(ts + 120)
        val controller = object : HotspotController {
            override fun isReady() = true
            override fun isActive() = true
            override suspend fun enable(options: Int, autoOffSeconds: Int) =
                HotspotInfo(HotspotState.ON, Security.WPA2_WPA3, Band.GHZ_5, "Nothing Phone (2)".toByteArray(), "correct horse battery")
            override suspend fun disable() {}
            override suspend fun query() = enable(0, 0)
        }
        val store = InMemoryClientStore()
        // the engine draws: phone private key, pairing secret, then one nonce per sealed reply
        val random = ScriptedRandom(qr.hex("phone_private"), qr.hex("pairing_secret"), pm.getAsJsonObject("pair_resp").hex("nonce"), enableResp.hex("nonce"), ByteArray(12))
        val engine = PhoneEngine(store, controller, clock, random)

        val shown = engine.openPairing()
        assertEquals(qr.get("uri").asString, shown.encode(), "QR the phone shows equals the vector QR")
        assertContentEquals(obj("advertisement").hex("pairing_ad_structure"),
            Advertisement.buildAdStructure(Proto.ADV_MODE_PAIRING, Advertisement.pairingToken(shown.secret, ts / 1000)))

        // PAIR_REQ -> PAIR_RESP
        val pairLink = engine.openLink(247)
        val pairReply = reassemble(pairLink.onWrite(Chunking.chunkFrame(pm.getAsJsonObject("pair_req").hex("frame"), 247).single()))
        assertContentEquals(pm.getAsJsonObject("pair_resp").hex("frame"), pairReply)
        val record = store.list().single()
        assertContentEquals(kd.hex("ltk"), record.ltk)
        assertEquals("Test Laptop", record.label)

        // ENABLE (chunked at MTU 23, as in the vector) -> ENABLE_RESP
        clock.wall = ts + 1900
        val link = engine.openLink(23)
        var notes = emptyList<ByteArray>()
        for (c in enableReq.getAsJsonArray("chunks_mtu23")) notes = link.onWrite(c.asString.hexToBytes())
        assertEquals(enableResp.getAsJsonArray("chunks_mtu23").map { it.asString }, notes.map { it.toHex() })
        assertContentEquals(enableResp.hex("frame"), reassemble(notes))
        assertEquals(1L, store.list().single().lastCounter)

        // replaying the very same vector frame is now rejected with an authenticated REPLAY error
        var replayNotes = emptyList<ByteArray>()
        for (c in enableReq.getAsJsonArray("chunks_mtu23")) replayNotes = link.onWrite(c.asString.hexToBytes())
        val ex = runCatching {
            Session.parseResponse(kd.hex("k_resp"), Frame.decode(reassemble(replayNotes)), MsgType.ENABLE, 0x0102, enableReq.hex("nonce"), 1)
        }.exceptionOrNull() as PhoneException
        assertEquals(ErrorCode.REPLAY, ex.code)
    }
}
