package com.qiye.ledgerflow.core

/** A neutral, parsed row; platform readers provide the stable content/multiplicity key. */
data class ImportRecord(val key:String,val sourceRow:Int,val kind:EntryKind,val amountMinor:Long,
    val epochDay:Long,val minuteOfDay:Int,val second:Int,val category:String,val subcategory:String,
    val note:String,val tags:String,val accountName:String?,val targetAccountName:String?,
    val feeMinor:Long=0,val refundMinor:Long=0,val refundEpochDay:Long?=null,val reimbursementMinor:Long=0,
    val reimbursementEpochDay:Long?=null,val excludeBudget:Boolean=false,val excludeStatistics:Boolean=false,
    val sourceDetails:String="",val warnings:List<String> = emptyList(),val counterparty:String="",val debtGroupKey:String?=null)
data class ImportCommit(val state:LedgerState,val insertedRows:Int,val duplicates:Int)

object LedgerImport {
    fun saveCategorizedEntry(state:LedgerState,draft:EntryDraft,today:Long,id:String?=null):LedgerState {
        if(!draft.kind.isOrdinary() || draft.categoryId!=null) return LedgerEngine.saveEntry(state,draft,today,id)
        var next=state;val kind=RichLedger.categoryKind(draft.kind)
        var parent=next.categories.firstOrNull { it.kind==kind && it.parentId==null && it.name==draft.category }
        if(parent==null){ next=RichLedger.saveCategory(next,draft.category,kind);parent=next.categories.last() }
        var category=parent
        if(draft.subcategory.isNotBlank()){
            category=next.categories.firstOrNull { it.parentId==parent.id && it.name==draft.subcategory }
            if(category==null){next=RichLedger.saveCategory(next,draft.subcategory,kind,parent.id);category=next.categories.last()}
        }
        return LedgerEngine.saveEntry(next,draft.copy(categoryId=category.id),today,id)
    }
    fun counterparty(e:Entry)=e.debtCounterparty.ifBlank{Regex("(?m)^债务人：([^\n]*)").find(e.sourceDetails)?.groupValues?.get(1).orEmpty()}
    fun pendingSettlements(state:LedgerState)=DebtGroups.all(state).flatMap{it.unmatched}.map{it.entry}.distinctBy{it.id}
    fun uncertain(state:LedgerState,d:Debt)=DebtGroups.forDebt(state,d.id)?.unmatched?.isNotEmpty()==true
    fun linkSettlement(state:LedgerState,entryId:String,debtId:String):LedgerState {
        val e=state.entries.firstOrNull{it.id==entryId}?:error("还款记录不存在");val d=state.debts.firstOrNull{it.id==debtId}?:error("本金不存在")
        if(e.debtId==debtId)return state
        require(e in pendingSettlements(state)){"仅可关联未分配的导入还款"}
        require(e.currencyCode==d.currencyCode&&e.kind==(if(d.kind==DebtKind.BORROWED)EntryKind.REPAY else EntryKind.COLLECT)){"方向或币种不匹配"}
        require(counterparty(e).isBlank()||counterparty(e)==d.person){"对方不匹配"}
        require(e.epochDay>=d.openedEpochDay&&e.amountMinor<=RichLedger.outstanding(state,d.id)){"日期或金额不匹配，未分配"}
        return LedgerEngine.validate(state.copy(entries=state.entries.map{if(it.id==e.id)it.copy(debtId=d.id,settlementId="import-${it.importKey}")else it}))
    }
    fun commit(state:LedgerState,rows:List<ImportRecord>,today:Long,applyBalances:Boolean=false,accountMapping:Map<String,String?> = emptyMap()):ImportCommit {
        var next=state;var inserted=0;var duplicates=0
        val seen=state.importedKeys.toMutableSet()
        for(row in rows){
            if(!seen.add(row.key)){duplicates++;continue}
            fun account(name:String?):String? {
                if(name==null)return null
                if(applyBalances){
                    require(accountMapping.containsKey(name)){"请为“$name”选择当前资金账户"}
                    return accountMapping[name]?.also{id->require(next.accounts.any{it.id==id&&AccountManagement.selectable(it)&&it.currencyCode=="CNY"}){"所选账户不可用"}}
                }
                next.accounts.firstOrNull{it.name==name&&it.currencyCode=="CNY"}?.let{return it.id}
                next=RichLedger.saveAccount(next,Account("",0,name,0,active=false,includeInForecast=false,archived=true));return next.accounts.last().id
            }
            val from=account(row.accountName);val to=account(row.targetAccountName)
            val currency=from?.let { id->next.accounts.first { it.id==id }.currencyCode }?:"CNY"
            require(currency=="CNY") { "表格没有币种列，不能将人民币导出金额静默当作外币账户金额；请先核对账户映射" }
            val draft=EntryDraft(row.kind,row.amountMinor,row.epochDay,row.minuteOfDay,row.category,from,to,row.note,row.second,
                subcategory=row.subcategory,tags=row.tags,excludeFromBudget=row.excludeBudget,excludeFromStatistics=row.excludeStatistics,
                affectsBalance=applyBalances,affectsBudget=applyBalances,sourceDetails=row.sourceDetails)
            next=saveCategorizedEntry(next,draft,today)
            val original=next.entries.last()
            next=next.copy(entries=next.entries.map { if(it.id==original.id)it.copy(importKey=row.key,debtCounterparty=row.counterparty) else it })
            if(row.feeMinor>0) next=saveCategorizedEntry(next,EntryDraft(EntryKind.EXPENSE,row.feeMinor,row.epochDay,row.minuteOfDay,"转账手续费",from,note="导入手续费 · ${row.note}",second=row.second,affectsBalance=applyBalances,affectsBudget=applyBalances,excludeFromBudget=row.excludeBudget,excludeFromStatistics=row.excludeStatistics,sourceDetails="导入关联 ${row.key}"),today)
            if(row.refundMinor>0) next=saveCategorizedEntry(next,EntryDraft(EntryKind.REFUND,row.refundMinor,row.refundEpochDay?:error("缺少退款日期"),0,row.category,from,note="退款 · ${row.note}",subcategory=row.subcategory,tags=row.tags,affectsBalance=applyBalances,affectsBudget=applyBalances,excludeFromBudget=row.excludeBudget,excludeFromStatistics=row.excludeStatistics,refundOfId=original.id,sourceDetails="导入关联 ${row.key}"),today)
            if(row.reimbursementMinor>0) next=saveCategorizedEntry(next,EntryDraft(EntryKind.REFUND,row.reimbursementMinor,row.reimbursementEpochDay?:error("缺少报销日期"),0,row.category,from,note="报销 · ${row.note}",subcategory=row.subcategory,tags=row.tags,affectsBalance=applyBalances,affectsBudget=applyBalances,excludeFromBudget=row.excludeBudget,excludeFromStatistics=row.excludeStatistics,refundOfId=original.id,sourceDetails="导入关联 ${row.key}"),today)
            inserted++
        }
        // Relate only explicitly previewed unambiguous principal groups. Interest/due dates were not supplied.
        rows.filter{it.debtGroupKey!=null}.groupBy{it.debtGroupKey!!}.forEach { (key,group)->
            val opening=if(key.startsWith("debt:"))next.entries.firstOrNull{it.debtId==key.removePrefix("debt:")&&it.kind in listOf(EntryKind.BORROW,EntryKind.LEND)}?:error("本金关系不存在")else next.entries.firstOrNull{it.importKey==key} ?: error("债务原始本金缺失，未建立猜测关系")
            require(opening.kind in listOf(EntryKind.BORROW,EntryKind.LEND))
            val relatedKeys=group.map{it.key}.toSet()+key
            val existing=opening.debtId?.let{id->next.debts.first{it.id==id}}
            val seq=next.nextSequence;val id=existing?.id?:"debt-$seq"
            val debt=existing?:Debt(id,seq,if(opening.kind==EntryKind.BORROW)DebtKind.BORROWED else DebtKind.LENT,
                group.first().counterparty.ifBlank{"未提供对方"},opening.amountMinor,opening.epochDay,opening.accountId,"CNY",note="历史本金配对；来源未提供利息及到期信息",interestProvided=false)
            next=next.copy(nextSequence=if(existing==null)seq+1 else next.nextSequence,debts=if(existing==null)next.debts+debt else next.debts,
                entries=next.entries.map{e->if(e.importKey in relatedKeys)e.copy(debtId=id,settlementId=if(e.kind in listOf(EntryKind.REPAY,EntryKind.COLLECT))"import-${e.importKey}"else e.settlementId)else e})
        }
        next=LedgerEngine.validate(next.copy(importedKeys=seen.toList()))
        return ImportCommit(next,inserted,duplicates)
    }
}
