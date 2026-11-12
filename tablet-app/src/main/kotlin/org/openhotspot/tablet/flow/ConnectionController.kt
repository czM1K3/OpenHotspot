package org.openhotspot.tablet.flow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.openhotspot.tablet.auto.Connector
import org.openhotspot.tablet.client.ErrorText
import org.openhotspot.tablet.client.HotspotClient
import org.openhotspot.tablet.client.PhoneNotFoundException
import org.openhotspot.tablet.client.UserError
import org.openhotspot.tablet.data.PhoneStore
import org.openhotspot.tablet.data.SettingsStore
import org.openhotspot.tablet.wifi.ProbeResult

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Working(val stage: Stage, val phoneName: String?) : ConnectionState
    data class Online(val phoneName: String) : ConnectionState

    /** [stillJoined]: we are on the hotspot's Wi-Fi even though the check failed (the user may leave with Disconnect). */
    data class Failed(val error: UserError, val stillJoined: Boolean = false) : ConnectionState
}

enum class Trigger { USER, AUTO }

/**
 * The single owner of "are we connected to the phone's hotspot". The button, the Quick Settings tile
 * and the auto-trigger all call [connect]; only one attempt runs at a time. Lives as long as the
 * process, because a Wi-Fi specifier connection ends when its callback is released.
 */
class ConnectionController(
    private val scope: CoroutineScope,
    private val store: PhoneStore,
    private val settings: SettingsStore,
    private val flow: ConnectFlow,
    private val client: HotspotClient,
    private val log: (String) -> Unit = {},
) : Connector {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> get() = _state

    /** A non-error remark about the last action (for example "left the hotspot, but the phone could not be reached"). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> get() = _notice

    private val lock = Any()
    private var job: Job? = null
    private var current: Connected? = null

    override val busy: Boolean get() = synchronized(lock) { job?.isActive == true }

    /** Starts a connect attempt. Returns false (and does nothing) if one is already running or we are connected. */
    override fun connect(phoneId: String?, trigger: Trigger): Boolean = synchronized(lock) {
        if (job?.isActive == true || current != null) return false
        _notice.value = null
        val phones = store.list()
        val name = phones.firstOrNull { phoneId == null || it.phoneId == phoneId }?.name.takeIf { phoneId != null || phones.size == 1 }
        _state.value = ConnectionState.Working(Stage.SCANNING, name)
        job = scope.launch { runConnect(phones, phoneId, trigger, name) }
        true
    }

    private suspend fun runConnect(phones: List<org.openhotspot.tablet.data.PhoneRecord>, phoneId: String?, trigger: Trigger, name: String?) {
        try {
            val c = flow.run(phones, phoneId, settings.settings.value) { stage -> _state.value = ConnectionState.Working(stage, name) }
            adopt(c)
        } catch (e: NoInternetException) {
            val reason = (e.connected.internet as? ProbeResult.Offline)?.reason ?: "captive portal"
            log("Joined, but no internet ($reason)")
            if (trigger == Trigger.USER) {
                adopt(e.connected, keepState = false)
                _state.value = ConnectionState.Failed(ErrorText.noInternet, stillJoined = true)
            } else {
                // Unattended: a Wi-Fi network that goes nowhere must not be left in place (D34 on the Linux side).
                e.connected.link.release()
                turnHotspotOff(e.connected.phone.phoneId)
                _state.value = ConnectionState.Failed(ErrorText.noInternet)
            }
        } catch (e: CancellationException) {
            _state.value = ConnectionState.Idle
            throw e
        } catch (e: Exception) {
            log("Connect failed: ${e.javaClass.simpleName}")
            _state.value = ConnectionState.Failed(ErrorText.describe(e))
        }
    }

    private fun adopt(c: Connected, keepState: Boolean = true) {
        synchronized(lock) { current = c }
        c.link.setLostListener {
            synchronized(lock) { if (current === c) current = null }
            log("Hotspot connection lost")
            _state.value = ConnectionState.Idle
        }
        if (keepState) _state.value = ConnectionState.Online(c.phone.name)
    }

    /** Leaves the hotspot's Wi-Fi and (best effort) asks the phone to switch its hotspot off. */
    fun disconnect(turnOffHotspot: Boolean = true) {
        val c = synchronized(lock) { current.also { current = null } }
        if (c == null) {
            synchronized(lock) { job?.cancel() }
            _state.value = ConnectionState.Idle
            return
        }
        c.link.release()
        _state.value = ConnectionState.Idle
        if (turnOffHotspot) {
            synchronized(lock) {
                job = scope.launch { turnHotspotOff(c.phone.phoneId) }
            }
        }
    }

    /** The common case: any paired phone in range, started by the user. */
    fun connect(): Boolean = connect(null, Trigger.USER)

    /** Abandons a running attempt (also closes a half-open Wi-Fi request). */
    fun cancel() {
        synchronized(lock) { job?.cancel() }
    }

    private suspend fun turnHotspotOff(phoneId: String) {
        try {
            val phone = store.list().firstOrNull { it.phoneId == phoneId } ?: return
            val found = client.findPhone(listOf(phone), 8_000) ?: throw PhoneNotFoundException()
            client.open(found).use { it.disable() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Could not switch the phone's hotspot off: ${e.javaClass.simpleName}")
            _notice.value = "Left the hotspot, but could not reach the phone to switch its hotspot off."
        }
    }
}
