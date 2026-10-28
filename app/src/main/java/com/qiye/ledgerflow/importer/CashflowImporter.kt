package com.qiye.ledgerflow.importer

import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.cashflow.data.*
import com.qiyue.cashflow.shared.CoreRepeatRule
import org.json.*
import java.security.MessageDigest

/** Legacy backup decoding is copied from the user's BackupJson; writing always uses the new durable repository. */
data class CashflowMigrationOptions(val disableOtherForecast:Boolean=false,val updateKnown:Boolean=false,
    val adoptOverall:Boolean=false,val overallMinor:Long?=null)
data class CashflowMigrationResult(val state:LedgerState,val messages:List<String>,val suggestedOverallMinor:Long,val duplicate:Boolean=false)
data class CashflowImportPreview(val source:ForecastBackup,val sourceKey:String,val originalJson:String) {
    fun build(state:LedgerState,today:Long,options:CashflowMigrationOptions=CashflowMigrationOptions()):CashflowMigrationResult = CashflowImporter.merge(this,state,today,options)
}
object CashflowImporter {
    fun preview(text:String):CashflowImportPreview {
        require(text.toByteArray(Charsets.UTF_8).size<=20*1024*1024){"现金流备份超过20MB"}
        val tok=JSONTokener(text);val root=tok.nextValue() as? JSONObject?:error("现金流备份根须为对象")
        require(tok.nextClean()=='\u0000'){"备份末尾内容损坏"}
        require(root.getString("format")=="cashflow-forecast" && root.get("version") is Number && root.get("version").toString()=="6"){"仅支持已验证的cashflow-forecast版本6，请保留原文件"}
        val numericKeys=setOf("version","id","initialBalance","balance","rate","amount","start","end","installmentTotal","installmentRemaining","installmentTotalAmount","balanceAppliedThrough","total","remaining","chargeDay","date","amountUnits","usdRate","horizon","budgetMonthStartDay")
        val booleanKeys=setOf("included","active","income","usesBudget","appliedToBalance","carryOver","showUsdOnHome","showCryptoOnHome","showPaypalOnAccounts","showAlipayOnAccounts","showCryptoOnAccounts","showFinanceTips","financeTipChangesDaily","onboardingCompleted","seeded")
        fun strict(value:Any?) { when(value){is JSONObject->value.keys().forEach { k->val v=value.get(k);if(v!==JSONObject.NULL&&k in numericKeys)require(v is Number){"金额/编号须为JSON整数，不能用文本替代"};if(v!==JSONObject.NULL&&k in booleanKeys)require(v is Boolean){"显示/状态字段须为布尔值"};strict(v) };is JSONArray->{require(value.length()<=100_000);(0 until value.length()).forEach{strict(value.get(it))}};is Number->require(value.toString().matches(Regex("-?[0-9]+"))&&value.toString().toLongOrNull()!=null){"金额/编号必须是精确整数"}} }
        strict(root)
        val b=BackupJson.decode(root.toString())
        require(b.accounts.map{it.id}.distinct().size==b.accounts.size && b.accounts.map{it.name}.distinct().size==b.accounts.size){"备份账户编号/名称重复"}
        require(b.plans.map{it.id}.distinct().size==b.plans.size && b.budgets.map{it.id}.distinct().size==b.budgets.size && b.adjustments.map{it.id}.distinct().size==b.adjustments.size){"备份计划/预算/调整编号重复"}
        val names=b.accounts.associateBy{it.name}
        b.accounts.forEach { a->require(a.id>0&&a.name.isNotBlank()&&a.currencyCode.matches(Regex("[A-Z]{2,8}"))&&a.exchangeRateToCnyMicros>=0){"备份账户字段无效"};Money.checked(a.initialBalanceCents);Money.checked(a.currentBalanceCents) }
        b.plans.forEach { a->require(a.id>0&&a.type in listOf(EntryType.INCOME,EntryType.EXPENSE,EntryType.DEBT)){"备份含尚不支持的计划类型，未迁入"};require(a.accountName in names){"计划账户引用缺失"};require(names.getValue(a.accountName).currencyCode==a.currencyCode){"计划与账户币种不一致"};require(a.amountCents>0);Money.checked(a.amountCents);LedgerCalendar.validateDay(a.startDateEpochDay);a.endDateEpochDay?.let(LedgerCalendar::validateDay);a.balanceAppliedThroughEpochDay?.let(LedgerCalendar::validateDay) }
        b.budgets.forEach { require(it.id>0); require(it.accountName.isBlank()||it.accountName in names){"预算账户引用缺失"} }
        b.adjustments.forEach { require(it.id>0); require(it.accountName in names){"调整账户引用缺失"};Money.checked(it.amountCents);require(it.type==AdjustmentType.BALANCE_CALIBRATION||it.amountCents>0);LedgerCalendar.validateDay(it.dateEpochDay) }
        require(b.holdings.map{it.symbol}.distinct().size==b.holdings.size && b.holdings.all{it.symbol in listOf("USDT","BTC","LTC")}){"数字币持仓重复或未知"}
        require(b.accounts.count{it.currencyCode=="CRYPTO"}<=1 || b.holdings.isEmpty()){"备份持仓没有账户归属且有多个数字币账户，请先在来源中明确归属"}
        b.holdings.forEach{Money.checked(it.amountUnits)}
        fun canonical(v:Any?):String=when(v){is JSONObject->v.keys().asSequence().toList().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+canonical(v.get(it))};is JSONArray->(0 until v.length()).map{v.get(it)}.let{items->if(items.all{it is JSONObject && it.has("id")})items.sortedBy{(it as JSONObject).getLong("id")} else if(items.all{it is JSONObject && it.has("symbol")})items.sortedBy{(it as JSONObject).getString("symbol")}else items}.joinToString(",","[","]"){canonical(it)};is String->JSONObject.quote(v.replace("\r\n","\n").replace('\r','\n'));else->v.toString()}
        val key=MessageDigest.getInstance("SHA-256").digest(canonical(root).toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        return CashflowImportPreview(b,key,text)
    }
    internal fun merge(preview:CashflowImportPreview,state:LedgerState,today:Long,options:CashflowMigrationOptions):CashflowMigrationResult {
        val b=preview.source;val suggested=b.accounts.filter{it.isActive&&it.includeInForecast&&it.currencyCode=="CNY"}.fold(0L){n,a->Money.add(n,a.currentBalanceCents)}
        if(state.cashflowSources.any{it.key==preview.sourceKey})return CashflowMigrationResult(state,listOf("此现金流备份内容已迁入，重复导入不会覆盖或再次执行。"),suggested,true)
        var next=state;val messages=mutableListOf<String>()
        val previousAccounts=state.cashflowSources.flatMap{it.accountIds.entries}.associate{it.toPair()}
        val previousPlans=state.cashflowSources.flatMap{it.planIds.entries}.associate{it.toPair()}
        val previousBudgets=state.cashflowSources.flatMap{it.budgetIds.entries}.associate{it.toPair()}
        if(options.disableOtherForecast){val ids=previousPlans.values.toSet();val count=next.plans.count{it.id !in ids&&it.debtId==null&&next.entries.none{e->e.planId==it.id}&&it.active};next=next.copy(plans=next.plans.map{p->if(p.id !in ids&&p.debtId==null&&next.entries.none{it.planId==p.id})p.copy(active=false)else p},budgets=next.budgets.map{if(it.id !in previousBudgets.values)it.copy(isActive=false)else it});messages+="停用原未兑现普通计划${count}项及其他原预算；记录、实际账目和债务计划保留。"}
        val accountMap=mutableMapOf<Long,String>();val nameMap=mutableMapOf<String,String>()
        b.accounts.forEach { a->
            val known=previousAccounts[a.id];val old=next.accounts.firstOrNull{it.id==known}?:next.accounts.firstOrNull{it.name==a.name&&it.currencyCode==a.currencyCode}
            if(known!=null&&old!=null&&!options.updateKnown){accountMap[a.id]=old.id;nameMap[a.name]=old.id;messages+="已识别账户“${a.name}”，保留当前余额/设置（更新需明确选择）。"}
            else {
                var name=a.name
                if(next.accounts.any{it.name==name&&it.id!=old?.id}){var i=1;while(next.accounts.any{it.name==name}){name="${a.name}（现金流${i++}）"};messages+="同名异币账户另建“$name”，不改已有账户。"}
                if(old==null){next=RichLedger.saveAccount(next,Account("",0,name,a.initialBalanceCents,a.currencyCode,a.accountType,a.isActive,a.includeInForecast,a.exchangeRateToCnyMicros))}
                else {require(old.currencyCode==a.currencyCode){"来源账户币种已改变，请先核对映射"};next=next.copy(accounts=next.accounts.map{if(it.id==old.id)it.copy(name=name,accountType=a.accountType,active=a.isActive,includeInForecast=a.includeInForecast,exchangeRateToCnyMicros=a.exchangeRateToCnyMicros,archived=false)else it})}
                val id=old?.id?:next.accounts.last().id;accountMap[a.id]=id;nameMap[a.name]=id
                // Current balance is an explicit calibration; initial balance is never substituted for it.
                if(a.currencyCode!="CRYPTO")next=LedgerEngine.correctBalance(next,id,a.currentBalanceCents,today,0,"现金流迁入当前余额")
                messages+="账户“$name”：原开头 ${CurrencyUnits.format(a.initialBalanceCents,a.currencyCode)}，当前校准 ${CurrencyUnits.format(a.currentBalanceCents,a.currencyCode)} ${a.currencyCode}。"
            }
        }
        val budgetMap=mutableMapOf<Long,Long>()
        b.budgets.forEach { old->val known=previousBudgets[old.id];val existing=next.budgets.firstOrNull{it.id==known}
            if(known!=null&&!options.updateKnown){budgetMap[old.id]=known;messages+="来源预算“${old.name}”已识别，保留当前进度。"}
            else {val mapped=old.copy(id=existing?.id?:0,accountName=old.accountName.takeIf{it.isNotBlank()}?.let{n->next.accounts.first{it.id==nameMap.getValue(n)}.name}.orEmpty(),baselineSequence=next.nextSequence-1);val id=existing?.id?:next.nextSequence;next=next.copy(nextSequence=if(existing==null)id+1 else next.nextSequence,budgets=next.budgets.filterNot{it.id==id}+mapped.copy(id=id));budgetMap[old.id]=id;messages+="预算“${old.name}”：迁入剩余 ${Money.format(old.remainingCents)}，旧账不会再次扣除。"}
        }
        val planMap=mutableMapOf<Long,String>()
        b.plans.forEach { old->val known=previousPlans[old.id];val existing=next.plans.firstOrNull{it.id==known}
            if(known!=null&&(!options.updateKnown||(next.entries.any{it.planId==known}||next.settledOccurrences.any{it.planId==known}))){planMap[old.id]=known;messages+="来源计划“${old.name}”已识别${if((next.entries.any{it.planId==known}||next.settledOccurrences.any{it.planId==known}))"且已有实际关联"else ""}，保留，不重置已兑现期次。"}
            else {val seq=existing?.sequence?:next.nextSequence;val p=Plan(existing?.id?:"plan-$seq",seq,old.name,when(old.type){EntryType.INCOME->EntryKind.INCOME;EntryType.DEBT->EntryKind.REPAY;else->EntryKind.EXPENSE},old.amountCents,old.startDateEpochDay,if(old.type==EntryType.INCOME)"现金流收入"else "现金流支出",nameMap.getValue(old.accountName),"从现金流备份迁入",old.currencyCode,CoreRepeatRule.valueOf(old.repeatRule.name),old.endDateEpochDay,old.installmentTotal,old.installmentRemaining,old.installmentTotalAmountCents,old.isActive,postingFromEpochDay=old.balanceAppliedThroughEpochDay?.plus(1),notBeforeEpochDay=maxOf(today,existing?.notBeforeEpochDay?:today));next=next.copy(nextSequence=if(existing==null)seq+1 else next.nextSequence,plans=next.plans.filterNot{it.id==p.id}+p);planMap[old.id]=p.id;messages+="计划“${old.name}”：${old.repeatRule}，剩余${old.installmentRemaining}期；保留原游标，不补记过去期次。"}
        }
        // Adjustment IDs occupy their own legacy namespace, recorded as negative keys in the source map.
        b.adjustments.forEach { a->
            if(a.type==AdjustmentType.BALANCE_CALIBRATION){messages+="校准记录“${a.name}”仅保留来源，不作为未来收支再执行。";return@forEach}
            val key=-a.id;val known=previousPlans[key];val existing=next.plans.firstOrNull{it.id==known}
            if(known!=null&&(!options.updateKnown||(next.entries.any{it.planId==known}||next.settledOccurrences.any{it.planId==known}))){planMap[key]=known;return@forEach}
            val accountId=nameMap.getValue(a.accountName);val currency=next.accounts.first{it.id==accountId}.currencyCode
            require(currency!="CRYPTO"){"旧临时项缺少具体数字币币种，未静默解释金额"}
            val seq=existing?.sequence?:next.nextSequence;val p=Plan(existing?.id?:"plan-$seq",seq,a.name,if(a.isIncome)EntryKind.INCOME else EntryKind.EXPENSE,a.amountCents,a.dateEpochDay,"现金流临时收支",accountId,"从现金流临时项迁入",currency,temporary=true,usesBudget=a.usesBudget,budgetId=if(a.usesBudget)budgetMap.values.singleOrNull()else null,notBeforeEpochDay=if(a.appliedToBalance)null else today)
            next=next.copy(nextSequence=if(existing==null)seq+1 else next.nextSequence,plans=next.plans.filterNot{it.id==p.id}+p);planMap[key]=p.id
            if(a.appliedToBalance){next=LedgerEngine.saveEntry(next,EntryDraft(p.kind,p.amountMinor,p.dueEpochDay,0,p.category,p.accountId,note="迁入已应用临时项：余额已含此项，不再次计入",currencyCode=p.currencyCode,affectsBalance=false,affectsBudget=false,excludeFromStatistics=true),today);val e=next.entries.last();next=next.copy(entries=next.entries.map{if(it.id==e.id)it.copy(planId=p.id,occurrenceEpochDay=p.dueEpochDay)else it})}
            messages+="临时项“${a.name}”：${if(a.appliedToBalance)"已应用，只保留确认标记，不再次执行"else if(a.dateEpochDay<today)"过去未应用，保留原日，不挤到今天"else "未来待发生"}。"
        }
        val crypto=b.accounts.firstOrNull{it.currencyCode=="CRYPTO"};if(crypto!=null){val id=accountMap.getValue(crypto.id);if(previousAccounts[crypto.id]==null||options.updateKnown)next=RichLedger.saveHoldings(next,b.holdings,id)}
        val hidden=b.settings.hiddenAccountIdsCsv.split(',').mapNotNull{it.trim().toLongOrNull()}.mapNotNull{accountMap[it]}.mapNotNull{id->next.accounts.firstOrNull{it.id==id}?.sequence}.toMutableSet()
        b.accounts.filter{(!b.settings.showPaypalOnAccounts&&it.accountType==AccountType.PAYPAL)||(!b.settings.showAlipayOnAccounts&&it.accountType==AccountType.ALIPAY)||(!b.settings.showCryptoOnAccounts&&it.accountType==AccountType.CRYPTO)}.forEach{a->accountMap[a.id]?.let{id->next.accounts.firstOrNull{it.id==id}?.sequence?.let(hidden::add)}}
        val home=b.settings.hiddenHomeCurrenciesCsv.split(',').filter{it.isNotBlank()}.toMutableSet();if(!b.settings.showUsdOnHome)home+="USD";if(!b.settings.showCryptoOnHome)home+="CRYPTO"
        next=next.copy(settings=next.settings.copy(cashflow=b.settings.copy(showUsdValuation=next.settings.cashflow.showUsdValuation,showCryptoValuation=next.settings.cashflow.showCryptoValuation,cryptoPriceMicros=next.settings.cashflow.cryptoPriceMicros,hiddenAccountIdsCsv=hidden.sorted().joinToString(","),hiddenHomeCurrenciesCsv=home.sorted().joinToString(","),automaticPosting=false,automaticPostingFromEpochDay=null,onboardingCompleted=true,seeded=true)))
        if(options.adoptOverall)next=LedgerEngine.correctBalance(next,null,options.overallMinor?:suggested,today,0,"现金流迁入：独立整体余额校准")
        messages+="人民币建议整体余额：${b.accounts.count{it.isActive&&it.includeInForecast&&it.currencyCode=="CNY"}}个参与预测的人民币账户余额合计 ${Money.format(suggested)}；${if(options.adoptOverall)"确认后校准为 ${Money.format(options.overallMinor?:suggested)}"else "本次保留现有独立整体余额"}；美元/持仓不折入。"
        messages+="采用文件中的显示和预测设置，保留现有每月账期。"
        messages+="保留现有${state.entries.size}条实际账目、${state.categories.size}个分类、${state.debts.size}笔债务；自动过账关闭。来源原字段随全量备份保留。"
        next=next.copy(cashflowSources=next.cashflowSources+CashflowSource(preview.sourceKey,today,accountMap,planMap,budgetMap,preview.originalJson))
        return CashflowMigrationResult(LedgerEngine.validate(next),messages,suggested)
    }
}
