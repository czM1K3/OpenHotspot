package org.openhotspot.common.phone

import kotlinx.coroutines.runBlocking
import org.openhotspot.common.*
import org.openhotspot.common.crypto.RandomSource

class ManualClock(var wall: Long = 1_800_000_000_000L) : Clock {
    override fun wallMs() = wall
    override fun monotonicMs() = wall
    fun advanceSeconds(s: Long) { wall += s * 1000 }
}

class ScriptedRandom(private vararg val chunks: ByteArray) : RandomSource {
    private var i = 0
    override fun bytes(n: Int): ByteArray {
        check(i < chunks.size) { "ScriptedRandom exhausted" }
        return chunks[i++].also { require(it.size == n) { "expected $n random bytes, script has ${it.size}" } }
    }
}

class CountingStore(private val inner: ClientStore = InMemoryClientStore()) : ClientStore by inner {
    var listCalls = 0
    override fun list(): List<ClientRecord> { listCalls++; return inner.list() }
}

class TestController : HotspotController {
    var ready = true
    var active = false
    var failure: ErrorCode? = null
    var enableCalls = 0
    var hang = false
    val info get() = HotspotInfo(HotspotState.ON, Security.WPA2_WPA3, Band.GHZ_5, "Fake Phone".toByteArray(), "correct horse battery")
    override fun isReady() = ready
    override fun isActive() = active
    override suspend fun enable(options: Int, autoOffSeconds: Int): HotspotInfo {
        failure?.let { throw HotspotException(it) }
        if (hang) kotlinx.coroutines.delay(Long.MAX_VALUE)
        enableCalls++; active = true
        return info
    }
    override suspend fun disable() { active = false }
    override suspend fun query() = if (active) info else HotspotInfo(HotspotState.OFF)
}

class Harness(
    val clock: ManualClock = ManualClock(),
    val store: CountingStore = CountingStore(),
    val controller: TestController = TestController(),
    persistence: RateLimitPersistence? = null,
    log: EngineLog = EngineLog.NONE,
) {
    val engine = PhoneEngine(store, controller, clock, log = log, limiter = RateLimiter(clock, persistence))

    /** Sends one frame over a fresh or given link, chunked at the link's MTU, and returns the reply frame. */
    fun exchange(frame: Frame, link: PhoneEngine.PhoneLink = engine.openLink(247)): Frame = runBlocking {
        var notes = emptyList<ByteArray>()
        for (c in Chunking.chunkFrame(frame.encode(), link.mtu)) notes = link.onWrite(c)
        val r = Reassembler({ clock.monotonicMs() })
        Frame.decode(notes.mapNotNull { r.feed(it) }.single())
    }

    fun pair(label: String = "Test Laptop", mtu: Int = 247, clientClock: () -> Long = { clock.wall }): TestClient {
        val qr = engine.openPairing()
        val pairing = ClientPairing(qr, label, clientClock)
        val phone = pairing.finish(exchange(pairing.requestFrame(), engine.openLink(mtu)))
        return TestClient(this, phone, mtu, clientClock)
    }
}

class TestClient(val h: Harness, val phone: PairedPhone, val mtu: Int = 247, val nowMs: () -> Long = { h.clock.wall }) {
    var counter = 0L
    var lastNonce = ByteArray(0)

    fun build(type: MsgType, body: ByteArray = ByteArray(0), counter: Long = ++this.counter, msgId: Int = 7, ts: Long = nowMs()) =
        Session.buildRequest(phone.keys.kReq, type, msgId, ts, counter, body)

    fun parse(type: MsgType, built: Session.BuiltRequest, counter: Long, reply: Frame, msgId: Int = 7) =
        Session.parseResponse(phone.keys.kResp, reply, type, msgId, built.nonce, counter, nowMs())

    fun request(type: MsgType, body: ByteArray = ByteArray(0), link: PhoneEngine.PhoneLink = h.engine.openLink(mtu)): ByteArray {
        val built = build(type, body)
        return parse(type, built, counter, h.exchange(built.frame, link))
    }

    fun enable() = HotspotInfo.decode(request(MsgType.ENABLE, EnableBody.pack()))
    fun query() = HotspotInfo.decode(request(MsgType.QUERY))
}
