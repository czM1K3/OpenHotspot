package org.openhotspot.phone.data

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
import org.openhotspot.common.phone.ClientRecord
import org.openhotspot.common.phone.ClientStore
import org.openhotspot.common.phone.EngineLog
import org.openhotspot.common.phone.LogLevel

/**
 * Paired clients, encrypted at rest with [SecretCipher] and written atomically (temp file, fsync,
 * rename), so a crash never leaves a half-written file and [updateCounter] is durable on return.
 *
 * If the file cannot be decrypted (for example the Keystore key was lost), the store starts empty
 * and keeps the unreadable file as `<name>.corrupt` instead of overwriting it silently.
 */
class EncryptedClientStore(
    private val file: File,
    private val cipher: SecretCipher,
    private val log: EngineLog = EngineLog.NONE,
) : ClientStore {
    private val lock = Any()
    private var items: List<ClientRecord> = emptyList()
    private var loadFailed = false
    private val flow = MutableStateFlow<List<ClientRecord>>(emptyList())

    /** Observable view for the UI. */
    val clients: StateFlow<List<ClientRecord>> get() = flow

    init {
        load()
    }

    private fun load() = synchronized(lock) {
        if (!file.exists()) return@synchronized
        try {
            items = decode(cipher.decrypt(file.readBytes(), AAD))
            flow.value = items
        } catch (e: Exception) {
            loadFailed = true
            log.log(LogLevel.ERROR, "Stored client keys could not be read (${e.javaClass.simpleName}); starting with no paired clients")
        }
    }

    override fun list(): List<ClientRecord> = synchronized(lock) { items }

    override fun add(record: ClientRecord) = synchronized(lock) {
        commit(items.filter { it.id != record.id } + record)
    }

    override fun updateCounter(id: String, counter: Long) = synchronized(lock) {
        val current = items.firstOrNull { it.id == id } ?: return@synchronized
        commit(items.map { if (it.id == id) ClientRecord(it.id, it.ltk, it.label, it.pairedAtMs, counter) else it })
    }

    override fun remove(id: String): Boolean = synchronized(lock) {
        if (items.none { it.id == id }) return@synchronized false
        commit(items.filter { it.id != id })
        true
    }

    /** Persists [next] first and only then publishes it, so memory never runs ahead of disk. */
    private fun commit(next: List<ClientRecord>) {
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
        items = next
        flow.value = next
    }

    companion object {
        private val AAD = "OpenHotspot clients v1".toByteArray()
        private const val FORMAT = 1

        internal fun encode(records: List<ClientRecord>): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { o ->
                o.writeByte(FORMAT)
                o.writeShort(records.size)
                for (r in records) {
                    o.writeUTF(r.id)
                    o.writeByte(r.ltk.size); o.write(r.ltk)
                    o.writeUTF(r.label)
                    o.writeLong(r.pairedAtMs)
                    o.writeLong(r.lastCounter)
                }
            }
            return bytes.toByteArray()
        }

        internal fun decode(data: ByteArray): List<ClientRecord> {
            DataInputStream(ByteArrayInputStream(data)).use { i ->
                val version = i.readUnsignedByte()
                require(version == FORMAT) { "unsupported format $version" }
                return List(i.readUnsignedShort()) {
                    val id = i.readUTF()
                    val ltk = ByteArray(i.readUnsignedByte()).also { b -> i.readFully(b) }
                    ClientRecord(id, ltk, i.readUTF(), i.readLong(), i.readLong())
                }
            }
        }
    }
}
