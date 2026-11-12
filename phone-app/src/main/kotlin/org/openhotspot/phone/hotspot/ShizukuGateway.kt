package org.openhotspot.phone.hotspot

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

/** What the UI shows about Shizuku. */
enum class ShizukuState(val label: String) {
    NOT_INSTALLED("Shizuku is not installed"),
    NOT_RUNNING("Shizuku is not running"),
    UNSUPPORTED_VERSION("Shizuku is too old (needs v11 or newer)"),
    NOT_AUTHORIZED("Shizuku is running, but OpenHotspot is not authorized"),
    READY("Shizuku is ready"),
}

data class ShizukuStatus(
    val state: ShizukuState = ShizukuState.NOT_RUNNING,
    /** The user chose "deny and don't ask again"; the permission can only be granted inside the Shizuku app now. */
    val permanentlyDenied: Boolean = false,
    /** 2000 when Shizuku runs through adb (shell), 0 when it runs as root. */
    val serverUid: Int? = null,
    val serverVersion: Int? = null,
) {
    val ready: Boolean get() = state == ShizukuState.READY
}

/** The Shizuku side of things, behind an interface so backend selection can be tested without Shizuku. */
interface ShizukuGateway {
    val status: StateFlow<ShizukuStatus>

    /** Re-reads the state (call on resume; the Shizuku service can start or stop at any time). */
    fun refresh()

    /** Shows Shizuku's permission dialog (does nothing visible if permanently denied). */
    fun requestPermission()

    /** Opens the Shizuku app, if installed. */
    fun openManager(): Boolean
}

/** The real thing, on top of `dev.rikka.shizuku:api` and `:provider` (both MIT). */
class ShizukuGatewayImpl(private val context: Context, private val log: (String) -> Unit = {}) : ShizukuGateway {
    private val flow = MutableStateFlow(ShizukuStatus())
    override val status: StateFlow<ShizukuStatus> get() = flow
    private val main = Handler(Looper.getMainLooper())

    private val onReceived = Shizuku.OnBinderReceivedListener { log("Shizuku binder received"); refresh() }
    private val onDead = Shizuku.OnBinderDeadListener { log("Shizuku binder died"); refresh() }
    private val onResult = Shizuku.OnRequestPermissionResultListener { _, result ->
        log("Shizuku permission result: " + if (result == PackageManager.PERMISSION_GRANTED) "granted" else "denied")
        refresh()
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(onReceived, main)
        Shizuku.addBinderDeadListener(onDead, main)
        Shizuku.addRequestPermissionResultListener(onResult, main)
        refresh()
    }

    override fun refresh() {
        val next = compute()
        if (next != flow.value) {
            log("Shizuku: ${next.state.label}" + (next.serverUid?.let { " (server uid $it, API ${next.serverVersion})" } ?: ""))
        }
        flow.value = next
    }

    private fun compute(): ShizukuStatus = try {
        if (!Shizuku.pingBinder()) {
            ShizukuStatus(if (isManagerInstalled()) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED)
        } else {
            val uid = Shizuku.getUid()
            val version = Shizuku.getVersion()
            when {
                Shizuku.isPreV11() -> ShizukuStatus(ShizukuState.UNSUPPORTED_VERSION, serverUid = uid, serverVersion = version)
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                    ShizukuStatus(ShizukuState.READY, serverUid = uid, serverVersion = version)
                else -> ShizukuStatus(
                    ShizukuState.NOT_AUTHORIZED, permanentlyDenied = Shizuku.shouldShowRequestPermissionRationale(),
                    serverUid = uid, serverVersion = version,
                )
            }
        }
    } catch (e: Exception) { // the binder can die between ping and the next call
        ShizukuStatus(ShizukuState.NOT_RUNNING)
    }

    override fun requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Exception) {
            log("Shizuku permission request failed: ${e.javaClass.simpleName}")
        }
    }

    override fun openManager(): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(MANAGER_PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false
        context.startActivity(intent)
        return true
    }

    private fun isManagerInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 4101
    }
}
