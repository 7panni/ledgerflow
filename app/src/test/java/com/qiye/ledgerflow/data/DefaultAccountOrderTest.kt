package com.qiye.ledgerflow.data

import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.cashflow.data.AccountType
import com.qiye.ledgerflow.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DefaultAccountOrderTest {
    private val day=LedgerCalendar.parse("2026-10-30")
    private fun initial():LedgerState {
        var s=LedgerState()
        listOf(Triple("银行",AccountType.CMB,200L),Triple("支付宝",AccountType.ALIPAY,300L),Triple("现金",AccountType.CASH,400L),Triple("微信",AccountType.WECHAT,100L)).forEach{(name,type,balance)->s=RichLedger.saveAccount(s,Account("",0,name,balance,accountType=type))}
        return s
    }
    private fun id(s:LedgerState,name:String)=s.accounts.single{it.name==name}.id
    private fun balances(s:LedgerState,vararg values:Pair<String,Long>)=s.copy(accounts=s.accounts.map{a->values.toMap()[a.name]?.let{a.copy(openingMinor=it)}?:a})
    @Test fun defaultsSkipOnlyExactZeroAndAllZeroKeepFirst() {
        val s=initial();assertEquals(id(s,"微信"),defaultQuickEntryAccount(s,"CNY"))
        val noWechat=balances(s,"微信" to 0L);assertEquals(id(s,"银行"),defaultQuickEntryAccount(noWechat,"CNY"))
        assertEquals(id(s,"支付宝"),defaultQuickEntryAccount(balances(noWechat,"银行" to 0L),"CNY"))
        assertEquals(id(s,"微信"),defaultQuickEntryAccount(balances(s,"微信" to -1L),"CNY"))
        assertEquals(id(s,"微信"),defaultQuickEntryAccount(balances(s,"微信" to 0L,"银行" to 0L,"支付宝" to 0L,"现金" to 0L),"CNY"))
    }
    @Test fun savedIdOrderFiltersInvalidAndAppendsNewAccounts() {
        var s=initial();val cash=id(s,"现金");val bank=id(s,"银行")
        s=s.copy(settings=s.settings.copy(defaultAccountOrder=listOf("missing",cash,cash,bank)))
        assertEquals(cash,defaultQuickEntryAccount(s,"CNY"))
        s=RichLedger.saveAccount(s,Account("",0,"自制新增",1))
        val ordered=orderedQuickEntryAccounts(s.accounts,s.settings.defaultAccountOrder)
        assertEquals(listOf(cash,bank),ordered.take(2).map{it.id});assertEquals("自制新增",ordered.last().name)
        val filtered=s.copy(accounts=s.accounts.map{if(it.id==cash)it.copy(archived=true)else if(it.id==bank)it.copy(active=false)else it})
        assertEquals(id(s,"微信"),defaultQuickEntryAccount(filtered,"CNY"))
        val renamed=s.copy(accounts=s.accounts.map{if(it.name=="微信")it.copy(name="自制微信别名")else it},settings=s.settings.copy(defaultAccountOrder=emptyList()))
        assertEquals(id(s,"微信"),defaultQuickEntryAccount(renamed,"CNY"))
    }
    @Test fun defaultsUseMatchingFiatOrCryptoActualUnits() {
        var s=initial()
        s=RichLedger.saveAccount(s,Account("",0,"自制美元零",0,"USD"));val usdZero=s.accounts.last().id
        s=RichLedger.saveAccount(s,Account("",0,"自制美元非零",-1,"USD"));val usd=s.accounts.last().id
        assertEquals(usd,defaultQuickEntryAccount(s,"USD"));assertNull(defaultQuickEntryAccount(s,"EUR"))
        s=RichLedger.saveAccount(s,Account("",0,"自制数字币一",0,"CRYPTO",AccountType.CRYPTO));val cryptoOne=s.accounts.last().id
        s=RichLedger.saveAccount(s,Account("",0,"自制数字币二",0,"CRYPTO",AccountType.CRYPTO));val cryptoTwo=s.accounts.last().id
        s=RichLedger.saveHoldings(s,listOf(com.qiye.ledgerflow.cashflow.data.CryptoHoldingEntity("BTC",1)),cryptoTwo)
        assertEquals(cryptoTwo,defaultQuickEntryAccount(s,"BTC"));assertEquals(cryptoOne,defaultQuickEntryAccount(s,"USDT"))
        assertTrue(usdZero!=usd)
    }
    @Test fun editingKeepsItsOriginalZeroOrMissingAccount() {
        val s=balances(initial(),"银行" to 0L)
        val old=Entry("self-created-old",100,EntryKind.EXPENSE,10,day,600,"自制历史",id(s,"银行"))
        assertEquals(old.accountId,initialQuickEntryAccount(s,old))
        assertNull(initialQuickEntryAccount(s,old.copy(accountId=null)))
        assertEquals(id(s,"微信"),initialQuickEntryAccount(s,null))
    }
    @Test fun nextEntryUsesTheSuccessfullySavedBalanceWithoutSplitting() {
        val s=initial();val wechat=id(s,"微信")
        val after=LedgerImport.saveCategorizedEntry(s,EntryDraft(EntryKind.EXPENSE,100,day,600,"自制花完",wechat),day)
        assertEquals(id(s,"银行"),defaultQuickEntryAccount(after,"CNY"))
        val overdrawn=LedgerImport.saveCategorizedEntry(s,EntryDraft(EntryKind.EXPENSE,150,day,600,"自制负余额",wechat),day)
        assertEquals(wechat,defaultQuickEntryAccount(overdrawn,"CNY"));assertEquals(1,overdrawn.entries.size)
    }
    @Test fun fullBackupAndRepositoryRestartKeepOrderAndOldOrBadPreferencesFallback() {
        val initial=initial();val order=listOf(id(initial,"支付宝"),id(initial,"微信"))
        val s=initial.copy(settings=initial.settings.copy(defaultAccountOrder=order));val codec=JsonLedgerCodec()
        assertEquals(s,codec.decode(codec.encode(s)))
        val old=JSONObject(codec.encode(s));old.getJSONObject("settings").remove("defaultAccountOrder")
        assertTrue(codec.decode(old.toString()).settings.defaultAccountOrder.isEmpty())
        val bad=JSONObject(codec.encode(s));bad.getJSONObject("settings").put("defaultAccountOrder",JSONArray().put(42).put(JSONObject.NULL).put("").put(order.first()).put(order.first()))
        assertEquals(listOf(order.first()),codec.decode(bad.toString()).settings.defaultAccountOrder)
        bad.getJSONObject("settings").put("defaultAccountOrder","invalid")
        assertEquals(id(s,"微信"),defaultQuickEntryAccount(codec.decode(bad.toString()),"CNY"))
        val folder=Files.createTempDirectory(File("build").toPath(),"self-created-order-").toFile()
        try{val repo=LedgerRepository(AtomicLedgerStore(folder));repo.open();repo.mutate{s};assertEquals(order,LedgerRepository(AtomicLedgerStore(folder)).open().settings.defaultAccountOrder)}finally{folder.deleteRecursively()}
    }
    @Test fun moduleClearsAndRestoresKeepLocalOrderAndSnapshotsExcludeIt() {
        var s=initial();s=s.copy(settings=s.settings.copy(defaultAccountOrder=listOf(id(s,"现金"),id(s,"银行"))))
        s=LedgerImport.saveCategorizedEntry(s,EntryDraft(EntryKind.EXPENSE,10,day,600,"自制账目",id(s,"银行")),day)
        val local=s.settings.defaultAccountOrder
        assertEquals(local,DataModules.clearBook(s,day).settings.defaultAccountOrder)
        assertEquals(local,DataModules.clearFlow(s).settings.defaultAccountOrder)
        for(module in DataModule.entries){
            assertTrue(DataModules.snapshot(s,module,day).settings.defaultAccountOrder.isEmpty())
            val preview=ModuleExchange.nativePreview(ModuleExchange.nativeExport(s,module,day).bytes.toString(Charsets.UTF_8),module)
            assertTrue(preview.data.settings.defaultAccountOrder.isEmpty())
            assertEquals(local,DataModules.restore(s,preview.data,module,day,preview.key).settings.defaultAccountOrder)
        }
    }
    @Test fun changingOrderDoesNotChangeFundingAssetsOrForecast() {
        val before=initial();val after=before.copy(settings=before.settings.copy(defaultAccountOrder=listOf(id(before,"现金"),"stale")))
        assertEquals(LedgerEngine.balances(before),LedgerEngine.balances(after));assertEquals(HomeAssets.calculate(before),HomeAssets.calculate(after))
        assertEquals(LedgerForecast.forecast(before,day,30),LedgerForecast.forecast(after,day,30));assertEquals(before.entries,after.entries)
    }
}
