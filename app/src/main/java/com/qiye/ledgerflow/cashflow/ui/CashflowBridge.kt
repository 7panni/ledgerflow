package com.qiye.ledgerflow.cashflow.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.PaddingValues
import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.cashflow.*
import com.qiye.ledgerflow.cashflow.data.*
import com.qiye.ledgerflow.ui.LedgerViewModel
import com.qiye.ledgerflow.ui.LocalClock
import java.time.LocalDate

internal val LocalCashflowSaving=staticCompositionLocalOf { false }
data class TransferUndo(val entryId:String)
data class CashFlowUiState(val accounts:List<AccountEntity>,val plans:List<PlannedTransactionEntity>,val budgets:List<BudgetEntity>,val adjustments:List<AdjustmentEntity>,val cryptoHoldings:List<CryptoHoldingEntity>,val settings:AppSettingsEntity,val horizonDays:Int,val summary:CashFlowSummary,val nextDates:Map<Long,LocalDate?> = emptyMap(),val remainingPeriods:Map<Long,Int> = emptyMap(),val remainingAmounts:Map<Long,Long> = emptyMap(),val confirmedPeriods:Map<Long,Int> = emptyMap(),val message:String?=null,val pendingTransferUndo:TransferUndo?=null,val isReady:Boolean=true)

class CashFlowViewModel(val owner:LedgerViewModel,val openOccurrences:(Long)->Unit={}) {
    private var undoEntryId:String?=null
    fun state(s:LedgerState):CashFlowUiState {
        val balances=LedgerEngine.balances(s);val today=LocalClock.today();val horizon=s.settings.cashflow.defaultHorizonDays
        val f=LedgerForecast.forecast(s,today,horizon)
        val accounts=s.accounts.filter { it.active&&!it.archived }.map { AccountEntity(it.sequence,it.name,it.openingMinor,balances.accountsMinor.getValue(it.id),it.currencyCode,it.exchangeRateToCnyMicros,it.includeInForecast,it.accountType,it.active) }
        val plans=s.plans.filterNot { it.temporary }.map { p->PlannedTransactionEntity(p.sequence,p.title,if(p.kind.isOutflow())EntryType.EXPENSE else EntryType.INCOME,p.amountMinor,p.currencyCode,0,p.accountId?.let { id->s.accounts.first { it.id==id }.name }?:"无账户",startDateEpochDay=p.dueEpochDay,repeatRule=RepeatRule.valueOf(p.repeatRule.name),endDateEpochDay=p.endEpochDay,installmentTotal=p.installmentTotal,installmentRemaining=p.installmentRemaining,installmentTotalAmountCents=p.installmentTotalAmountMinor,balanceAppliedThroughEpochDay=p.postingFromEpochDay?.minus(1),isActive=p.active,usesBudget=p.usesBudget,budgetId=p.budgetId) }
        val nextDates=s.plans.associate { p->p.sequence to RichLedger.occurrences(s,p,today+366,maxOf(p.dueEpochDay,today)).firstOrNull { !it.confirmed }?.dueEpochDay?.let(LocalDate::ofEpochDay) }
        val adjustments=s.plans.filter { it.temporary }.map { p->AdjustmentEntity(p.sequence,p.title,AdjustmentType.TEMPORARY,p.amountMinor,p.accountId?.let { id->s.accounts.first { it.id==id }.name }?:"无账户",p.dueEpochDay,!p.kind.isOutflow(),p.usesBudget,(s.entries.any { it.planId==p.id }||s.settledOccurrences.any{it.planId==p.id}),p.budgetId) }
        val summary=CashFlowSummary(f.currentCnyMinor,f.currentUsdMinor,f.endCnyMinor,f.endUsdMinor,minOf(f.currentCnyMinor,f.lowestCnyMinor),LocalDate.ofEpochDay(if(f.currentCnyMinor<=f.lowestCnyMinor)today else f.lowestEpochDay?:today),maxOf(f.currentCnyMinor,f.highestCnyMinor),LocalDate.ofEpochDay(if(f.currentCnyMinor>=f.highestCnyMinor)today else f.highestEpochDay?:today),if(f.currentCnyMinor<0)LocalDate.ofEpochDay(today) else f.firstShortageEpochDay?.let(LocalDate::ofEpochDay),f.budgetRemainingMinor,0,f.days.map { d->CashFlowDay(LocalDate.ofEpochDay(d.epochDay),d.cnyBalanceMinor,d.usdBalanceMinor,d.accountBalancesMinor,d.events.map { ForecastEvent(it.name,it.amountMinor,it.currencyCode,it.accountName,it.kind) }) })
        return CashFlowUiState(accounts,plans,s.budgets.map { it.copy(remainingCents=BudgetRules.remaining(s,it,today)) },adjustments,RichLedger.cryptoBalances(s).map { CryptoHoldingEntity(it.key,it.value) },s.settings.cashflow.copy(budgetMonthStartDay=s.settings.monthStartDay),horizon,summary,nextDates,
            remainingPeriods=s.plans.associate{p->p.sequence to if(p.installmentTotal>1)(p.installmentRemaining-(s.entries.count{it.planId==p.id}+s.settledOccurrences.count{it.planId==p.id})).coerceAtLeast(0) else 0},
            remainingAmounts=s.plans.filter{it.installmentTotal>1}.associate{p->p.sequence to RichLedger.occurrences(s,p,minOf(LedgerCalendar.lastDay,maxOf(today,p.dueEpochDay)+p.installmentTotal*32L+366)).filter{!it.confirmed}.fold(0L){total,o->Money.add(total,o.amountMinor)}},
            confirmedPeriods=s.plans.associate{p->p.sequence to (s.entries.count{it.planId==p.id}+s.settledOccurrences.count{it.planId==p.id})})
    }
    fun homeBase(s:LedgerState):CashFlowUiState {
        val current=LedgerEngine.balances(s).overallMinor
        val summary=CashFlowSummary(current,0,current,0,current,null,current,null,null,0,0,emptyList())
        return CashFlowUiState(emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),s.settings.cashflow,s.settings.cashflow.defaultHorizonDays,summary,isReady=false)
    }
    private fun mutate(message:String,onSuccess:()->Unit={},fn:(LedgerState)->LedgerState){val s=owner.state?:error("账本尚未读取");if(owner.busy)return;fn(s);owner.change(message,onSuccess,fn)}
    fun setHorizon(days:Int)=mutate("预测区间已更新"){it.copy(settings=it.settings.copy(cashflow=it.settings.cashflow.copy(defaultHorizonDays=days)))}
    fun dismissMessage()=owner.clearNotice()
    fun saveAccount(a:AccountEntity,onSuccess:()->Unit={})=mutate("账户已保存",onSuccess){s->val old=s.accounts.firstOrNull { it.sequence==a.id };val next=RichLedger.saveAccount(s,Account(old?.id?:"",old?.sequence?:0,a.name,a.initialBalanceCents,a.currencyCode,a.accountType,a.isActive,a.includeInForecast,a.exchangeRateToCnyMicros));if(old!=null&&a.currencyCode!="CRYPTO")LedgerEngine.calibrateAccount(next,old.id,a.currentBalanceCents,LocalClock.today(),LocalClock.minute(),"编辑账户余额")else next}
    fun renameAccount(a:AccountEntity,name:String)=safe { mutate("账户名称已更新"){s->val old=s.accounts.first { it.sequence==a.id };RichLedger.saveAccount(s,old.copy(name=name))} }
    fun calibrate(a:AccountEntity,amount:Long)=safe { mutate("账户余额已校准"){s->val id=s.accounts.first { it.sequence==a.id }.id;LedgerEngine.calibrateAccount(s,id,amount,LocalClock.today(),LocalClock.minute(),"手动校准 ${a.name}")} }
    fun accountPlanCount(id:Long)=owner.state?.let{s->val key=s.accounts.firstOrNull{it.sequence==id}?.id;s.plans.count{it.accountId==key}}?:0
    fun historicalBankIds()=owner.state?.let{AccountManagement.historicalBanks(it).map{a->a.sequence}.toSet()}.orEmpty()
    fun cleanHistoricalBanks(onSuccess:()->Unit)=owner.migrate("历史银行账户已移除",onSuccess){AccountManagement.cleanHistoricalBanks(it,LocalClock.today())}
    fun removeAccount(id:Long,target:Long?,onSuccess:()->Unit)=owner.migrate("账户已移除，历史记录保留",onSuccess){s->AccountManagement.retire(s,s.accounts.first{it.sequence==id}.id,LocalClock.today(),target?.let{n->s.accounts.first{it.sequence==n}.id})}
    fun saveValuations(usd:String,prices:Map<String,String>,showUsd:Boolean,showCrypto:Boolean,onSuccess:()->Unit,holdingAmounts:Map<String,String>?=null,accountId:Long?=null)=safe {
        fun parse(text:String):Long{require(Regex("[0-9]{1,10}([.][0-9]{1,6})?").matches(text.trim())){"汇率须为非负数字，最多六位小数"};return java.math.BigDecimal(text.trim()).movePointRight(6).longValueExact()}
        mutate("估值设置已保存",onSuccess){s->val stored=s.settings.cashflow.cryptoPriceMicros
            val values=prices.mapNotNull{(symbol,text)->if(text.isBlank()){require(symbol !in stored){"$symbol 汇率不能为空；填0表示估值为0"};null}else symbol to parse(text)}.toMap()
            val withHoldings=if(holdingAmounts==null)s else RichLedger.saveHoldings(s,holdingAmounts.map{(symbol,text)->CryptoHoldingEntity(symbol,CurrencyUnits.parse(text,symbol))},s.accounts.firstOrNull{it.sequence==accountId}?.id)
            AssetValuation.configure(withHoldings,parse(usd),values,showUsd,showCrypto)
        }
    }
    fun homeAssets()=owner.state?.let{HomeAssets.calculate(it)}
    fun usdValuation()=owner.state?.let{AssetValuation.usd(it)}
    fun cryptoValuation()=owner.state?.let{AssetValuation.crypto(it)}
    fun savePlan(p:PlannedTransactionEntity,onSuccess:()->Unit={})=mutate("计划已保存",onSuccess){s->val old=s.plans.firstOrNull { it.sequence==p.id };val account=s.accounts.firstOrNull { it.name==p.accountName };val currency=if(account?.currencyCode=="CRYPTO")p.currencyCode else account?.currencyCode?:"CNY"
        LedgerEngine.savePlan(s,PlanDraft(p.name,if(old!=null&&!old.kind.isOrdinary())old.kind else if(p.type==EntryType.INCOME)EntryKind.INCOME else EntryKind.EXPENSE,p.amountCents,p.startDateEpochDay,old?.category?:if(p.type==EntryType.INCOME)"其他收入" else "其他支出",account?.id,old?.note.orEmpty(),currency,com.qiyue.cashflow.shared.CoreRepeatRule.valueOf(p.repeatRule.name),p.endDateEpochDay,p.installmentTotal,p.installmentRemaining,p.installmentTotalAmountCents,p.isActive,usesBudget=p.usesBudget,categoryId=old?.categoryId?.takeIf{old.kind==(if(p.type==EntryType.INCOME)EntryKind.INCOME else EntryKind.EXPENSE)},debtId=old?.debtId,isInterest=old?.isInterest?:false,budgetId=p.budgetId,postingFromEpochDay=old?.postingFromEpochDay),old?.id,LocalClock.today()) }
    fun saveAdjustment(a:AdjustmentEntity,onSuccess:()->Unit={})=mutate("临时收支已保存",onSuccess){s->val old=s.plans.firstOrNull { it.sequence==a.id };val account=s.accounts.firstOrNull { it.name==a.accountName };LedgerEngine.savePlan(s,PlanDraft(a.name,if(a.isIncome)EntryKind.INCOME else EntryKind.EXPENSE,a.amountCents,a.dateEpochDay,old?.category?:if(a.isIncome)"其他收入" else "其他支出",account?.id,currencyCode=account?.currencyCode?:"CNY",temporary=true,usesBudget=a.usesBudget,budgetId=a.budgetId),old?.id)}
    fun saveBudget(b:BudgetEntity,onSuccess:()->Unit={})=mutate("预算已保存",onSuccess){s->val old=s.budgets.firstOrNull { it.id==b.id };val recalibrate=old==null||b.remainingCents!=BudgetRules.remaining(s,old,LocalClock.today());RichLedger.saveBudget(s,b.copy(monthStartDay=if(b.period==BudgetPeriod.MONTHLY)(if(old!=null&&!recalibrate)old.monthStartDay else s.settings.monthStartDay)else null),recalibrate,LocalClock.today())}
    fun toggleBudget(id:Long)=safe { mutate("预算状态已更新"){s->val b=s.budgets.first { it.id==id };RichLedger.saveBudget(s,b.copy(isActive=!b.isActive),false)} }
    fun deleteBudget(id:Long)=safe { mutate("预算已删除"){RichLedger.deleteBudget(it,id)} }
    fun deletePlan(id:Long)=safe { mutate("计划已删除"){s->LedgerEngine.deletePlan(s,s.plans.first { it.sequence==id }.id)} }
    fun deleteAdjustment(id:Long)=deletePlan(id)
    fun holdingsForAccount(id:Long?):List<CryptoHoldingEntity>{val s=owner.state?:return emptyList();val account=s.accounts.firstOrNull { it.sequence==id }?.id?:s.accounts.firstOrNull { it.currencyCode=="CRYPTO" }?.id;return listOf("USDT","BTC","LTC").map { CryptoHoldingEntity(it,RichLedger.cryptoAccountBalances(s,account,it),accountId=account) }}
    fun saveCryptoHoldings(items:List<CryptoHoldingEntity>,id:Long?=null,onSuccess:()->Unit={})=mutate("持仓已保存",onSuccess){s->RichLedger.saveHoldings(s,items,s.accounts.firstOrNull { it.sequence==id }?.id)}
    fun transferBalance(fromId:Long,toId:Long,amount:Long)=transfer(fromId,toId,amount,null)
    fun transfer(fromId:Long,toId:Long,amount:Long,symbol:String?,onSuccess:()->Unit={})=mutate("转账已保存，可在账目中查看或撤销",onSuccess){s->val from=s.accounts.first { it.sequence==fromId };val to=s.accounts.first { it.sequence==toId };val next=LedgerEngine.saveEntry(s,EntryDraft(EntryKind.TRANSFER,amount,LocalClock.today(),LocalClock.minute(),"内部转账",from.id,to.id,currencyCode=symbol?:from.currencyCode,affectsBudget=false),LocalClock.today());undoEntryId=next.entries.last().id;next}
    fun lastTransfer():Entry?=owner.state?.entries?.lastOrNull{it.kind==EntryKind.TRANSFER}
    fun undoLastTransfer()=safe { val id=lastTransfer()?.id?:return@safe;mutate("转账已撤销"){LedgerEngine.deleteEntry(it,id)};undoEntryId=null }
    fun setFinanceTips(show:Boolean)=safe {mutate("提示语设置已保存"){it.copy(settings=it.settings.copy(cashflow=it.settings.cashflow.copy(showFinanceTips=show)))}}
    fun updateSettings(days:Int,hiddenCurrencies:Set<String>,hiddenAccounts:Set<Long>,tips:Boolean,daily:Boolean,usdRate:String,automatic:Boolean)=safe {
        val rate=java.math.BigDecimal(usdRate).movePointRight(6).longValueExact()
        mutate("设置已保存"){s->val old=s.settings.cashflow;s.copy(settings=s.settings.copy(cashflow=old.copy(defaultHorizonDays=days,budgetMonthStartDay=s.settings.monthStartDay,hiddenHomeCurrenciesCsv=hiddenCurrencies.sorted().joinToString(","),hiddenAccountIdsCsv=hiddenAccounts.sorted().joinToString(","),showUsdOnHome="USD" !in hiddenCurrencies,showCryptoOnHome="CRYPTO" !in hiddenCurrencies,showFinanceTips=tips,financeTipChangesDaily=daily,usdRateMicros=rate,automaticPosting=automatic,automaticPostingFromEpochDay=if(automatic&&!old.automaticPosting)LocalClock.today() else old.automaticPostingFromEpochDay))) }
    }
    private fun safe(action:()->Unit){runCatching(action).onFailure{owner.inform(it.message?:"操作失败，原数据未变")}}
}

@Composable fun FullCashflowPage(section:Int,state:LedgerState,padding:PaddingValues,owner:LedgerViewModel,onOccurrence:(Long)->Unit){
    val open by rememberUpdatedState(onOccurrence)
    val vm=remember(owner){CashFlowViewModel(owner){open(it)}}
    val built=remember(state){runCatching { vm.state(state) }}
    CompositionLocalProvider(LocalCashflowSaving provides owner.busy){built.fold(onSuccess={ui->when(section){0->PlanPage(ui,padding,vm);1->AccountPage(ui,padding,vm);else->SettingsPage(ui,padding,vm)}},onFailure={androidx.compose.material3.Text("预测暂不可用：${it.message}")})}
}
