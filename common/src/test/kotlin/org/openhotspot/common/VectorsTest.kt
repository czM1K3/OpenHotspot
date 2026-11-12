package org.openhotspot.common

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.openhotspot.common.crypto.Crypto

/** Reproduces every value in docs/test-vectors.json, which is also verified by the Python client. */
class VectorsTest {
    companion object {
        val v: JsonObject = JsonParser.parseString(File(System.getProperty("vectors.path")).readText()).asJsonObject
        fun obj(name: String) = v.getAsJsonObject(name)
        fun JsonObject.hex(name: String) = get(name).asString.hexToBytes()
        fun JsonObject.str(name: String) = get(name).asString
        fun JsonObject.long(name: String) = get(name).asLong
        const val NOW_S = 1_800_000_000L
    }

    private val kd = obj("key_derivation")

    @Test fun qr() {
        val q = obj("qr")
        val qr = PairingQr.parse(q.str("uri"))
        assertEquals(q.hex("phone_public").toHex(), qr.phonePub.toHex())
        assertEquals(q.hex("pairing_secret").toHex(), qr.secret.toHex())
        assertEquals(q.str("uri"), qr.encode())
        assertContentEquals(q.hex("phone_public"), Crypto.publicKey(q.hex("phone_private")))
        assertContentEquals(q.hex("raw"), byteArrayOf(1) + qr.serviceUuid + qr.phonePub + qr.secret)
    }

    @Test fun uuidsAndVersionMatchTheVectors() {
        val u = obj("uuids")
        assertEquals(u.str("service"), Proto.SERVICE_UUID)
        assertEquals(u.str("request"), Proto.CHAR_REQUEST_UUID)
        assertEquals(u.str("response"), Proto.CHAR_RESPONSE_UUID)
        assertEquals(u.str("status"), Proto.CHAR_STATUS_UUID)
        assertEquals(v.get("protocol_version").asInt, Proto.VERSION)
        assertEquals(obj("advertisement").get("window_seconds").asLong, 300L)
    }

    @Test fun qrRejectsGarbage() {
        listOf("nope", "openhotspot://pair/AAAA", "openhotspot://pair/!!!").forEach {
            assertFailsWith<PairingException> { PairingQr.parse(it) }
        }
        val wrongService = PairingQr(1, ByteArray(16), ByteArray(32), ByteArray(16)).encode()
        assertFailsWith<PairingException> { PairingQr.parse(wrongService) }
    }

    @Test fun keyDerivation() {
        val qr = PairingQr.parse(obj("qr").str("uri"))
        val clientPriv = kd.hex("client_private")
        val clientPub = Crypto.publicKey(clientPriv)
        assertContentEquals(kd.hex("client_public"), clientPub)
        val dh = Crypto.sharedSecret(clientPriv, qr.phonePub)
        assertContentEquals(kd.hex("x25519_shared"), dh)
        assertContentEquals(kd.hex("hkdf_info_transcript"), Proto.SERVICE_UUID_BYTES + qr.phonePub + clientPub)
        val keys = Keys.derivePairing(dh, qr.secret, qr.phonePub, clientPub)
        assertContentEquals(kd.hex("k_pair"), keys.kPair)
        assertContentEquals(kd.hex("ltk"), keys.ltk)
        val s = Keys.deriveSession(keys.ltk)
        assertContentEquals(kd.hex("k_req"), s.kReq)
        assertContentEquals(kd.hex("k_resp"), s.kResp)
        assertContentEquals(kd.hex("k_adv"), s.kAdv)
    }

    @Test fun advertisement() {
        val a = obj("advertisement")
        val now = a.long("unix_seconds")
        assertEquals(a.long("window_index"), Advertisement.windowIndex(now))
        val paired = Advertisement.pairedToken(kd.hex("k_adv"), now)
        val pairing = Advertisement.pairingToken(obj("qr").hex("pairing_secret"), now)
        assertContentEquals(a.hex("paired_token"), paired)
        assertContentEquals(a.hex("pairing_token"), pairing)
        assertContentEquals(a.hex("paired_ad_structure"), Advertisement.buildAdStructure(Proto.ADV_MODE_PAIRED, paired))
        assertContentEquals(a.hex("pairing_ad_structure"), Advertisement.buildAdStructure(Proto.ADV_MODE_PAIRING, pairing))
        val payload = Advertisement.buildPayload(Proto.ADV_MODE_PAIRED, paired)
        for (drift in listOf(-300L, 0L, 300L)) assertTrue(Advertisement.matchesPaired(payload, kd.hex("k_adv"), now + drift))
        assertFalse(Advertisement.matchesPaired(payload, kd.hex("k_adv"), now + 900))
        assertFalse(Advertisement.matchesPaired(payload, ByteArray(32), now))
        assertFalse(Advertisement.matchesPairing(payload, obj("qr").hex("pairing_secret"), now)) // wrong mode
    }

    private fun clientPairing(): ClientPairing {
        val qr = PairingQr.parse(obj("qr").str("uri"))
        val req = obj("pairing_messages").getAsJsonObject("pair_req")
        return ClientPairing(qr, "Test Laptop", { NOW_S * 1000 }, privateKey = kd.hex("client_private"), nonce = req.hex("nonce"), msgId = 1)
    }

    @Test fun pairReqFrameIsReproducedByteForByte() {
        val req = obj("pairing_messages").getAsJsonObject("pair_req")
        assertContentEquals(req.hex("frame"), clientPairing().requestFrame().encode())
    }

    @Test fun pairRespCompletesPairing() {
        val pairing = clientPairing()
        pairing.requestFrame()
        val resp = obj("pairing_messages").getAsJsonObject("pair_resp")
        val phone = pairing.finish(Frame.decode(resp.hex("frame")))
        assertContentEquals(kd.hex("ltk"), phone.ltk)
        assertContentEquals(resp.hex("key_confirmation"), Keys.pairConfirm(phone.ltk, pairing.clientPub, phone.phonePub))
    }

    @Test fun pairRespWithBadConfirmationIsRejected() {
        val pairing = clientPairing()
        pairing.requestFrame()
        val raw = obj("pairing_messages").getAsJsonObject("pair_resp").hex("frame")
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        assertFailsWith<AuthenticationException> { pairing.finish(Frame.decode(raw)) }
    }

    @Test fun enableRequest() {
        val er = obj("enable_request")
        val built = Session.buildRequest(kd.hex("k_req"), MsgType.ENABLE, 0x0102, NOW_S * 1000 + 1000, 1, EnableBody.pack(0, 0), er.hex("nonce"))
        assertContentEquals(er.hex("header"), built.frame.header)
        assertContentEquals(er.hex("frame"), built.frame.encode())
        assertEquals(er.getAsJsonArray("chunks_mtu23").map { it.asString }, Chunking.chunkFrame(built.frame.encode(), 23).map { it.toHex() })
        val opened = SealedBody.open(kd.hex("k_req"), built.frame.header, built.frame.body)
        assertContentEquals(er.hex("plaintext"), opened.plaintext)
    }

    @Test fun enableResponse() {
        val er = obj("enable_request")
        val rs = obj("enable_response")
        val body = Session.parseResponse(kd.hex("k_resp"), Frame.decode(rs.hex("frame")), MsgType.ENABLE, 0x0102, er.hex("nonce"), 1, NOW_S * 1000 + 1900)
        assertContentEquals(rs.hex("body"), body)
        val info = HotspotInfo.decode(body)
        assertEquals("Nothing Phone (2)", info.ssid.toString(Charsets.UTF_8))
        assertEquals("correct horse battery", info.passphrase)
        assertEquals(Security.WPA2_WPA3, info.security)
        assertEquals(Band.GHZ_5, info.band)
        assertContentEquals(body, info.encode())
        for ((key, mtu) in listOf("chunks_mtu23" to 23, "chunks_mtu247" to 247)) {
            assertEquals(rs.getAsJsonArray(key).map { it.asString }, Chunking.chunkFrame(rs.hex("frame"), mtu).map { it.toHex() })
            val r = Reassembler({ 0L })
            val out = rs.getAsJsonArray(key).map { it.asString.hexToBytes() }.mapNotNull { r.feed(it) }.single()
            assertContentEquals(rs.hex("frame"), out)
        }
    }

    @Test fun responseIsBoundToItsRequest() {
        val er = obj("enable_request")
        val frame = Frame.decode(obj("enable_response").hex("frame"))
        assertFailsWith<AuthenticationException> { // replayed against a different request nonce
            Session.parseResponse(kd.hex("k_resp"), frame, MsgType.ENABLE, 0x0102, ByteArray(12), 1)
        }
        assertFailsWith<AuthenticationException> { // request key used as the response key: no reflection
            Session.parseResponse(kd.hex("k_req"), frame, MsgType.ENABLE, 0x0102, er.hex("nonce"), 1)
        }
        assertFailsWith<ProtocolException> { // wrong counter echo
            Session.parseResponse(kd.hex("k_resp"), frame, MsgType.ENABLE, 0x0102, er.hex("nonce"), 2)
        }
    }

    @Test fun tamperedRequestFails() {
        val er = obj("enable_request")
        assertFailsWith<AuthenticationException> {
            Crypto.open(kd.hex("k_req"), er.hex("nonce"), er.hex("aad"), obj("negative").hex("tampered_enable_request_ciphertext_and_tag"))
        }
    }

    @Test fun unauthenticatedErrorFrame() {
        val frame = Frame.decode(obj("error_unauthenticated").hex("frame"))
        assertTrue(frame.type == MsgType.ERROR.code && frame.plaintext)
        val e = assertFailsWith<PhoneException> { Session.parseResponse(ByteArray(32), frame, MsgType.ENABLE, 0x0102, ByteArray(12), 1) }
        assertEquals(ErrorCode.AUTH_FAILED, e.code)
        assertEquals(30, e.retryAfterSeconds)
        assertFalse(e.authenticated)
    }
}
