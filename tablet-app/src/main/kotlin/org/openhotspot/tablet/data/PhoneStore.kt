package org.openhotspot.tablet.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.openhotspot.common.PairedPhone

/** A phone this tablet is paired with. [ltk] is secret; [name] is what the user sees. */
class PhoneRecord(
    val phoneId: String,
    val ltk: ByteArray,
    val phonePub: ByteArray,
    /** The label this tablet gave itself during pairing (shown on the phone). */
    val ourLabel: String,
    val name: String,
    val pairedAtMs: Long,
    /** Last request counter used. Persisted before each send (PROTOCOL.md section 6). Unsigned. */
    val lastCounter: Long = 0,
) {
    val paired: PairedPhone by lazy { PairedPhone(phoneId, ltk, phonePub, ourLabel) }
    val keys get() = paired.keys

    fun copy(name: String = this.name, lastCounter: Long = this.lastCounter) =
        PhoneRecord(phoneId, ltk, phonePub, ourLabel, name, pairedAtMs, lastCounter)
}

/**
 * Where paired phones live. [nextCounter] MUST be durable before it returns, because the request
 * is sent only afterwards: a crash can then waste a counter value but never reuse one.
 */
interface PhoneStore {
    val phones: StateFlow<List<PhoneRecord>>
    fun list(): List<PhoneRecord> = phones.value
    fun add(record: PhoneRecord)
    fun rename(phoneId: String, name: String)
    fun remove(phoneId: String): Boolean

    /** Increments the phone's counter, persists it, and returns the new value (first value: 1). */
    fun nextCounter(phoneId: String): Long
}

class InMemoryPhoneStore : PhoneStore {
    private val flow = MutableStateFlow<List<PhoneRecord>>(emptyList())
    override val phones: StateFlow<List<PhoneRecord>> get() = flow

    @Synchronized override fun add(record: PhoneRecord) { flow.value = flow.value.filter { it.phoneId != record.phoneId } + record }

    @Synchronized override fun rename(phoneId: String, name: String) {
        flow.value = flow.value.map { if (it.phoneId == phoneId) it.copy(name = name) else it }
    }

    @Synchronized override fun remove(phoneId: String): Boolean {
        val had = flow.value.any { it.phoneId == phoneId }
        flow.value = flow.value.filter { it.phoneId != phoneId }
        return had
    }

    @Synchronized override fun nextCounter(phoneId: String): Long {
        val r = flow.value.firstOrNull { it.phoneId == phoneId } ?: throw IllegalStateException("unknown phone")
        val next = r.lastCounter + 1
        flow.value = flow.value.map { if (it.phoneId == phoneId) it.copy(lastCounter = next) else it }
        return next
    }
}
