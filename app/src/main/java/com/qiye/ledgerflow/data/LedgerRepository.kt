package com.qiye.ledgerflow.data

import com.qiye.ledgerflow.core.*

/** Serialized access; an in-memory success can never precede a durable write. */
class LedgerRepository(private val store: LedgerStore, private val codec: LedgerCodec = JsonLedgerCodec()) {
    private var current: LedgerState? = null

    @Synchronized fun open(): LedgerState {
        current = null
        val loaded = if(store is ValidatedLedgerStore) store.loadValidated() ?: LedgerEngine.validate(LedgerState()) else LedgerEngine.validate(store.load() ?: LedgerState())
        current = loaded
        return loaded
    }

    @Synchronized fun snapshot(): LedgerState = current ?: error("本地数据未成功读取；写入已锁定，请先恢复有效备份")

    @Synchronized fun mutate(change: (LedgerState) -> LedgerState): LedgerState {
        val before = snapshot()
        val changed = change(before)
        if(changed === before) return before
        val after = LedgerEngine.validate(changed)
        if (after != before) { saveValidated(after); current = after }
        return after
    }

    @Synchronized fun migrate(change:(LedgerState)->LedgerState):LedgerState {
        val before=snapshot(); val after=LedgerEngine.validate(change(before))
        if(after!=before){store.saveBeforeRestore();saveValidated(after);current=after}
        return after
    }

    private fun saveValidated(state:LedgerState) { if(store is ValidatedLedgerStore) store.saveValidated(state) else store.save(state) }

    fun previewBackup(text: String): LedgerState = codec.decode(text)
    @Synchronized fun exportBackup(): String = codec.encode(snapshot())

    @Synchronized fun restore(candidate: LedgerState): LedgerState {
        val valid = LedgerEngine.validate(candidate)
        store.saveBeforeRestore()
        saveValidated(valid)
        current = valid
        return valid
    }
}
