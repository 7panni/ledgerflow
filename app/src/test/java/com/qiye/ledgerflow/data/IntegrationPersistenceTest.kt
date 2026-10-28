package com.qiye.ledgerflow.data

import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.cashflow.data.*
import com.qiye.ledgerflow.importer.*
import com.qiyue.cashflow.shared.CoreRepeatRule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class IntegrationPersistenceTest {
    private val today=LedgerCalendar.parse("2026-10-21")
    private val codec=JsonLedgerCodec();private val importer=TableImporter()
    private fun initial()=LedgerEngine.correctBalance(LedgerState(),null,100_000,today,600,"自制整体")
    private fun row(vararg fields:Pair<String,String>):List<String>{val map=mapOf("日期" to "2026-10-21 10:20:30","金额" to "100.00","类型" to "支出","分类" to "自制分类","子分类" to "自制子类","备注" to "自制备注","标签" to "测试,日常")+fields;return TableImporter.HEADERS.map{map[it].orEmpty()}}
    private fun csv(rows:List<List<String>>):ByteArray=(listOf(TableImporter.HEADERS)+rows).joinToString("\r\n"){r->r.joinToString(","){"\""+it.replace("\"","\"\"")+"\""}}.toByteArray(Charsets.UTF_8)
    private fun assertThrows(block:()->Unit){try{block();fail("Expected failure")}catch(e:Exception){}}

    @Test fun duplicateMultisetPreservesIdenticalRealRowsAndIsStableAcrossReordering(){
        val a=row();val preview=importer.preview(csv(listOf(a,a)),"test.csv",initial(),today);assertEquals(2,preview.count(ImportStatus.READY))
        var s=LedgerImport.commit(initial(),preview.ready,today).state;assertEquals(2,s.entries.size);assertEquals(100_000,LedgerEngine.balances(s).overallMinor)
        val repeat=importer.preview(csv(listOf(a,a)),"test.csv",s,today);assertEquals(0,repeat.ready.size);assertEquals(2,repeat.count(ImportStatus.DUPLICATE))
        val three=importer.preview(csv(listOf(a,a,a)),"test.csv",s,today);assertEquals(1,three.ready.size);s=LedgerImport.commit(s,three.ready,today).state;assertEquals(3,s.entries.size)
        val b=row("备注" to "另一笔");val reordered=importer.preview(csv(listOf(a,b,a,a)),"test.csv",s,today);assertEquals(1,reordered.ready.size);assertEquals("另一笔",reordered.ready.single().note)
    }
    @Test fun xlsxNumericDateSecondsAndEquivalentDefaultsMatchCsvIdentity(){
        val note="第一行\r\n第二行,含逗号和\"引号\""
        val a=row("备注" to note,"转账手续费" to "","退款金额" to "","报销金额" to "")
        val p=importer.preview(csv(listOf(a)),"test.csv",initial(),today);assertEquals(1,p.ready.size)
        val s=LedgerImport.commit(initial(),p.ready,today).state
        val b=row("备注" to note.replace("\r\n","\n"),"转账手续费" to "0","退款金额" to "0.00","报销金额" to "0")
        val x=importer.preview(xlsx(b,10*3600+20*60+30),"test.xlsx",s,today)
        assertEquals(x.lines.toString(),1,x.count(ImportStatus.DUPLICATE));assertEquals(30,x.lines.single().record!!.second)
        val minute=row("日期" to "2026-10-21 10:20");val m=importer.preview(xlsx(minute,10*3600+20*60),"minute.xlsx",initial(),today);assertEquals(0,m.ready.single().second);assertEquals(620,m.ready.single().minuteOfDay)
    }
    @Test fun unsupportedTypesStatusesForeignAccountsAndMissingDatesAreVisibleInPreview(){
        val s=RichLedger.saveAccount(initial(),Account("",0,"自制美元",0,"USD",AccountType.PAYPAL))
        val p=importer.preview(csv(listOf(row("类型" to "未知类型"),row("账单状态" to "未知状态"),row("账户" to "自制美元"),row("退款金额" to "1","退款日期" to ""))),"errors.csv",s,today)
        assertEquals(0,p.ready.size);assertEquals(3,p.count(ImportStatus.UNSUPPORTED));assertEquals(1,p.count(ImportStatus.ERROR));assertEquals(100_000,LedgerEngine.balances(s).overallMinor)
    }
    @Test fun feesRefundsReimbursementsFlagsAndMissingRelationsAreExplicit(){
        val expense=row("退款金额" to "20","退款日期" to "2026-10-21","报销金额" to "10","报销日期" to "2026-10-21","是否为报销" to "是","债务人" to "自制对方","账单图片" to "自制图片引用")
        val transfer=row("类型" to "转账","账户" to "自制甲","转入账户" to "自制乙","转账手续费" to "5")
        val excluded=row("不计入收支" to "是","不计入预算" to "是","备注" to "排除项")
        val p=importer.preview(csv(listOf(expense,transfer,excluded)),"mapping.csv",initial(),today);assertEquals(3,p.ready.size);assertTrue(p.ready.first().warnings.any{it.contains("图片")});assertTrue(p.ready.first().warnings.any{it.contains("债务")})
        val s=LedgerImport.commit(initial(),p.ready,today).state;assertEquals(75_00,LedgerEngine.periodSummary(s,today..today).expenseMinor);assertEquals(100_000,LedgerEngine.balances(s).overallMinor);assertEquals(0,LedgerEngine.balances(s).accountsMinor.values.sum())
        assertEquals(2,s.entries.count{it.kind==EntryKind.REFUND});assertTrue(s.entries.filter{it.kind==EntryKind.REFUND}.all{it.refundOfId!=null});assertTrue(s.debts.isEmpty())
    }
    @Test fun importedHistoryCanBeEditedDeletedAndReopenedWithoutMovingCurrentBaseline(){
        val folder=folder();try{
            val repo=LedgerRepository(AtomicLedgerStore(folder));repo.open();repo.mutate{initial()}
            val p=importer.preview(csv(listOf(row())),"history.csv",repo.snapshot(),today);repo.mutate{LedgerImport.commit(it,p.ready,today).state}
            val entry=repo.snapshot().entries.single();repo.mutate{LedgerEngine.saveEntry(it,EntryDraft(EntryKind.EXPENSE,50_000,today,600,"改分类",note="改备注"),today,entry.id)}
            var s=LedgerRepository(AtomicLedgerStore(folder)).open();assertFalse(s.entries.single().affectsBalance);assertFalse(s.entries.single().affectsBudget);assertEquals(100_000,LedgerEngine.balances(s).overallMinor)
            repo.mutate{LedgerEngine.deleteEntry(it,entry.id)};s=LedgerRepository(AtomicLedgerStore(folder)).open();assertEquals(100_000,LedgerEngine.balances(s).overallMinor);assertEquals(1,s.importedKeys.size)
        }finally{folder.deleteRecursively()}
    }
    @Test fun v1UpgradePreservesOriginalActionsAndSavesExactOriginalBeforeFirstReplacement(){
        var old=LedgerEngine.addAccount(initial(),"自制CNY",50_000);old=LedgerEngine.saveEntry(old,EntryDraft(EntryKind.EXPENSE,1_000,today,600,"自制旧分类",old.accounts.single().id),today)
        val root=JSONObject(codec.encode(old));root.put("schemaVersion",1);listOf("categories","debts","budgets","holdings","occurrenceOverrides","importedKeys").forEach(root::remove);root.getJSONObject("settings").remove("cashflow")
        val raw=root.toString();val folder=folder();try{
            val store=AtomicLedgerStore(folder);store.dataFile.writeText(raw);val repo=LedgerRepository(store);val upgraded=repo.open();assertEquals(99_000,LedgerEngine.balances(upgraded).overallMinor);assertEquals(49_000,LedgerEngine.balances(upgraded).accountsMinor.values.single());assertEquals(old.entries.single().sequence,upgraded.entries.single().sequence);assertNotNull(upgraded.entries.single().categoryId)
            repo.mutate{it.copy(settings=it.settings.copy(monthStartDay=10))};assertEquals(raw,store.latestRecoveryFile()!!.readText());assertEquals(7,JSONObject(store.dataFile.readText()).getInt("schemaVersion"));assertEquals(repo.snapshot(),LedgerRepository(AtomicLedgerStore(folder)).open())
        }finally{folder.deleteRecursively()}
    }
    @Test fun v2RoundTripRestoresAllMeaningfulFieldsLinksVisibilityAndOriginalCurrencyUnits(){
        var s=RichLedger.saveAccount(initial(),Account("",0,"自制CNY",10_000));val cny=s.accounts.single().id
        s=RichLedger.saveAccount(s,Account("",0,"自制USD",20_000,"USD",AccountType.PAYPAL));s=RichLedger.saveAccount(s,Account("",0,"自制币",0,"CRYPTO",AccountType.CRYPTO));val crypto=s.accounts.last().id
        s=RichLedger.saveHoldings(s,listOf(CryptoHoldingEntity("BTC",123_456_789),CryptoHoldingEntity("USDT",100_000_000)),crypto)
        s=RichLedger.saveBudget(s,BudgetEntity(name="自制归属预算",totalCents=60_000,remainingCents=50_000,period=BudgetPeriod.MONTHLY,startDateEpochDay=today,endDateEpochDay=today+30,accountName="自制CNY",method=BudgetMethod.SPECIFIC_DAY,chargeDay=25,carryOver=true))
        s=LedgerImport.saveCategorizedEntry(s,EntryDraft(EntryKind.EXPENSE,1_000,today,620,"自制父",cny,note="自制备注",second=30,subcategory="自制子",tags="标签甲,标签乙",budgetId=s.budgets.single().id),today)
        s=LedgerEngine.savePlan(s,PlanDraft("自制周期",EntryKind.EXPENSE,100,today,"自制父",cny,repeatRule=CoreRepeatRule.WEEKLY,categoryId=s.categories.last().id,budgetId=s.budgets.single().id),today=today);val p=s.plans.last();s=RichLedger.confirmOccurrence(s,p.id,today,today,600,today);s=RichLedger.postponeOccurrence(s,p.id,today+7,today+8)
        s=RichLedger.createDebt(s,DebtKind.BORROWED,"自制债务",10_000,today,cny,today+10,500,"自制债务备注",today);s=RichLedger.settleDebt(s,s.debts.single().id,4_000,200,today,600,cny,today,"roundtrip-request",s.budgets.single().id)
        s=s.copy(settings=LedgerSettings(10,s.settings.cashflow.copy(usdRateMicros=7_123_456,defaultHorizonDays=180,budgetMonthStartDay=5,showUsdOnHome=false,showCryptoOnHome=false,showPaypalOnAccounts=false,showAlipayOnAccounts=false,showCryptoOnAccounts=false,hiddenHomeCurrenciesCsv="USD,CRYPTO",hiddenAccountIdsCsv=s.accounts.last().sequence.toString(),showFinanceTips=false,financeTipChangesDaily=true,appMode="CUSTOM",customModulesCsv="BUDGET,CRYPTO",automaticPosting=true,automaticPostingFromEpochDay=today)),importedKeys=listOf("b".repeat(64)+"#1"))
        val restored=codec.decode(codec.encode(s));assertEquals(s,restored);assertEquals(s,codec.decode(codec.encode(restored)));assertEquals(LedgerForecast.forecast(s,today,30),LedgerForecast.forecast(restored,today,30))
    }
    private fun folder():File{val base=File("build/test-ledgers").apply{mkdirs()};return Files.createTempDirectory(base.toPath(),"integration-self-").toFile()}
    private fun xlsx(row:List<String>,seconds:Int):ByteArray {
        fun escape(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
        fun column(i:Int):String=if(i<26)(('A'.code+i).toChar()).toString() else error("fixture only22 columns")
        val serial=ChronoUnit.DAYS.between(LocalDate.of(1899,12,30),LocalDate.ofEpochDay(today));val date=BigDecimal.valueOf(serial).add(BigDecimal.valueOf(seconds.toLong()).divide(BigDecimal(86400),12,RoundingMode.HALF_UP)).toPlainString()
        fun xmlRow(cells:List<String>,number:Int)=cells.mapIndexed{i,v->if(number==2&&i==0)"<c r=\"A2\" s=\"1\"><v>$date</v></c>" else "<c r=\"${column(i)}$number\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(v)}</t></is></c>"}.joinToString("",prefix="<row r=\"$number\">",postfix="</row>")
        val files=mapOf("xl/workbook.xml" to "<workbook><workbookPr date1904=\"0\"/></workbook>","xl/styles.xml" to "<styleSheet><cellXfs count=\"2\"><xf numFmtId=\"0\"/><xf numFmtId=\"14\"/></cellXfs></styleSheet>","xl/worksheets/sheet1.xml" to "<worksheet><sheetData>${xmlRow(TableImporter.HEADERS,1)}${xmlRow(row,2)}</sheetData></worksheet>")
        val out=java.io.ByteArrayOutputStream();ZipOutputStream(out).use{zip->files.forEach{(name,text)->zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray(Charsets.UTF_8));zip.closeEntry()}};return out.toByteArray()
    }
}
