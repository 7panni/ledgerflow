package com.qiye.ledgerflow.core

data class DebtRemainder(val entry:Entry,val amountMinor:Long,val reason:String)
data class DebtAllocation(val debtId:String,val entryId:String,val amountMinor:Long)
data class DebtPerson(val key:String,val name:String,val groups:List<DebtGroup>)
data class DebtProjectEntry(val entry:Entry,val allocatedMinor:Long)
data class DebtGroup(val key:String,val person:String,val currencyCode:String,val kind:DebtKind,val loans:List<Debt>,val entries:List<Entry>,val remainingByDebt:Map<String,Long>,val unmatched:List<DebtRemainder>,val allocations:List<DebtAllocation> = emptyList()){
    val principalMinor:Long get()=loans.fold(0L){n,d->Money.add(n,d.principalMinor)}
    val remainingMinor:Long get()=remainingByDebt.values.fold(0L,Money::add)
    val repaidMinor:Long get()=Money.add(principalMinor,-remainingMinor)
}
/** Read-only FIFO allocation. Raw loans, payments, account balances and statistics never change. */
object DebtGroups {
    private val moment=compareBy<Entry>{it.epochDay}.thenBy{it.minuteOfDay}.thenBy{it.second}
    private val order=moment.thenBy{it.sequence}
    fun all(state:LedgerState):List<DebtGroup>{
        data class Bucket(val key:String,val person:String,val currency:String,val kind:DebtKind,val loans:MutableList<Debt> = mutableListOf(),val payments:MutableList<Entry> = mutableListOf())
        val buckets=linkedMapOf<String,Bucket>();val debtKeys=mutableMapOf<String,String>()
        fun key(person:String,code:String,kind:DebtKind)="${kind.name}:$code:$person"
        state.debts.forEach{d->
            val opening=state.entries.firstOrNull{it.debtId==d.id&&it.kind in listOf(EntryKind.BORROW,EntryKind.LEND)}
            val unknown=opening?.importKey!=null&&LedgerImport.counterparty(opening).isBlank()
            val k=if(unknown)"unknown-loan:${d.id}"else key(d.person.trim(),d.currencyCode,d.kind)
            debtKeys[d.id]=k;buckets.getOrPut(k){Bucket(k,if(unknown)"对方未填写"else d.person,d.currencyCode,d.kind)}.loans+=d
        }
        state.entries.filter{it.kind in listOf(EntryKind.REPAY,EntryKind.COLLECT)&&!it.isInterest}.forEach{e->
            val kind=if(e.kind==EntryKind.REPAY)DebtKind.BORROWED else DebtKind.LENT
            val person=LedgerImport.counterparty(e).trim()
            val linked=e.debtId?.let{debtKeys[it]}
            val k=linked?:if(person.isBlank())"unknown-payment:${e.id}"else key(person,e.currencyCode,kind)
            buckets.getOrPut(k){Bucket(k,person.ifBlank{"对方未填写"},e.currencyCode,kind)}.payments+=e
        }
        return buckets.values.map{b->
            val ids=b.loans.map{it.id}.toSet()
            val openings=state.entries.filter{it.debtId in ids&&it.kind in listOf(EntryKind.BORROW,EntryKind.LEND)}.sortedWith(order)
            val remaining=b.loans.associate{it.id to it.principalMinor}.toMutableMap();val unmatched=mutableListOf<DebtRemainder>();val allocations=mutableListOf<DebtAllocation>()
            b.payments.sortedWith(order).forEach{payment->
                var left=payment.amountMinor
                val eligible=openings.filter{moment.compare(it,payment)<=0}
                eligible.forEach{opening->val id=opening.debtId!!;val used=minOf(left,remaining.getValue(id));remaining[id]=remaining.getValue(id)-used;left-=used;if(used>0)allocations+=DebtAllocation(id,payment.id,used)}
                if(left>0)unmatched+=DebtRemainder(payment,left,if(openings.isEmpty())"没有对应本金"else if(eligible.isEmpty())"早于借款日期"else "超过当时可归还本金")
            }
            val records=(state.entries.filter{it.debtId in ids}+b.payments).distinctBy{it.id}.sortedWith(order)
            DebtGroup(b.key,b.person,b.currency,b.kind,b.loans.toList(),records,remaining,unmatched,allocations)
        }
    }
    fun forDebt(state:LedgerState,id:String)=all(state).firstOrNull{g->g.loans.any{it.id==id}}
    fun remaining(state:LedgerState,id:String)=forDebt(state,id)?.remainingByDebt?.get(id)?:error("借款不存在")
    fun projectEntries(state:LedgerState,id:String):List<DebtProjectEntry>{
        val g=forDebt(state,id)?:return emptyList();val portions=g.allocations.filter{it.debtId==id}.associate{it.entryId to it.amountMinor}
        return g.entries.filter{it.debtId==id&&it.kind !in listOf(EntryKind.REPAY,EntryKind.COLLECT)||it.isInterest&&it.debtId==id||it.id in portions}.map{DebtProjectEntry(it,portions[it.id]?:0)}
    }
    fun canArchiveProject(state:LedgerState,id:String):Boolean {
        val g=forDebt(state,id)?:return false
        return g.remainingByDebt[id]==0L&&g.unmatched.isEmpty()&&RichLedger.interestOutstanding(state,id)==0L
    }
    // Display checkpoint only; never changes FIFO allocation, money or future plans.
    private fun projectStamp(state:LedgerState,id:String):String {
        val d=state.debts.first{it.id==id};var h=1125899906842597L;var h2=5381L
        fun add(text:String){text.forEach{h=h*31+it.code;h2=(h2*33)xor it.code.toLong()};h=h*31+1;h2=h2*33+1}
        add(d.copy(id="",sequence=0,accountId=null).toString())
        projectEntries(state,id).forEach{(e,part)->add(e.copy(id="",sequence=0,accountId=null,targetAccountId=null,planId=null,categoryId=null,debtId=null,refundOfId=null,importKey=null,budgetId=null,settlementId=null,fundsDetached=false,planSnapshot="",budgetSnapshot="").toString());add(state.accounts.firstOrNull{it.id==e.accountId}?.name.orEmpty());add(part.toString())}
        return "$h:$h2"
    }
    fun isProjectArchived(state:LedgerState,id:String):Boolean {
        if(!canArchiveProject(state,id))return false
        val g=forDebt(state,id)?:return false
        return state.debtArchives.any{a->id in a.debtIds&&if(a.contentStamp.isNotEmpty())a.debtIds==listOf(id)&&a.entryIds.toSet()==projectEntries(state,id).map{it.entry.id}.toSet()&&a.contentStamp==projectStamp(state,id)else a.debtIds.toSet()==g.loans.map{it.id}.toSet()&&a.entryIds.toSet()==g.entries.map{it.id}.toSet()&&g.remainingMinor==0L}
    }
    private fun checkpoint(state:LedgerState,id:String)=DebtArchive(listOf(id),projectEntries(state,id).map{it.entry.id},projectStamp(state,id))
    private fun normalized(state:LedgerState)=state.debts.filter{isProjectArchived(state,it.id)}.map{checkpoint(state,it.id)}
    fun archiveProject(state:LedgerState,id:String):LedgerState {
        require(canArchiveProject(state,id)){"此项目仍有本金、利息或异常金额，不能归档"}
        return LedgerEngine.validate(state.copy(debtArchives=normalized(state).filterNot{id in it.debtIds}+checkpoint(state,id)))
    }
    fun restoreProject(state:LedgerState,id:String)=LedgerEngine.validate(state.copy(debtArchives=normalized(state).filterNot{id in it.debtIds}))
    fun canArchive(state:LedgerState,g:DebtGroup)=g.loans.isNotEmpty()&&g.loans.all{canArchiveProject(state,it.id)}
    fun isArchived(state:LedgerState,g:DebtGroup)=g.loans.isNotEmpty()&&g.loans.all{isProjectArchived(state,it.id)}
    fun archive(state:LedgerState,key:String):LedgerState {
        val g=all(state).firstOrNull{it.key==key}?:error("借贷分组不存在");require(canArchive(state,g)){"仍有本金、利息或异常金额，不能归档"}
        var next=state;g.loans.forEach{next=archiveProject(next,it.id)};return next
    }
    fun archiveAll(state:LedgerState):LedgerState {var next=state;state.debts.filter{canArchiveProject(state,it.id)}.forEach{next=archiveProject(next,it.id)};return next}
    fun restoreArchive(state:LedgerState,key:String):LedgerState {
        var next=state;all(state).firstOrNull{it.key==key}?.loans?.forEach{next=restoreProject(next,it.id)};return next
    }
    fun people(state:LedgerState):List<DebtPerson> = all(state).groupBy{if(it.key.startsWith("unknown-"))it.key else "person:${it.person.trim()}"}.map{(key,groups)->DebtPerson(key,groups.first().person,groups)}
    fun canArchivePerson(state:LedgerState,p:DebtPerson)=p.groups.isNotEmpty()&&p.groups.all{canArchive(state,it)}
    fun isPersonArchived(state:LedgerState,p:DebtPerson)=canArchivePerson(state,p)&&p.groups.all{isArchived(state,it)}
    fun archivePerson(state:LedgerState,key:String):LedgerState {
        val p=people(state).firstOrNull{it.key==key}?:error("借贷人不存在")
        require(canArchivePerson(state,p)){"此人仍有本金、利息或异常金额，不能归档"}
        var next=state;p.groups.flatMap{it.loans}.forEach{next=archiveProject(next,it.id)};return next
    }
    fun restorePerson(state:LedgerState,key:String):LedgerState {
        val p=people(state).firstOrNull{it.key==key}?:error("借贷人不存在")
        var next=state;p.groups.flatMap{it.loans}.forEach{next=restoreProject(next,it.id)};return next
    }
    fun repay(state:LedgerState,key:String,amount:Long,day:Long,minute:Int,accountId:String?,note:String,today:Long,request:String):LedgerState {
        if(state.entries.any{it.settlementId==request})return state
        val group=all(state).firstOrNull{it.key==key}?:error("借贷分组不存在")
        require(!key.startsWith("unknown-")){"请先填写对方姓名"}
        require(amount in 1..group.remainingMinor){"本金须大于0且不超过未结金额"}
        var next=LedgerEngine.saveEntry(state,EntryDraft(if(group.kind==DebtKind.BORROWED)EntryKind.REPAY else EntryKind.COLLECT,amount,day,minute,"借贷本金",accountId,note=note,currencyCode=group.currencyCode,affectsBudget=false,settlementId=request),today)
        val id=next.entries.last().id;next=next.copy(entries=next.entries.map{if(it.id==id)it.copy(debtCounterparty=group.person)else it})
        require(all(next).first{it.key==key}.unmatched.none{it.entry.id==id}){"还款日期早于可归还本金，请核对日期"}
        return LedgerEngine.validate(next)
    }
}
