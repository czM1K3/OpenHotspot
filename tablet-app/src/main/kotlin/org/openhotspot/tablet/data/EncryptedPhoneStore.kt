package org.openhotspot.tablet.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Paired phones, encrypted at rest with [SecretCipher] (the Android Keystore in the app) and written
 * atomically (temp file, fsync, rename), so [nextCounter] is durable on return and a crash never
 * leaves a half-written file. Same design as the phone app's client store (DECISIONS.md D23).
 *
 * If the file cannot be decrypted (for example the Keystore key was lost) the store starts empty and
 * keeps the unreadable file as `<name>.corrupt` instead of overwriting it silently.
 */
class EncryptedPhoneStore(
    private val file: File,
    private val cipher: SecretCipher,
    private val onProblem: (String) -> Unit = {},
) : PhoneStore {
    private val lock = Any()
    private var loadFailed = false
    private val flow = MutableStateFlow<List<PhoneRecord>>(emptyList())
    override val phones: StateFlow<List<PhoneRecord>> get() = flow

    init {
        synchronized(lock) {
            if (file.exists()) {
                try {
                    flow.value = decode(cipher.decrypt(file.readBytes(), AAD))
                } catch (e: Exception) {
                    loadFailed = true
                    onProblem("Stored phone keys could not be read (${e.javaClass.simpleName}); starting with no paired phones")
                }
            }
        }
    }

    override fun add(record: PhoneRecord) = synchronized(lock) {
        commit(flow.value.filter { it.phoneId != record.phoneId } + record)
    }

    override fun rename(phoneId: String, name: String) = synchronized(lock) {
        commit(flow.value.map { if (it.phoneId == phoneId) it.copy(name = name) else it })
    }

    override fun remove(phoneId: String): Boolean = synchronized(lock) {
        if (flow.value.none { it.phoneId == phoneId }) return@synchronized false
        commit(flow.value.filter { it.phoneId != phoneId })
        true
    }

    override fun nextCounter(phoneId: String): Long = synchronized(lock) {
        val r = flow.value.firstOrNull { it.phoneId == phoneId } ?: throw IllegalStateException("unknown phone")
        val next = r.lastCounter + 1 // unsigned wrap is not a practical concern at 2^64
        commit(flow.value.map { if (it.phoneId == phoneId) it.copy(lastCounter = next) else it })
        next
    }

    /** Persists [next] first and only then publishes it, so memory never runs ahead of disk. */
    private fun commit(next: List<PhoneRecord>) {
        file.parentFile?.mkdirs()
        if (loadFailed && file.exists()) {
            Files.move(file.toPath(), File(file.path + ".corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING)
            loadFailed = false
        }
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(cipher.encrypt(encode(next), AAD))
            out.flush()
            out.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        flow.value = next
    }

    companion object {
        private val AAD = "OpenHotspot tablet phones v1".toByteArray()
        private const val FORMAT = 1

        internal fun encode(records: List<PhoneRecord>): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { o ->
                o.writeByte(FORMAT)
                o.writeShort(records.size)
                for (r in records) {
                    o.writeUTF(r.phoneId)
                    o.writeByte(r.ltk.size); o.write(r.ltk)
                    o.writeByte(r.phonePub.size); o.write(r.phonePub)
                    o.writeUTF(r.ourLabel)
                    o.writeUTF(r.name)
                    o.writeLong(r.pairedAtMs)
                    o.writeLong(r.lastCounter)
                }
            }
            return bytes.toByteArray()
        }

        internal fun decode(data: ByteArray): List<PhoneRecord> {
            DataInputStream(ByteArrayInputStream(data)).use { i ->
                val version = i.readUnsignedByte()
                require(version == FORMAT) { "unsupported format $version" }
                return List(i.readUnsignedShort()) {
                    val id = i.readUTF()
                    val ltk = ByteArray(i.readUnsignedByte()).also { b -> i.readFully(b) }
                    val pub = ByteArray(i.readUnsignedByte()).also { b -> i.readFully(b) }
                    PhoneRecord(id, ltk, pub, i.readUTF(), i.readUTF(), i.readLong(), i.readLong())
                }
            }
        }
    }
}
