package com.qiye.ledgerflow.data

import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.LedgerFlowTiming
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Only this platform contract accepts immutable snapshots already checked by the repository. */
internal interface ValidatedLedgerStore : LedgerStore {
    fun loadValidated(): LedgerState?
    fun saveValidated(state: LedgerState)
}

/** Writes are same-directory temp + fsync + atomic replace; no delete-and-recreate fallback. */
internal class AtomicLedgerStore(private val directory: File, private val codec: LedgerCodec = JsonLedgerCodec()) : ValidatedLedgerStore {
    private var migrationCopyRequired = false
    val dataFile get() = File(directory, "ledger.json")
    override fun load(): LedgerState? = loadValidated()
    override fun loadValidated(): LedgerState? {
        if (!dataFile.exists()) return null
        require(dataFile.isFile && dataFile.length() <= JsonLedgerCodec.MAX_BYTES) { "本地数据文件不可读或过大；原文件已保留" }
        val text=LedgerFlowTiming.measure("file_read",dataFile.length()){dataFile.readText(Charsets.UTF_8)}
        if(codec is JsonLedgerCodec) {
            val (result,version)=LedgerFlowTiming.measure("decode"){codec.decodeWithVersion(text)}
            migrationCopyRequired=version<7
            return result
        }
        val result=LedgerEngine.validate(codec.decode(text))
        migrationCopyRequired=org.json.JSONObject(text).getInt("schemaVersion")<7
        return result
    }

    override fun save(state: LedgerState) = saveValidated(LedgerEngine.validate(state))
    override fun saveValidated(state: LedgerState) {
        val bytes = (if(codec is JsonLedgerCodec)codec.encodeValidated(state) else codec.encode(state)).toByteArray(Charsets.UTF_8)
        require(bytes.size <= JsonLedgerCodec.MAX_BYTES) { "数据超过当前版本 64 MB 限制，未保存" }
        ensureDirectory()
        if(migrationCopyRequired) { saveBeforeRestore(); migrationCopyRequired=false }
        val pending = File(directory, "ledger.json.pending")
        try {
            FileOutputStream(pending).use { stream -> stream.write(bytes); stream.fd.sync() }
            try {
                Files.move(pending.toPath(), dataFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                // Android/Linux rename in the same directory replaces atomically, or leaves the old file intact.
                if (!pending.renameTo(dataFile)) throw IOException("本地文件系统不能原子替换账本，原数据未变")
            }
        } finally { pending.delete() }
    }

    override fun saveBeforeRestore() {
        ensureDirectory()
        if (!dataFile.exists()) return
        val recovery = File(directory, "recovery")
        if (!recovery.isDirectory && !recovery.mkdirs()) throw IOException("不能建立恢复前副本，已取消恢复")
        val previousTime = latestRecoveryFile()?.lastModified() ?: 0L
        val target = File.createTempFile("before-restore-", ".json", recovery)
        try {
            dataFile.inputStream().use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() }
            }
            if (!target.setLastModified(maxOf(System.currentTimeMillis(), previousTime + 1))) throw IOException("不能标记恢复前副本顺序，已取消恢复")
        } catch (error: Exception) { target.delete(); throw error }
    }

    fun latestRecoveryFile(): File? = File(directory, "recovery").listFiles()
        ?.filter { it.isFile && it.name.startsWith("before-restore-") && it.name.endsWith(".json") }
        ?.maxWithOrNull(compareBy<File> { it.lastModified() }.thenBy { it.name })

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("本地保存目录不可用")
    }
}
