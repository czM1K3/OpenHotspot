package org.openhotspot.phone.hotspot

import android.content.Context
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.function.Supplier
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openhotspot.common.phone.Clock
import org.openhotspot.common.phone.EngineLog
import org.openhotspot.common.phone.LogLevel
import org.openhotspot.common.phone.SystemClock

sealed interface TetherStart {
    data object Started : TetherStart
    data class Failed(val errorCode: Int) : TetherStart
}

/** Receives Wi-Fi tethering events from the system. */
interface TetheringListener {
    fun onWifiActive(active: Boolean)

    /** Connected Wi-Fi clients, or null if the callback could not classify them. */
    fun onWifiClients(count: Int?)
}

/** The TetheringManager calls, behind an interface so [ShizukuTetheringBackend] is unit-testable. */
interface TetheringApi {
    /** Resolves everything by reflection; returns a short description of what was resolved. Throws [BackendException]. */
    fun prepare(): String

    /** Blocking binder round trip as the Shizuku identity; the only probe that proves the privileged path works without toggling anything. */
    fun isTetheringSupported(): Boolean
    suspend fun startWifi(): TetherStart
    fun stopWifi()
    fun setListener(listener: TetheringListener?)
    fun close()
}

/**
 * `TetheringManager` via reflection over a Shizuku-wrapped binder.
 *
 * How it works (every step below was checked against AOSP android11..17 source; NONE has run on a device):
 * 1. `ServiceManager` name "tethering" is the `ITetheringConnector` binder. We wrap it in
 *    [rikka.shizuku.ShizukuBinderWrapper], so each transaction is executed by the Shizuku server (shell UID).
 * 2. `new TetheringManager(Context, Supplier<IBinder>)` is a public constructor (`@hide`, in every version 11..17)
 *    that takes the binder from the supplier. Its Context only needs `getOpPackageName()`, which
 *    we set to the shell package because the service checks that the package belongs to the calling UID.
 * 3. `startTethering(TetheringRequest, Executor, StartTetheringCallback)` and `stopTethering(int)` exist in all versions.
 *    The request is `TetheringRequest.Builder(TETHERING_WIFI)`, exempt from entitlement checks.
 *    The OS starts the AP from the phone's saved hotspot configuration (`WifiManager.startTetheredHotspot(null)`).
 */
class ReflectiveTetheringApi(private val context: Context, private val log: EngineLog = EngineLog.NONE) : TetheringApi {
    private class Handles(
        val manager: Any,
        val newRequest: () -> Any,
        val start: Method,
        val stop: Method,
        val register: Method,
        val unregister: Method,
        val supported: Method,
        val startCallbackClass: Class<*>,
        val eventCallbackClass: Class<*>,
        val description: String,
    )

    @Volatile private var handles: Handles? = null
    @Volatile private var listener: TetheringListener? = null
    private var eventCallback: Any? = null
    private val direct = Executor { it.run() }

    @Synchronized
    private fun ensure(): Handles {
        handles?.let { return it }
        val h = ShizukuBinders.reflect("prepare TetheringManager") {
            val bypass = ShizukuBinders.ensureHiddenApiAccess()
            val pkg = ShizukuBinders.identityPackage()
            val managerClass = Class.forName("android.net.TetheringManager")
            val ctor = managerClass.getConstructor(Context::class.java, Supplier::class.java)
            val binder = ShizukuBinders.wrapped(TETHERING_SERVICE)
            val manager = ctor.newInstance(ShellIdentityContext(context.applicationContext, pkg), Supplier { binder })

            val requestClass = Class.forName("android.net.TetheringManager\$TetheringRequest")
            val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            val startCb = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            val eventCb = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
            val builderCtor = builderClass.getConstructor(Int::class.javaPrimitiveType)
            val exempt = builderClass.getMethod("setExemptFromEntitlementCheck", Boolean::class.javaPrimitiveType)
            val showUi = builderClass.getMethod("setShouldShowEntitlementUi", Boolean::class.javaPrimitiveType)
            val build = builderClass.getMethod("build")
            val newRequest = {
                val b = builderCtor.newInstance(TETHERING_WIFI)
                exempt.invoke(b, true)
                showUi.invoke(b, false)
                build.invoke(b)!!
            }
            val h = Handles(
                manager, newRequest,
                managerClass.getMethod("startTethering", requestClass, Executor::class.java, startCb),
                managerClass.getMethod("stopTethering", Int::class.javaPrimitiveType),
                managerClass.getMethod("registerTetheringEventCallback", Executor::class.java, eventCb),
                managerClass.getMethod("unregisterTetheringEventCallback", eventCb),
                managerClass.getMethod("isTetheringSupported"),
                startCb, eventCb,
                "ctor(Context,Supplier) startTethering(request,executor,cb) stopTethering(int) as '$pkg', hidden-API exemption " +
                    if (bypass) "ok" else "NOT confirmed",
            )
            h
        }
        handles = h
        registerEventCallback(h)
        return h
    }

    private fun registerEventCallback(h: Handles) {
        val cb = Proxy.newProxyInstance(h.eventCallbackClass.classLoader, arrayOf(h.eventCallbackClass), EventHandler())
        try {
            ShizukuBinders.reflect("registerTetheringEventCallback") { h.register.invoke(h.manager, direct, cb) }
            eventCallback = cb
        } catch (e: BackendException) {
            // Not fatal: start/stop still work, but we cannot count clients or see external changes.
            log.log(LogLevel.WARN, "Tethering event callback not registered: ${e.message}")
        }
    }

    /** Receives `TetheringEventCallback` calls. Only the three we care about are handled; every other default method is a no-op. */
    private inner class EventHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> "OpenHotspotTetheringCallback"
            }
            try {
                when (method.name) {
                    "onTetheredInterfacesChanged" -> onInterfaces(args?.firstOrNull())
                    "onClientsChanged" -> onClients(args?.firstOrNull())
                }
            } catch (e: Exception) {
                log.log(LogLevel.WARN, "Tethering callback ${method.name} failed: ${e.javaClass.simpleName}")
            }
            return when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        }
    }

    private fun onInterfaces(arg: Any?) {
        val wifi = when (arg) {
            // Android 12+: Set<TetheringInterface>; each has getType().
            is Set<*> -> arg.any { it != null && typeOf(it) == TETHERING_WIFI }
            // Android 11: List<String> of interface names, no type information; any entry counts (a USB tether would too).
            is List<*> -> arg.isNotEmpty()
            else -> return
        }
        listener?.onWifiActive(wifi)
    }

    private fun onClients(arg: Any?) {
        val clients = (arg as? Collection<*>) ?: return
        val wifi = try {
            // TetheredClient.getTetheringType(): TETHERING_WIFI == 0.
            clients.count { it != null && it.javaClass.getMethod("getTetheringType").invoke(it) == TETHERING_WIFI }
        } catch (e: ReflectiveOperationException) {
            null
        }
        listener?.onWifiClients(wifi)
    }

    private fun typeOf(iface: Any): Int? = try {
        iface.javaClass.getMethod("getType").invoke(iface) as? Int
    } catch (e: ReflectiveOperationException) {
        null
    }

    override fun prepare(): String = ensure().description

    override fun isTetheringSupported(): Boolean {
        val h = ensure()
        return ShizukuBinders.reflect("isTetheringSupported") { h.supported.invoke(h.manager) as Boolean }
    }

    override suspend fun startWifi(): TetherStart {
        val h = ensure()
        return suspendCancellableCoroutine { cont ->
            val cb = Proxy.newProxyInstance(h.startCallbackClass.classLoader, arrayOf(h.startCallbackClass)) { proxy, method, args ->
                when (method.name) {
                    "onTetheringStarted" -> if (cont.isActive) cont.resume(TetherStart.Started)
                    "onTetheringFailed" -> if (cont.isActive) cont.resume(TetherStart.Failed((args?.firstOrNull() as? Int) ?: -1))
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "equals" -> return@newProxyInstance proxy === args?.firstOrNull()
                    "toString" -> return@newProxyInstance "OpenHotspotStartCallback"
                }
                null
            }
            try {
                ShizukuBinders.reflect("startTethering") { h.start.invoke(h.manager, h.newRequest(), direct, cb) }
            } catch (e: BackendException) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }
        }
    }

    override fun stopWifi() {
        val h = ensure()
        ShizukuBinders.reflect("stopTethering") { h.stop.invoke(h.manager, TETHERING_WIFI) }
    }

    override fun setListener(listener: TetheringListener?) {
        this.listener = listener
    }

    @Synchronized
    override fun close() {
        val h = handles ?: return
        val cb = eventCallback
        if (cb != null) try { h.unregister.invoke(h.manager, cb) } catch (e: Exception) { /* best effort */ }
        eventCallback = null
        handles = null
    }

    companion object {
        const val TETHERING_SERVICE = "tethering" // Context.TETHERING_SERVICE
        const val TETHERING_WIFI = 0 // TetheringManager.TETHERING_WIFI (SDK stub, Android 16)
    }
}

/**
 * The preferred backend: starts the Wi-Fi hotspot exactly as the system Settings toggle does.
 * Reports credentials from the phone's saved hotspot configuration ([CredentialPolicy.SYSTEM_FIRST]).
 */
class ShizukuTetheringBackend(
    private val gateway: ShizukuGateway,
    private val api: TetheringApi,
    private val wifi: WifiBinderApi,
    private val log: EngineLog = EngineLog.NONE,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pollMs: Long = 300,
    private val clock: Clock = SystemClock,
) : HotspotBackend, TetheringListener {
    override val id = BackendId.SHIZUKU_TETHERING
    override val sharesInternet = true
    override val credentialPolicy = CredentialPolicy.SYSTEM_FIRST

    private val _active = MutableStateFlow<Boolean?>(null)
    private val _clients = MutableStateFlow<Int?>(null)
    override val active: StateFlow<Boolean?> get() = _active
    override val clients: StateFlow<Int?> get() = _clients

    override fun onWifiActive(active: Boolean) { _active.value = active }
    override fun onWifiClients(count: Int?) { _clients.value = count }

    override suspend fun probe(): ProbeResult {
        shizukuProblem(gateway.status.value)?.let { return it }
        return try {
            withContext(io) {
                val desc = api.prepare()
                api.setListener(this@ShizukuTetheringBackend)
                if (!api.isTetheringSupported()) {
                    ProbeResult.Unavailable(
                        FailureKind.UNSUPPORTED,
                        "isTetheringSupported() = false (tethering disabled on this device, or the tethering service rejected the shell identity)",
                    )
                } else ProbeResult.Available("TetheringManager reachable through Shizuku; isTetheringSupported() = true; $desc")
            }
        } catch (e: BackendException) {
            ProbeResult.Unavailable(e.kind, e.message ?: e.kind.name)
        } catch (e: Exception) {
            ProbeResult.Unavailable(FailureKind.INTERNAL, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override suspend fun start(credentials: HotspotCredentials?, timeoutMs: Long): HotspotCredentials? {
        requireShizuku(gateway.status.value)
        withContext(io) { api.prepare(); api.setListener(this@ShizukuTetheringBackend) }
        if (_active.value == true) {
            log.log(LogLevel.INFO, "Hotspot already on; not starting again")
            return null
        }
        val result = withTimeoutOrNull(timeoutMs) { api.startWifi() }
            ?: throw BackendException(FailureKind.TIMEOUT, "startTethering gave no result within ${timeoutMs} ms")
        when (result) {
            TetherStart.Started -> Unit
            is TetherStart.Failed -> {
                if (result.errorCode == TetherErrors.DUPLICATE_REQUEST) {
                    log.log(LogLevel.INFO, "startTethering reported DUPLICATE_REQUEST; treating as already requested")
                } else {
                    throw BackendException(TetherErrors.kind(result.errorCode), "startTethering failed: ${TetherErrors.name(result.errorCode)} (${result.errorCode})")
                }
            }
        }
        // onTetheringStarted only means the request was accepted. Wait for the AP to actually come up.
        if (!awaitApState(enabled = true, timeoutMs = timeoutMs)) {
            throw BackendException(FailureKind.TIMEOUT, "startTethering accepted but the Wi-Fi AP did not report enabled in time")
        }
        _active.value = true
        return null
    }

    override suspend fun stop() {
        requireShizuku(gateway.status.value)
        withContext(io) { api.prepare(); api.stopWifi() }
        if (!awaitApState(enabled = false, timeoutMs = 4_000)) {
            log.log(LogLevel.WARN, "stopTethering returned but the AP still reports enabled after 4 s")
        } else {
            _active.value = false
        }
    }

    /** True when the AP state matches [enabled], from the callback or, failing that, a direct poll of the Wi-Fi service. */
    private suspend fun awaitApState(enabled: Boolean, timeoutMs: Long): Boolean {
        val deadline = clock.monotonicMs() + timeoutMs
        while (true) {
            if (_active.value == enabled) return true
            val polled = withContext(io) { wifi.apState() }
            if (polled != null && (polled == SoftApMapping.WIFI_AP_STATE_ENABLED) == enabled) return true
            if (clock.monotonicMs() >= deadline) return false
            delay(pollMs)
        }
    }

    override fun close() {
        api.setListener(null)
        api.close()
    }
}

/** Shared by both Shizuku backends. Null means Shizuku is usable. */
internal fun shizukuProblem(s: ShizukuStatus): ProbeResult.Unavailable? = when (s.state) {
    ShizukuState.READY -> null
    ShizukuState.NOT_INSTALLED, ShizukuState.NOT_RUNNING -> ProbeResult.Unavailable(FailureKind.SHIZUKU_NOT_RUNNING, s.state.label)
    ShizukuState.UNSUPPORTED_VERSION -> ProbeResult.Unavailable(FailureKind.SHIZUKU_TOO_OLD, s.state.label)
    ShizukuState.NOT_AUTHORIZED -> ProbeResult.Unavailable(FailureKind.SHIZUKU_NOT_AUTHORIZED, s.state.label)
}

internal fun requireShizuku(s: ShizukuStatus) {
    shizukuProblem(s)?.let { throw BackendException(it.kind, it.reason) }
}
