package com.qiye.ledgerflow.data

import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.cashflow.data.*
import com.qiye.ledgerflow.importer.*
import com.qiyue.cashflow.shared.CoreRepeatRule
import com.qiyue.cashflow.shared.CivilDate
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** All fixtures are independently invented. Optional private probe only reads explicitly supplied local files. */
class MigrationTest {
    private val today=LedgerCalendar.parse("2026-10-22")
    private val start=CivilDate.fromEpochDay(today).plusMonths(-3).toEpochDay()
    private val codec=JsonLedgerCodec()
    private fun source(applied:Boolean=false):ForecastBackup {
        val accounts=listOf(AccountEntity(11,"自制甲",10_000,30_000),AccountEntity(22,"自制乙",20_000,20_000),
            AccountEntity(33,"自制美元",1_000,5_000,"USD",7_100_000,true,AccountType.PAYPAL),
            AccountEntity(44,"自制数字币",0,0,"CRYPTO",0,true,AccountType.CRYPTO),
            AccountEntity(55,"自制排除",0,80_000,includeInForecast=false),AccountEntity(66,"自制停用",0,90_000,isActive=false))
        val plans=listOf(PlannedTransactionEntity(101,"自制月分期",EntryType.EXPENSE,1000,accountName="自制甲",startDateEpochDay=start,repeatRule=RepeatRule.MONTHLY,installmentTotal=6,installmentRemaining=3,installmentTotalAmountCents=6001,balanceAppliedThroughEpochDay=today-1),
            PlannedTransactionEntity(102,"自制季分期",EntryType.EXPENSE,1000,accountName="自制乙",startDateEpochDay=start,repeatRule=RepeatRule.QUARTERLY,installmentTotal=40,installmentRemaining=39,installmentTotalAmountCents=40_001,balanceAppliedThroughEpochDay=today-1),
            PlannedTransactionEntity(103,"自制美元收入",EntryType.INCOME,100,currencyCode="USD",accountName="自制美元",startDateEpochDay=today,repeatRule=RepeatRule.MONTHLY,balanceAppliedThroughEpochDay=today-1))
        val budgets=listOf(BudgetEntity(201,"自制预算",6000,4000,BudgetPeriod.MONTHLY,today,today+30,"自制甲",carryOver=true))
        val adjustments=listOf(AdjustmentEntity(301,"自制校准记录",AdjustmentType.BALANCE_CALIBRATION,400,"自制甲",today-5),
            AdjustmentEntity(302,"自制逾期",AdjustmentType.TEMPORARY,500,"自制甲",today-2,appliedToBalance=applied),
            AdjustmentEntity(303,"自制未来",AdjustmentType.TEMPORARY,700,"自制乙",today+2))
        val settings=AppSettingsEntity(usdRateMicros=7_123_456,defaultHorizonDays=180,budgetMonthStartDay=5,showUsdOnHome=false,showCryptoOnHome=false,showPaypalOnAccounts=false,hiddenAccountIdsCsv="22",hiddenHomeCurrenciesCsv="USD,CRYPTO",appMode="CUSTOM",customModulesCsv="BUDGET,CRYPTO",showFinanceTips=false,financeTipChangesDaily=true,onboardingCompleted=true,seeded=true)
        return ForecastBackup(accounts,plans,budgets,adjustments,settings,listOf(CryptoHoldingEntity("BTC",123_456_789),CryptoHoldingEntity("USDT",20_000_000),CryptoHoldingEntity("LTC",50_000_000)))
    }
    private fun preview(b:ForecastBackup=source())=CashflowImporter.preview(BackupJson.encode(b))
    private fun row(vararg v:Pair<String,String>):List<String>{val values=mapOf("日期" to "2026-10-21 10:20:31","金额" to "20","类型" to "支出","分类" to "自制父","子分类" to "自制子")+v;return TableImporter.HEADERS.map{values[it].orEmpty()}}
    private fun csv(rows:List<List<String>>)= (listOf(TableImporter.HEADERS)+rows).joinToString("\r\n"){r->r.joinToString(","){"\""+it.replace("\"","\"\"")+"\""}}.toByteArray(Charsets.UTF_8)
    private fun reject(fn:()->Unit){try{fn();fail("Should reject")}catch(e:IllegalArgumentException){}}

    @Test fun legacyCurrentBalancesMetadataVisibilityAndAllSourceFieldsSurvive(){
        val p=preview();val s=p.build(LedgerState(),today,CashflowMigrationOptions(adoptOverall=true)).state
        assertEquals(50_000,LedgerEngine.balances(s).overallMinor);assertEquals(6,s.accounts.size);assertEquals(5,s.plans.size);assertEquals(1,s.budgets.size)
        val a=s.accounts.first{it.name=="自制甲"};assertEquals(10_000,a.openingMinor);assertEquals(30_000,LedgerEngine.balances(s).accountsMinor.getValue(a.id))
        val usd=s.accounts.first{it.currencyCode=="USD"};assertEquals(7_100_000,usd.exchangeRateToCnyMicros)
        assertFalse(s.accounts.first{it.name=="自制排除"}.includeInForecast)
        val hidden=s.settings.cashflow.hiddenAccountIdsCsv.split(',').map(String::toLong);assertTrue(s.accounts.first{it.name=="自制乙"}.sequence in hidden);assertTrue(usd.sequence in hidden);assertFalse(s.settings.cashflow.automaticPosting)
        assertEquals("CUSTOM",s.settings.cashflow.appMode);assertEquals("BUDGET,CRYPTO",s.settings.cashflow.customModulesCsv)
        assertEquals(p.originalJson,s.cashflowSources.single().originalJson);assertEquals(s,codec.decode(codec.encode(s)))
        assertEquals(123_456_789,RichLedger.cryptoBalances(s).getValue("BTC"));assertEquals(4_000,BudgetRules.remaining(s,s.budgets.single(),today))
    }
    @Test fun migrationKeepsActualsAndRequiresExplicitOverallAndForecastChoices(){
        var before=LedgerEngine.correctBalance(LedgerState(),null,100_000,today,0,"自制基点")
        before=LedgerImport.saveCategorizedEntry(before,EntryDraft(EntryKind.EXPENSE,2000,today,0,"自制支出"),today)
        before=LedgerEngine.savePlan(before,PlanDraft("自制原计划",EntryKind.EXPENSE,2000,today+1,"自制计划"))
        val p=preview();val merged=p.build(before,today).state
        assertEquals(before.entries,merged.entries);assertEquals(before.categories,merged.categories);assertEquals(98_000,LedgerEngine.balances(merged).overallMinor);assertTrue(merged.plans.first{it.title=="自制原计划"}.active)
        val explicit=p.build(before,today,CashflowMigrationOptions(disableOtherForecast=true,adoptOverall=true,overallMinor=70_000)).state
        assertFalse(explicit.plans.first{it.title=="自制原计划"}.active);assertEquals(before.entries,explicit.entries);assertEquals(70_000,LedgerEngine.balances(explicit).overallMinor)
    }
    @Test fun canonicalReimportAndChangedSourceEntityIdsDoNotDuplicateOrResetActuals(){
        val p=preview();var s=p.build(LedgerState(),today).state
        val root=JSONObject(p.originalJson);val reformatted=root.toString(4).replace("\n","\r\n")
        assertEquals(p.sourceKey,CashflowImporter.preview(reformatted).sourceKey);assertEquals(s,CashflowImporter.preview(reformatted).build(s,today).state)
        val plan=s.plans.first{it.title=="自制月分期"};s=RichLedger.confirmOccurrence(s,plan.id,today,today,0,today)
        val changed=source().copy(plans=source().plans.map{if(it.id==101L)it.copy(name="自制来源改名",amountCents=1100)else it})
        val updated=preview(changed).build(s,today,CashflowMigrationOptions(updateKnown=true)).state
        assertEquals(s.plans.size,updated.plans.size);assertEquals(s.plans.first{it.id==plan.id},updated.plans.first{it.id==plan.id});assertEquals(s.entries,updated.entries)
        assertEquals(2,updated.cashflowSources.size);assertEquals(updated,codec.decode(codec.encode(updated)))
    }
    @Test fun installmentCursorOverdueAndAppliedAdjustmentNeverBackfill(){
        val p=preview();var s=p.build(LedgerState(),today).state
        val late=s.plans.first{it.title=="自制逾期"};assertTrue(s.entries.isEmpty());assertFalse(RichLedger.pending(s,today+30).any{it.plan.id==late.id})
        assertTrue(RichLedger.occurs(late,late.dueEpochDay));s=RichLedger.postponeOccurrence(s,late.id,late.dueEpochDay,today+1);assertTrue(RichLedger.pending(s,today+2).any{it.plan.id==late.id})
        val installment=s.plans.first{it.title=="自制月分期"};assertEquals(3,installment.installmentRemaining);assertEquals(today,installment.postingFromEpochDay);assertEquals(3,RichLedger.occurrences(s,installment,today+180,today).size)
        val applied=preview(source(true)).build(LedgerState(),today).state;val actual=applied.entries.single();assertFalse(actual.affectsBalance);assertFalse(actual.affectsBudget);assertTrue(actual.excludeFromStatistics)
        assertFalse(RichLedger.pending(applied,today+30).any{it.plan.title=="自制逾期"});assertEquals(applied,RichLedger.automaticPost(applied,today+30));assertEquals(0,LedgerEngine.balances(applied).overallMinor)
    }
    @Test fun oldPerInstallmentAmountModeAndCompletedInstallmentsKeepTheirMeaning(){
        val old=source().copy(plans=source().plans.map{when(it.id){101L->it.copy(installmentTotalAmountCents=0);102L->it.copy(installmentRemaining=0);else->it}})
        val s=preview(old).build(LedgerState(),today).state;val active=s.plans.first{it.title=="自制月分期"};val complete=s.plans.first{it.title=="自制季分期"}
        assertEquals(0,active.installmentTotalAmountMinor);assertEquals(1000,RichLedger.occurrenceAmount(active,today));assertEquals(3,RichLedger.occurrences(s,active,today+180,today).size);assertTrue(RichLedger.occurrences(s,complete,today+365,today).isEmpty());assertEquals(s,codec.decode(codec.encode(s)))
    }
    @Test fun malformedAndUnknownLegacyBackupVersionsAndReferencesAreRejected(){
        val raw=BackupJson.encode(source());reject{CashflowImporter.preview(raw+"x")};reject{CashflowImporter.preview(JSONObject(raw).put("version",7).toString())}
        val nonInteger=JSONObject(raw);nonInteger.getJSONArray("accounts").getJSONObject(0).put("balance",1.5);reject{CashflowImporter.preview(nonInteger.toString())}
        val dangling=JSONObject(raw);dangling.getJSONArray("plans").getJSONObject(0).put("account","不存在");reject{CashflowImporter.preview(dangling.toString())}
        val collision=source().copy(accounts=source().accounts+source().accounts.first());reject{preview(collision)}
        val coerced=JSONObject(raw);coerced.getJSONArray("plans").getJSONObject(0).put("installmentTotalAmount","0.5");reject{CashflowImporter.preview(coerced.toString())}
        val boolText=JSONObject(raw);boolText.getJSONArray("accounts").getJSONObject(0).put("active","true");reject{CashflowImporter.preview(boolText.toString())}
    }
    @Test fun historicalDebtPairingAllowsDifferentAccountsAndIsIdempotentAndInterestUnknown(){
        val rows=listOf(row("类型" to "债务","分类" to "还款","子分类" to "","债务人" to "自制对方","金额" to "2000","日期" to "2026-05-20 11:22:33","账户" to "无账户"),row("类型" to "债务","分类" to "借入","子分类" to "","债务人" to "自制对方","金额" to "2000","日期" to "2026-04-20 09:10:11","账户" to "自制来源"))
        val before=preview().build(LedgerState(),today,CashflowMigrationOptions(adoptOverall=true)).state;val table=TableImporter().preview(csv(rows),"self.csv",before,today)
        assertEquals(2,table.ready.size);assertTrue(table.lines.all{it.record?.debtGroupKey!=null});val s=LedgerImport.commit(before,table.ready,today).state
        assertEquals(1,s.debts.size);assertEquals(0,RichLedger.outstanding(s,s.debts.single().id));assertFalse(s.debts.single().interestProvided);assertEquals(0,LedgerEngine.periodSummary(s,LedgerCalendar.parse("2026-01-01")..today).incomeMinor);assertEquals(0,LedgerEngine.periodSummary(s,LedgerCalendar.parse("2026-01-01")..today).expenseMinor)
        assertFalse(s.accounts.any{it.name=="无账户"});assertEquals(50_000,LedgerEngine.balances(s).overallMinor);assertTrue(s.entries.all{!it.affectsBalance&&!it.affectsBudget});assertEquals(listOf(11,33),s.entries.sortedBy{it.epochDay}.map{it.second})
        assertEquals(0,TableImporter().preview(csv(rows.reversed()),"again.csv",s,today).ready.size);assertEquals(s,LedgerImport.commit(s,table.ready,today).state);assertEquals(s,codec.decode(codec.encode(s)))
    }
    @Test fun ambiguousDebtRowsAreVisibleAndDoNotInventRelations(){
        val common=arrayOf("类型" to "债务","子分类" to "","债务人" to "自制同名人")
        val rows=listOf(row(*common,"分类" to "借入","金额" to "2000"),row(*common,"分类" to "借入","金额" to "1000"),row(*common,"分类" to "还款","金额" to "100"))
        val p=TableImporter().preview(csv(rows),"ambiguous.csv",LedgerState(),today);assertEquals(0,p.count(ImportStatus.UNSUPPORTED));assertEquals(3,p.ready.size);val s=LedgerImport.commit(LedgerState(),p.ready,today).state;assertEquals(2,s.debts.size);assertEquals(0,LedgerImport.pendingSettlements(s).size)
    }
    @Test fun categoryPreviewMatchesParentChildCreationAndHistoricalBudgetStaysUntouched(){
        var s=preview().build(LedgerState(),today,CashflowMigrationOptions(adoptOverall=true)).state
        val rows=listOf(row("类型" to "支出","金额" to "1"),row("类型" to "支出","金额" to "2"),row("类型" to "收入","金额" to "3"))
        val table=TableImporter().preview(csv(rows),"category.csv",s,today);assertEquals(4,table.newCategories.size);assertTrue(s.categories.isEmpty());val before=LedgerEngine.balances(s);val budget=BudgetRules.remaining(s,s.budgets.single(),today)
        s=LedgerImport.commit(s,table.ready,today).state;assertEquals(4,s.categories.size);assertEquals(2,s.categories.count{it.parentId==null});assertEquals(2,s.categories.count{it.parentId!=null});assertEquals(before,LedgerEngine.balances(s));assertEquals(budget,BudgetRules.remaining(s,s.budgets.single(),today));assertEquals(0,TableImporter().preview(csv(rows),"again.csv",s,today).newCategories.size)
        val e=s.entries.first();s=LedgerEngine.saveEntry(s,EntryDraft(e.kind,9999,e.epochDay,e.minuteOfDay,"改备注分类",note="自制"),today,e.id);s=LedgerEngine.deleteEntry(s,e.id);assertEquals(before,LedgerEngine.balances(s));assertEquals(budget,BudgetRules.remaining(s,s.budgets.single(),today))
    }
    @Test fun protectedMigrationMakesExactRecoveryCopyAndFailureDoesNotPublish(){
        val dir=Files.createTempDirectory(File("build").toPath(),"migration-self-").toFile()
        try{val store=AtomicLedgerStore(dir);val repo=LedgerRepository(store);repo.open();repo.mutate{LedgerEngine.correctBalance(it,null,12300,today,0,"自制")};val original=store.dataFile.readText();repo.migrate{preview().build(it,today).state};assertEquals(original,store.latestRecoveryFile()!!.readText());assertEquals(repo.snapshot(),LedgerRepository(AtomicLedgerStore(dir)).open())}finally{dir.deleteRecursively()}
        var backup=false;val failStore=object:LedgerStore{override fun load()=LedgerState();override fun save(s:LedgerState){throw IllegalStateException("自制写失败")};override fun saveBeforeRestore(){backup=true}}
        val repo=LedgerRepository(failStore);val before=repo.open();try{repo.migrate{preview().build(it,today).state};fail()}catch(_:IllegalStateException){};assertTrue(backup);assertEquals(before,repo.snapshot())
    }
    @Test fun v2LedgerUpgradePreservesOriginalBytesAndDefaultMetadata(){
        val original=LedgerEngine.addAccount(LedgerEngine.correctBalance(LedgerState(),null,12345,today,0,"自制"),"自制旧账户",300)
        val root=JSONObject(codec.encode(original)).put("schemaVersion",2);root.remove("cashflowSources")
        root.getJSONArray("accounts").getJSONObject(0).remove("includeInForecast");root.getJSONArray("accounts").getJSONObject(0).remove("exchangeRateToCnyMicros")
        val raw=root.toString();assertEquals(original,codec.decode(raw))
        val dir=Files.createTempDirectory(File("build").toPath(),"v2-self-").toFile()
        try{val store=AtomicLedgerStore(dir);store.dataFile.writeText(raw);val repo=LedgerRepository(store);assertEquals(original,repo.open());repo.mutate{it.copy(settings=it.settings.copy(monthStartDay=2))};assertEquals(raw,store.latestRecoveryFile()!!.readText());assertEquals(7,JSONObject(store.dataFile.readText()).getInt("schemaVersion"))}finally{dir.deleteRecursively()}
    }
    @Test fun accountNameCurrencyConflictsAreRenamedWithReferencesMapped(){
        val before=RichLedger.saveAccount(LedgerState(),Account("",0,"自制甲",200,"USD",AccountType.PAYPAL))
        val s=preview().build(before,today).state
        val old=s.accounts.first{it.id==before.accounts.single().id};assertEquals("USD",old.currencyCode);assertEquals(200,LedgerEngine.balances(s).accountsMinor.getValue(old.id))
        val mapped=s.accounts.first{it.currencyCode=="CNY"&&it.name.startsWith("自制甲（现金流")};assertEquals(mapped.id,s.plans.first{it.title=="自制月分期"}.accountId);assertEquals(mapped.name,s.budgets.single().accountName)
        assertEquals(s.accounts.size,s.accounts.map{it.name}.distinct().size);assertEquals(s,codec.decode(codec.encode(s)))
    }
    @Test fun privateInputsAreOnlyProcessedWhenExplicitlyProvided(){
        val path=System.getenv("LEDGER_PRIVATE_CASHFLOW");assumeTrue(path!=null)
        val input=File(path!!);val dir=input.parentFile.canonicalFile
        val csvFiles=System.getenv("LEDGER_PRIVATE_CSVS").orEmpty().split('|').filter{it.isNotBlank()}.map(::File);require(csvFiles.size==2&&csvFiles.all{it.canonicalFile.parentFile==dir}){"Private source whitelist invalid"}
        val p=CashflowImporter.preview(input.readText());var s=p.build(LedgerState(),today,CashflowMigrationOptions(adoptOverall=true)).state
        val originalBudget=s.budgets.map{BudgetRules.remaining(s,it,today)}
        val tableReports=JSONArray()
        csvFiles.forEach{file->val table=TableImporter().preview(file.readBytes(),file.name,s,today);require(table.count(ImportStatus.ERROR)==0&&table.count(ImportStatus.UNSUPPORTED)==0){"Private parser has unresolved rows"};tableReports.put(JSONObject().put("rows",table.lines.size).put("ready",table.ready.size).put("newParentCategories",table.newCategories.count{it.endsWith("（父分类）")}).put("newChildCategories",table.newCategories.count{it.endsWith("（子分类）")}));s=LedgerImport.commit(s,table.ready,today).state}
        val fullRange=LedgerCalendar.parse("1900-01-01")..today;val stat=LedgerEngine.periodSummary(s,fullRange)
        require(p.build(s,today).state==s){"Cashflow reimport changed private candidate"}
        var repeats=0;csvFiles.forEach{file->val table=TableImporter().preview(file.readBytes(),file.name,s,today);repeats+=table.ready.size};require(repeats==0){"Table reimport duplicated private candidate"}
        require(originalBudget==s.budgets.map{BudgetRules.remaining(s,it,today)}){"History altered budget"}
        require(s.entries.all{!it.affectsBalance&&!it.affectsBudget}){"History altered current baseline flags"}
        val source=s.cashflowSources.single()
        require(BackupJson.decode(source.originalJson)==p.source){"Legacy source fields were lost"}
        require(p.source.accounts.all{a->val id=source.accountIds.getValue(a.id);val mapped=s.accounts.first{it.id==id};mapped.openingMinor==a.initialBalanceCents&&mapped.includeInForecast==a.includeInForecast&&mapped.exchangeRateToCnyMicros==a.exchangeRateToCnyMicros&&mapped.active==a.isActive&&(a.currencyCode=="CRYPTO"||LedgerEngine.balances(s).accountsMinor.getValue(id)==a.currentBalanceCents)}){"Legacy account fields were lost"}
        require(p.source.plans.all{old->val mapped=s.plans.first{it.id==source.planIds[old.id]};mapped.dueEpochDay==old.startDateEpochDay&&mapped.endEpochDay==old.endDateEpochDay&&mapped.installmentTotal==old.installmentTotal&&mapped.installmentRemaining==old.installmentRemaining&&mapped.installmentTotalAmountMinor==old.installmentTotalAmountCents&&mapped.postingFromEpochDay==old.balanceAppliedThroughEpochDay?.plus(1)}){"Legacy plan cursor/period fields were lost"}
        val encoded=codec.encode(s);require(codec.decode(encoded)==s){"Candidate all-field round trip failed"}
        val out=File(dir,"候选迁入账本-0.2.1.json");if(out.exists())require(out.readText()==encoded){"Existing candidate differs; do not overwrite"}else out.writeText(encoded)
        val digest=MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).joinToString(""){"%02x".format(it)}
        val report=JSONObject().put("schemaVersion",3).put("fromEmptyLedger",true).put("containsPhoneTrialEntries",false).put("includesReferenceXlsx",false).put("accounts",s.accounts.size).put("fixedPlans",s.plans.count{!it.temporary}).put("temporaryPlans",s.plans.count{it.temporary}).put("budgets",s.budgets.size).put("legacyAdjustments",p.source.adjustments.size).put("calibrationRecordsNotExecuted",p.source.adjustments.count{it.type==AdjustmentType.BALANCE_CALIBRATION}).put("overdueUnappliedTemporary",p.source.adjustments.count{it.type==AdjustmentType.TEMPORARY&&!it.appliedToBalance&&it.dateEpochDay<today}).put("overduePolicy","保留原日与未确认状态，不补记余额、不挤到今日预测；可手动确认或延期").put("automaticPosting",s.settings.cashflow.automaticPosting).put("holdings",s.holdings.size).put("actualRows",s.entries.size).put("ordinaryRows",s.entries.count{it.kind.isOrdinary()}).put("expenseMinor",stat.expenseMinor).put("incomeMinor",stat.incomeMinor).put("parents",s.categories.count{it.parentId==null}).put("children",s.categories.count{it.parentId!=null}).put("withSubcategory",s.entries.count{it.subcategory.isNotBlank()}).put("withNote",s.entries.count{it.note.isNotBlank()}).put("ordinaryWithNote",s.entries.count{it.kind.isOrdinary()&&it.note.isNotBlank()}).put("legacySourceAllFieldsRetained",true).put("initialAndCurrentBalancesDistinctAndPreserved",true).put("accountInclusionAndRatesPreserved",true).put("planCursorsAndInstallmentsPreserved",true).put("debts",s.debts.size).put("outstandingPrincipalMinor",s.debts.fold(0L){n,d->Money.add(n,RichLedger.outstanding(s,d.id))}).put("interestProvided",s.debts.all{it.interestProvided}).put("allFieldsRoundTrip",true).put("historyPreservesBudget",true).put("cashflowSemanticReimportChanges",0).put("tableReimportNewRows",repeats).put("suggestedCnyOverallMinor",p.build(LedgerState(),today).suggestedOverallMinor).put("candidateSha256",digest).put("tables",tableReports)
        val ref=System.getenv("LEDGER_PRIVATE_XLSX_REFERENCE")?.let(::File)
        if(ref!=null){require(ref.canonicalFile.parentFile==dir);val r=TableImporter().preview(ref.readBytes(),ref.name,LedgerState(),today);report.put("referenceXlsx",JSONObject().put("rows",r.lines.size).put("ready",r.ready.size).put("errors",r.count(ImportStatus.ERROR)).put("unsupported",r.count(ImportStatus.UNSUPPORTED)).put("notCommitted",true).put("unsupportedReasons",JSONArray(r.lines.filter{it.status==ImportStatus.UNSUPPORTED}.map{it.message})))}
        File(dir,"迁入预览汇总.json").writeText(report.toString(2))
    }
}
