package org.openhotspot.common.phone

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.openhotspot.common.*

class PhoneEngineTest {
    private fun errorOf(block: () -> Unit) = assertFailsWith<PhoneException> { block() }
    private fun garbage(seed: Int = 1) = Frame(MsgType.ENABLE, seed, ByteArray(40) { (it + seed).toByte() })

    // ---- pairing ----

    @Test fun pairingStoresMatchingLtkAtBothMtus() {
        for (mtu in listOf(23, 247)) {
            val h = Harness()
            val c = h.pair(mtu = mtu)
            val rec = h.store.list().single()
            assertContentEquals(c.phone.ltk, rec.ltk)
            assertEquals("Test Laptop", rec.label)
            assertEquals(0L, rec.lastCounter)
            assertFalse(h.engine.isPairingOpen(), "window closes after success")
        }
    }

    @Test fun pairingWindowIsSingleUse() {
        val h = Harness()
        val qr = h.engine.openPairing()
        val first = ClientPairing(qr, "one", { h.clock.wall })
        first.finish(h.exchange(first.requestFrame()))
        val second = ClientPairing(qr, "two", { h.clock.wall })
        assertEquals(ErrorCode.NOT_IN_PAIRING_MODE, errorOf { second.finish(h.exchange(second.requestFrame())) }.code)
    }

    @Test fun wrongSecretIsRejectedAndThreeFailuresCloseTheWindow() {
        val h = Harness()
        val good = h.engine.openPairing()
        val bad = PairingQr(good.version, good.serviceUuid, good.phonePub, ByteArray(16))
        repeat(3) {
            val p = ClientPairing(bad, "attacker", { h.clock.wall })
            val e = errorOf { p.finish(h.exchange(p.requestFrame())) }
            assertEquals(ErrorCode.AUTH_FAILED, e.code); assertFalse(e.authenticated)
        }
        assertTrue(h.store.list().isEmpty())
        assertFalse(h.engine.isPairingOpen())
        val late = ClientPairing(good, "late", { h.clock.wall })
        assertEquals(ErrorCode.NOT_IN_PAIRING_MODE, errorOf { late.finish(h.exchange(late.requestFrame())) }.code)
    }

    @Test fun pairingWindowExpiresAfter120Seconds() {
        val h = Harness()
        val qr = h.engine.openPairing()
        assertNotNull(h.engine.advertisementPayload(0))
        h.clock.advanceSeconds(121)
        assertNull(h.engine.advertisementPayload(0), "not advertising once expired")
        val p = ClientPairing(qr, "late", { h.clock.wall })
        assertEquals(ErrorCode.NOT_IN_PAIRING_MODE, errorOf { p.finish(h.exchange(p.requestFrame())) }.code)
    }

    @Test fun skewedClientClockGetsAuthenticatedStaleErrorDuringPairing() {
        val h = Harness()
        val e = errorOf { h.pair(clientClock = { h.clock.wall + 120_000 }) }
        assertEquals(ErrorCode.STALE_TIMESTAMP, e.code); assertTrue(e.authenticated)
        assertTrue(h.store.list().isEmpty())
    }

    @Test fun ninthClientIsRefused() {
        val h = Harness()
        repeat(8) { h.pair("c$it") }
        val e = errorOf { h.pair("ninth") }
        assertEquals(ErrorCode.TOO_MANY_CLIENTS, e.code); assertTrue(e.authenticated)
    }

    // ---- requests ----

    @Test fun enableReturnsCredentialsAndPersistsCounter() {
        for (mtu in listOf(23, 247)) {
            val h = Harness()
            val c = h.pair(mtu = mtu)
            val info = c.enable()
            assertEquals(HotspotState.ON, info.state)
            assertEquals("correct horse battery", info.passphrase)
            assertEquals(1, h.controller.enableCalls)
            assertEquals(1L, h.store.list().single().lastCounter)
            assertEquals(HotspotState.ON, c.query().state)
            assertEquals(2L, h.store.list().single().lastCounter)
            c.request(MsgType.DISABLE)
            assertEquals(HotspotState.OFF, c.query().state)
        }
    }

    @Test fun logNeverContainsSecrets() {
        val lines = mutableListOf<String>()
        val h = Harness(log = EngineLog { _, m -> lines += m })
        val qr = h.engine.openPairing()
        val pairing = ClientPairing(qr, "Laptop", { h.clock.wall })
        val phone = TestClient(h, pairing.finish(h.exchange(pairing.requestFrame()))).also { it.enable() }.phone
        h.exchange(garbage())
        val text = lines.joinToString("\n")
        assertTrue(lines.size >= 3)
        for (secret in listOf("correct horse battery", "Fake Phone", phone.ltk.toHex(), qr.secret.toHex(), qr.phonePub.toHex())) {
            assertFalse(text.contains(secret))
        }
    }

    @Test fun queryWhileOffRevealsNothing() {
        val h = Harness()
        val info = h.pair().query()
        assertEquals(HotspotState.OFF, info.state)
        assertEquals(0, info.ssid.size); assertEquals("", info.passphrase)
    }

    @Test fun controllerErrorsAreAuthenticated() {
        for (code in listOf(ErrorCode.SHIZUKU_UNAVAILABLE, ErrorCode.PERMISSION_DENIED, ErrorCode.HOTSPOT_FAILED)) {
            val h = Harness()
            val c = h.pair()
            h.controller.failure = code
            val e = errorOf { c.enable() }
            assertEquals(code, e.code); assertTrue(e.authenticated)
        }
    }

    @Test fun unexpectedBodiesAreMalformed() {
        val h = Harness()
        val c = h.pair()
        assertEquals(ErrorCode.MALFORMED, errorOf { c.request(MsgType.QUERY, byteArrayOf(1)) }.code)
        assertEquals(ErrorCode.MALFORMED, errorOf { c.request(MsgType.ENABLE, byteArrayOf(1)) }.code)
    }

    @Test fun hungHotspotTimesOutAfter20Seconds() = runTest {
        val h = Harness()
        val c = h.pair()
        h.controller.hang = true
        val built = c.build(MsgType.ENABLE, EnableBody.pack())
        val link = h.engine.openLink(247)
        val notes = link.onWrite(Chunking.chunkFrame(built.frame.encode(), 247).single())
        val e = assertFailsWith<PhoneException> {
            c.parse(MsgType.ENABLE, built, c.counter, Frame.decode(notes.single().copyOfRange(1, notes.single().size)))
        }
        assertEquals(ErrorCode.HOTSPOT_FAILED, e.code)
        assertFalse(link.busy.get(), "link is released after the timeout")
    }

    @Test fun statusReflectsControllerAndPairing() {
        val h = Harness()
        assertEquals(Status(1, ready = true, pairingOpen = false, hotspotActive = false), h.engine.status())
        h.engine.openPairing(); h.controller.active = true; h.controller.ready = false
        assertEquals(Status(1, ready = false, pairingOpen = true, hotspotActive = true), h.engine.status())
    }

    @Test fun nonZeroEnableOptionsAreUnsupported() {
        val h = Harness()
        val c = h.pair()
        val e = errorOf { c.request(MsgType.ENABLE, EnableBody.pack(options = 1)) }
        assertEquals(ErrorCode.UNSUPPORTED_REQUEST, e.code); assertTrue(e.authenticated)
        assertEquals(0, h.controller.enableCalls)
    }

    @Test fun pairingLabelMustBeValidUtf8() {
        val h = Harness()
        val qr = h.engine.openPairing()
        val priv = ByteArray(32) { (it + 1).toByte() }
        val dh = org.openhotspot.common.crypto.Crypto.sharedSecret(priv, qr.phonePub)
        val clientPub = org.openhotspot.common.crypto.Crypto.publicKey(priv)
        val keys = Keys.derivePairing(dh, qr.secret, qr.phonePub, clientPub)
        val badLabel = byteArrayOf(0xC3.toByte(), 0x28)
        val plaintext = u64(h.clock.wall) + byteArrayOf(badLabel.size.toByte()) + badLabel
        val header = Frame(MsgType.PAIR_REQ, 3, ByteArray(0)).header
        val frame = Frame(MsgType.PAIR_REQ, 3, clientPub + SealedBody.seal(keys.kPair, header + clientPub, plaintext))
        val reply = h.exchange(frame)
        assertEquals(MsgType.ERROR.code, reply.type)
        assertFalse(reply.plaintext, "authenticated error under K_pair")
        assertTrue(h.store.list().isEmpty())
    }

    // ---- replay / timestamps ----

    @Test fun replayedRequestIsRejectedWithoutActingOrRateLimiting() {
        val h = Harness()
        val c = h.pair()
        val link = h.engine.openLink(247)
        val built = c.build(MsgType.ENABLE, EnableBody.pack(), counter = 5)
        c.parse(MsgType.ENABLE, built, 5, h.exchange(built.frame, link))
        repeat(10) {
            val e = errorOf { c.parse(MsgType.ENABLE, built, 5, h.exchange(built.frame, link)) }
            assertEquals(ErrorCode.REPLAY, e.code); assertTrue(e.authenticated)
        }
        val low = c.build(MsgType.ENABLE, EnableBody.pack(), counter = 4, msgId = 8)
        assertEquals(ErrorCode.REPLAY, errorOf { c.parse(MsgType.ENABLE, low, 4, h.exchange(low.frame, link), 8) }.code)
        assertEquals(1, h.controller.enableCalls)
        assertEquals(0, h.engine.lockoutSeconds())
    }

    @Test fun clientCounterRollbackIsRejected() {
        val h = Harness()
        val c = h.pair()
        c.enable()
        c.counter = 0
        assertEquals(ErrorCode.REPLAY, errorOf { c.enable() }.code)
    }

    @Test fun counterCompareIsUnsigned() {
        val h = Harness()
        val c = h.pair()
        c.counter = -2L // 2^64 - 2
        c.enable()
        c.counter = 5 // wraps "below" the stored huge value
        assertEquals(ErrorCode.REPLAY, errorOf { c.enable() }.code)
    }

    @Test fun staleTimestampIsRejectedInsideAndOutsideTheWindow() {
        val h = Harness()
        val c = h.pair()
        val stale = c.build(MsgType.ENABLE, EnableBody.pack(), ts = h.clock.wall - 120_000)
        val e = errorOf { c.parse(MsgType.ENABLE, stale, c.counter, h.exchange(stale.frame)) }
        assertEquals(ErrorCode.STALE_TIMESTAMP, e.code); assertTrue(e.authenticated)
        assertEquals(0, h.controller.enableCalls)
        val ok = c.build(MsgType.ENABLE, EnableBody.pack(), ts = h.clock.wall - 59_000)
        c.parse(MsgType.ENABLE, ok, c.counter, h.exchange(ok.frame))
        assertEquals(1, h.controller.enableCalls)
    }

    // ---- bad MAC ----

    @Test fun tamperedRequestIsRejectedWithNoEffect() {
        val h = Harness()
        val c = h.pair()
        val built = c.build(MsgType.ENABLE, EnableBody.pack())
        val body = built.frame.body.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val e = errorOf { c.parse(MsgType.ENABLE, built, c.counter, h.exchange(Frame(MsgType.ENABLE, 7, body))) }
        assertEquals(ErrorCode.AUTH_FAILED, e.code); assertFalse(e.authenticated)
        assertEquals(0, h.controller.enableCalls)
        assertEquals(0L, h.store.list().single().lastCounter, "a failed request must not move the counter")
    }

    @Test fun requestFromAnUnpairedKeyIsRejected() {
        val h = Harness()
        h.pair()
        val stranger = Keys.deriveSession(ByteArray(32) { it.toByte() })
        val built = Session.buildRequest(stranger.kReq, MsgType.ENABLE, 7, h.clock.wall, 1, EnableBody.pack())
        val e = errorOf { Session.parseResponse(stranger.kResp, h.exchange(built.frame), MsgType.ENABLE, 7, built.nonce, 1) }
        assertEquals(ErrorCode.AUTH_FAILED, e.code)
    }

    // ---- rate limiting ----

    @Test fun connectionIsToldToCloseAfterThreeFailures() {
        val h = Harness()
        h.pair()
        val link = h.engine.openLink(247)
        repeat(3) { h.exchange(garbage(it), link) }
        assertTrue(link.closeRequested)
        assertFalse(h.engine.openLink(247).closeRequested)
    }

    private fun burst(h: Harness) = repeat(5) { h.exchange(garbage(it)) } // one failure per fresh link

    @Test fun globalLockoutBlocksValidRequestsWithoutDecrypting() {
        val h = Harness()
        val c = h.pair()
        burst(h)
        val lists = h.store.listCalls
        val e = errorOf { c.enable() }
        assertEquals(ErrorCode.RATE_LIMITED, e.code); assertFalse(e.authenticated)
        assertTrue(e.retryAfterSeconds in 1..30)
        assertEquals(lists, h.store.listCalls, "no trial decryption while locked out")
        assertEquals(0, h.controller.enableCalls)
        h.clock.advanceSeconds(31)
        assertEquals(HotspotState.ON, c.enable().state)
    }

    @Test fun lockoutDoublesOnConsecutiveLockouts() {
        val h = Harness()
        val c = h.pair()
        burst(h)
        h.clock.advanceSeconds(31)
        burst(h)
        val e = errorOf { c.enable() }
        assertEquals(ErrorCode.RATE_LIMITED, e.code)
        assertTrue(e.retryAfterSeconds in 32..60, "was ${e.retryAfterSeconds}")
        h.clock.advanceSeconds(61)
        assertEquals(HotspotState.ON, c.enable().state)
    }

    @Test fun lockoutDoublesUpToOneHourThenResetsAfterAQuietHour() {
        val clock = ManualClock()
        val limiter = RateLimiter(clock)
        val observed = mutableListOf<Int>()
        repeat(8) { // consecutive lockouts, each started right after the previous one ended
            repeat(5) { limiter.recordFailure() }
            observed += limiter.retryAfterSeconds()
            clock.advanceSeconds(limiter.retryAfterSeconds().toLong() - 1) // still inside the lockout
            assertTrue(limiter.retryAfterSeconds() > 0)
            clock.advanceSeconds(1)
        }
        assertEquals(listOf(30, 60, 120, 240, 480, 960, 1920, 3600), observed, "doubling, capped at one hour")
        clock.advanceSeconds(3600) // a quiet hour with no failures resets the escalation
        repeat(5) { limiter.recordFailure() }
        assertEquals(30, limiter.retryAfterSeconds())
    }

    @Test fun oldFailuresAgeOutOfTheWindow() {
        val h = Harness()
        val c = h.pair()
        repeat(4) { h.exchange(garbage(it)); h.clock.advanceSeconds(20) }
        assertEquals(HotspotState.ON, c.enable().state)
    }

    @Test fun lockoutSurvivesARestart() {
        var saved: RateLimitSnapshot? = null
        val persistence = object : RateLimitPersistence {
            override fun load() = saved
            override fun save(snapshot: RateLimitSnapshot) { saved = snapshot }
        }
        val h = Harness(persistence = persistence)
        repeat(5) { h.exchange(garbage(it)) }
        assertTrue(h.engine.lockoutSeconds() > 0)
        val restarted = RateLimiter(h.clock, persistence)
        assertTrue(restarted.retryAfterSeconds() in 1..30)
    }

    // ---- revocation and advertising ----

    @Test fun revokedClientIsNoLongerAdvertisedOrServed() {
        val h = Harness()
        val c = h.pair()
        c.enable()
        val id = h.store.list().single().id
        assertTrue(h.engine.revoke(id))
        assertNull(h.engine.advertisementPayload(0))
        val e = errorOf { c.enable() }
        assertEquals(ErrorCode.AUTH_FAILED, e.code); assertFalse(e.authenticated)
        assertFalse(h.engine.revoke(id))
    }

    @Test fun revokingOneClientLeavesOthersWorking() {
        val h = Harness()
        val a = h.pair("a"); val b = h.pair("b")
        h.engine.revoke(h.store.list().first { it.label == "a" }.id)
        assertEquals(HotspotState.OFF, b.query().state)
        assertEquals(ErrorCode.AUTH_FAILED, errorOf { a.query() }.code)
    }

    @Test fun clientInitiatedUnpair() {
        val h = Harness()
        val c = h.pair()
        assertEquals(0, c.request(MsgType.UNPAIR).size)
        assertTrue(h.store.list().isEmpty())
    }

    @Test fun advertisementRotatesThroughClientsAndPairingModeIsExclusive() {
        val h = Harness()
        val clients = listOf(h.pair("a"), h.pair("b"), h.pair("c"))
        assertEquals(3, h.engine.advertisementSlots())
        val now = h.clock.wall / 1000
        val seen = mutableSetOf<Int>()
        for (tick in 0L until 6) {
            val payload = h.engine.advertisementPayload(tick)!!
            val matching = clients.indices.filter { Advertisement.matchesPaired(payload, clients[it].phone.keys.kAdv, now) }
            assertEquals(1, matching.size, "tick $tick matches exactly one client")
            assertEquals(Proto.ADV_MODE_PAIRED, Advertisement.parsePayload(payload)!!.mode)
            seen += matching
        }
        assertEquals(setOf(0, 1, 2), seen)
        val qr = h.engine.openPairing()
        val p = h.engine.advertisementPayload(0)!!
        assertEquals(Proto.ADV_MODE_PAIRING, Advertisement.parsePayload(p)!!.mode)
        assertTrue(Advertisement.matchesPairing(p, qr.secret, now))
        assertEquals(1, h.engine.advertisementSlots())
    }

    @Test fun nothingToAdvertiseWhenIdle() {
        assertNull(Harness().engine.advertisementPayload(0))
        assertEquals(0, Harness().engine.advertisementSlots())
    }

    @Test fun sharedSecretRejectsLowOrderPeerInPairing() {
        val h = Harness()
        h.engine.openPairing()
        val frame = Frame(MsgType.PAIR_REQ, 1, ByteArray(32) + ByteArray(12) + ByteArray(30))
        val e = errorOf { Session.parseResponse(ByteArray(32), h.exchange(frame), MsgType.PAIR_REQ, 1, ByteArray(12), 0) }
        assertFalse(e.authenticated)
    }
}
