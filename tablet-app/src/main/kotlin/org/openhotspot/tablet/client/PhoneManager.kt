package org.openhotspot.tablet.client

import kotlinx.coroutines.CancellationException
import org.openhotspot.tablet.data.PhoneStore

/** Housekeeping for paired phones. */
class PhoneManager(private val client: HotspotClient, private val store: PhoneStore) {
    /**
     * Forgets a phone. The local key is always deleted (a user who unpairs wants it gone even when the
     * phone is away); the phone is asked to forget this tablet only if it can be reached.
     * Returns true if the phone confirmed.
     */
    suspend fun unpair(phoneId: String, scanMs: Long = 8_000): Boolean {
        val phone = store.list().firstOrNull { it.phoneId == phoneId } ?: return false
        val acknowledged = try {
            val found = client.findPhone(listOf(phone), scanMs)
            if (found == null) false else client.open(found).use { it.unpair(); true }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        store.remove(phoneId)
        return acknowledged
    }
}
