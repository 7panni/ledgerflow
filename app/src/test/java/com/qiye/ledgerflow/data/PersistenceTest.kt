package com.qiye.ledgerflow.data

import com.qiye.ledgerflow.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class PersistenceTest {
    private val today = LedgerCalendar.parse("2026-10-21")
    private val codec = JsonLedgerCodec()
    private fun testFolder(): File {
        val parent = File("build/test-ledgers").apply { mkdirs() }
        return Files.createTempDirectory(parent.toPath(), "self-created-").toFile()
    }
    private fun initial() = LedgerEngine.correctBalance(LedgerState(), null, 100_000, today, 600, "自制初始余额")
    private fun withPlan() = LedgerEngine.savePlan(initial(), PlanDraft("自制计划", EntryKind.EXPENSE, 20_000, today + 1, "测试"))
    private fun assertThrows(action: () -> Unit) { try { action(); fail("Expected failure") } catch (expected: Exception) { /* Expected path. */ } }

    @Test fun restartPreservesAllDataAndConfirmationCannotApplyTwice() {
        val folder = testFolder()
        try {
            val repository = LedgerRepository(AtomicLedgerStore(folder)); repository.open()
            var state = repository.mutate { withPlan() }
            val id = state.plans.single().id
            state = repository.mutate { LedgerEngine.confirmPlan(it, id, today, 600, today) }
            val restarted = LedgerRepository(AtomicLedgerStore(folder))
            assertEquals(state, restarted.open())
            assertEquals(state, restarted.mutate { LedgerEngine.confirmPlan(it, id, today, 700, today) })
            assertEquals(1, restarted.snapshot().entries.size)
            assertEquals(80_000, LedgerEngine.balances(restarted.snapshot()).overallMinor)
        } finally { folder.deleteRecursively() }
    }

    @Test fun interruptedPendingFileIsIgnoredAndFailedSavePreservesOriginal() {
        val folder = testFolder()
        try {
            val store = AtomicLedgerStore(folder); store.save(initial())
            val original = store.dataFile.readBytes()
            val pending = File(folder, "ledger.json.pending")
            pending.writeText("{broken pending")
            assertEquals(initial(), store.load())
            pending.delete(); pending.mkdir(); File(pending, "obstruction").writeText("test")
            assertThrows { store.save(withPlan()) }
            assertArrayEquals(original, store.dataFile.readBytes())
        } finally { folder.deleteRecursively() }
    }

    @Test fun corruptedDataLocksWritesAndDoesNotCreateAnEmptyBook() {
        val folder = testFolder()
        try {
            val store = AtomicLedgerStore(folder); store.dataFile.writeText("{broken")
            val repo = LedgerRepository(store)
            assertThrows { repo.open() }
            assertThrows { repo.mutate { initial() } }
            assertEquals("{broken", store.dataFile.readText())
        } finally { folder.deleteRecursively() }
    }

    @Test fun backupRoundTripIncludesAccountsHistorySettingsAndPlanLinks() {
        var state = LedgerEngine.addAccount(withPlan(), "自制账户", -15_000)
        state = LedgerEngine.confirmPlan(state, state.plans.single().id, today, 600, today)
        state = state.copy(settings = LedgerSettings(10))
        assertEquals(state, codec.decode(codec.encode(state)))
        assertThrows { codec.decode(codec.encode(state).replace("\"schemaVersion\": 7", "\"schemaVersion\": 99")) }
        assertThrows { codec.decode(codec.encode(state).replace("\"amountMinor\": 20000", "\"amountMinor\": 20000.5")) }
        assertThrows { codec.decode(codec.encode(state).replace("EXPENSE", "UNKNOWN")) }
        assertThrows { codec.decode(codec.encode(state).replace("\"nextSequence\": ${state.nextSequence}", "\"nextSequence\": 1")) }
        assertThrows { codec.decode(codec.encode(state) + "damaged suffix") }
    }

    @Test fun invalidRestoreAndWriteFailureKeepMemoryAndDiskUnchanged() {
        val store = FailingStore(initial())
        val repo = LedgerRepository(store); val before = repo.open()
        assertThrows { repo.previewBackup("{}") }
        assertEquals(before, repo.snapshot())
        store.failSave = true
        assertThrows { repo.mutate { withPlan() } }
        assertEquals(before, repo.snapshot()); assertEquals(before, store.state)
        assertThrows { repo.restore(withPlan()) }
        assertEquals(before, repo.snapshot()); assertEquals(before, store.state)
        assertEquals(before, store.preserved)
        store.failSave = false; store.failBackup = true
        assertThrows { repo.restore(withPlan()) }
        assertEquals(before, repo.snapshot()); assertEquals(before, store.state)
    }

    @Test fun successfulRestorePreservesExactPreviousFileAndSupportsLockedRecovery() {
        val folder = testFolder()
        try {
            val store = AtomicLedgerStore(folder); store.dataFile.writeText("damaged-original")
            val repo = LedgerRepository(store); assertThrows { repo.open() }
            val restored = repo.restore(withPlan())
            assertEquals(withPlan(), restored)
            assertEquals("damaged-original", File(folder, "recovery").listFiles()!!.single().readText())
            assertEquals("damaged-original", store.latestRecoveryFile()!!.readText())
            val previousValid = store.dataFile.readText()
            repo.restore(initial())
            assertEquals(previousValid, store.latestRecoveryFile()!!.readText())
            assertEquals(initial(), LedgerRepository(AtomicLedgerStore(folder)).open())
        } finally { folder.deleteRecursively() }
    }

    private class FailingStore(var state: LedgerState) : LedgerStore {
        var failSave = false; var failBackup = false; var preserved: LedgerState? = null
        override fun load() = state
        override fun save(state: LedgerState) { if (failSave) throw IOException("self-created simulated disk failure"); this.state = state }
        override fun saveBeforeRestore() { if (failBackup) throw IOException("self-created snapshot failure"); preserved = state }
    }
}
