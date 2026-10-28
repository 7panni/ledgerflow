package com.qiye.ledgerflow.data

import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.cashflow.data.*
import com.qiye.ledgerflow.cashflow.CashFlowCalculator
import com.qiye.ledgerflow.importer.TableImporter
import org.json.*
import java.security.MessageDigest
import java.time.LocalDate

data class NativeModulePreview(val module:DataModule,val data:LedgerState,val key:String)
data class ModuleExport(val bytes:ByteArray,val extension:String,val mime:String,val description:String)
object ModuleExchange {
    private val codec=JsonLedgerCodec()
    private fun canonical(v:Any?):String=when(v){is JSONObject->v.keys().asSequence().toList().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+canonical(v.get(it))};is JSONArray->(0 until v.length()).joinToString(",","[","]"){canonical(v.get(it))};is String->JSONObject.quote(v);else->v.toString()}
    private fun digest(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    fun nativeExport(state:LedgerState,module:DataModule,today:Long):ModuleExport {
        val payload=JSONObject(codec.encode(DataModules.snapshot(state,module,today)));val key=digest(module.name+canonical(payload))
        val root=JSONObject().put("format","qiye-ledgerflow-module").put("version",1).put("module",module.name).put("payload",payload).put("checksum",key)
        return ModuleExport(root.toString(2).toByteArray(),"json","application/json","保存全部${if(module==DataModule.BOOK)"记账"else "现金流"}数据，导入时只替换该类数据。保留历史账户及计划名称，恢复记账不改变当前余额。")
    }
    fun nativePreview(text:String,expected:DataModule):NativeModulePreview {
        require(text.toByteArray().size<=JsonLedgerCodec.MAX_BYTES){"模块文件超过64MB"};val tok=JSONTokener(text);val root=tok.nextValue() as? JSONObject?:error("模块文件根须为对象");require(tok.nextClean()=='\u0000')
        require(root.getString("format")=="qiye-ledgerflow-module"&&root.get("version").toString()=="1"){"不支持的本应用备份格式/版本"}
        val module=enumValueOf<DataModule>(root.getString("module"));require(module==expected){"文件属于另一模块"}
        val payload=root.getJSONObject("payload");val key=digest(module.name+canonical(payload));require(key==root.getString("checksum")){"模块校验失败"}
        return NativeModulePreview(module,codec.decode(payload.toString()),key)
    }
    fun compatibleRows(state:LedgerState):List<List<String>> {
        require(state.entries.all{it.currencyCode=="CNY"}){"22列没有币种列；外币/数字币请用原生记账模块，不能当人民币导出"}
        require(state.debts.groupBy{it.person}.values.none{it.size>1}){"同人多笔债务在22列无法区分归还关系，请用本应用备份"}
        val refunds=state.entries.filter{it.kind==EntryKind.REFUND}.groupBy{it.refundOfId}
        require(null !in refunds&&refunds.values.all{it.size<=1}){"无原支出或多笔退款无法放入一个退款日期栏，请用本应用备份"}
        fun date(e:Entry)="${LedgerCalendar.format(e.epochDay)} ${String.format(java.util.Locale.ROOT,"%02d:%02d:%02d",e.minuteOfDay/60,e.minuteOfDay%60,e.second)}"
        return state.entries.filter{it.kind!=EntryKind.REFUND}.sortedWith(compareBy<Entry>{it.epochDay}.thenBy{it.minuteOfDay}.thenBy{it.second}.thenBy{it.sequence}).map{e->
            val d=e.debtId?.let{id->state.debts.first{it.id==id}}
            require(e.kind.isOrdinary()||e.kind==EntryKind.TRANSFER||d!=null){"本金记录尚未关联借款，无法导出到这张表，请用本应用备份"}
            val refund=refunds[e.id]?.singleOrNull();if(refund!=null)require(refund.excludeFromStatistics==e.excludeFromStatistics&&refund.excludeFromBudget==e.excludeFromBudget){"退款与原账排除标记不同，请用本应用备份"}
            fun original(field:String)=Regex("(?m)^${Regex.escape(field)}：([^\n]*)").find(e.sourceDetails)?.groupValues?.get(1).orEmpty()
            val type=when(e.kind){EntryKind.INCOME->"收入";EntryKind.EXPENSE->"支出";EntryKind.TRANSFER->"转账";else->"债务"}
            val category=when(e.kind){EntryKind.BORROW->"借入";EntryKind.LEND->"借出";EntryKind.REPAY->"还款";EntryKind.COLLECT->"收回";else->e.category}
            val values=mapOf("日期" to date(e),"金额" to Money.format(e.amountMinor),"账本" to original("账本"),"类型" to type,"分类" to category,"子分类" to e.subcategory,"备注" to e.note,"标签" to e.tags,"账户" to e.accountId?.let{state.accounts.first{a->a.id==it}.name}.orEmpty(),"转入账户" to e.targetAccountId?.let{state.accounts.first{a->a.id==it}.name}.orEmpty(),"债务人" to (d?.person?:original("债务人")),"转账手续费" to "0","是否为报销" to "否","账单状态" to "正常","报销金额" to "0","报销日期" to "","退款金额" to (refund?.let{Money.format(it.amountMinor)}?:"0"),"退款日期" to refund?.let(::date).orEmpty(),"原价" to original("原价"),"不计入预算" to if(e.excludeFromBudget)"是"else "否","不计入收支" to if(e.excludeFromStatistics)"是"else "否","账单图片" to original("账单图片"))
            TableImporter.HEADERS.map{values[it].orEmpty()}
        }
    }
    fun tableExport(state:LedgerState,xlsx:Boolean):ModuleExport {
        val rows=listOf(TableImporter.HEADERS)+compatibleRows(state)
        val description="22列${if(xlsx)"XLSX（推荐，日期为文本）"else "CSV"}，${rows.size-1}行。只交换账目；余额、计划/预算归属、借贷归属及利息约定等由本应用备份保全。报销按退款表达，退款折回原账退款列，手续费按独立支出表达；图片仅引用。原App现场回导未验收。"
        val bytes=if(xlsx)XlsxTableWriter.write(rows)else ("\uFEFF"+rows.joinToString("\r\n"){r->r.joinToString(","){"\""+it.replace("\"","\"\"")+"\""}}+"\r\n").toByteArray(Charsets.UTF_8)
        return ModuleExport(bytes,if(xlsx)"xlsx"else "csv",if(xlsx)"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"else "text/csv",description)
    }
    fun legacyFlowExport(state:LedgerState,today:Long):ModuleExport {
        val source=DataModules.snapshot(state,DataModule.FLOW,today)
        require(source.occurrenceOverrides.isEmpty()&&source.plans.none{it.usesBudget}){"独立延期/指定预算计划在旧v6不能完整表达，请用原生现金流模块"}
        val balances=LedgerEngine.balances(source);val accounts=source.accounts.filterNot{it.archived}.map{AccountEntity(it.sequence,it.name,it.openingMinor,balances.accountsMinor.getValue(it.id),it.currencyCode,it.exchangeRateToCnyMicros,it.includeInForecast,it.accountType,it.active)}.toMutableList()
        val sum=accounts.filter{it.currencyCode=="CNY"&&it.isActive&&it.includeInForecast}.fold(0L){n,a->Money.add(n,a.currentBalanceCents)};val difference=Money.add(balances.overallMinor,-sum)
        var name="独立整体余额差额（仅导出）";while(accounts.any{it.name==name})name+="_"
        if(difference!=0L||source.plans.any{it.accountId==null})accounts+=AccountEntity(source.nextSequence,name,difference,difference)
        val adjustments=mutableListOf<AdjustmentEntity>();val plans=mutableListOf<PlannedTransactionEntity>()
        source.plans.forEach{p->val target=p.accountId?.let{id->source.accounts.first{it.id==id}.name}?:name
            val confirmations=source.settledOccurrences.filter{it.planId==p.id}.map{it.originalEpochDay}.toSet()
            if(p.temporary){adjustments+=AdjustmentEntity(p.sequence,p.title,AdjustmentType.TEMPORARY,p.amountMinor,target,p.dueEpochDay,!p.kind.isOutflow(),p.usesBudget,p.dueEpochDay in confirmations);return@forEach}
            var cursor=maxOf(p.postingFromEpochDay?.minus(1)?:p.dueEpochDay-1,p.notBeforeEpochDay?.minus(1)?:p.dueEpochDay-1)
            val pastCount=if(p.installmentTotal>1)RichLedger.occurrences(source,p,minOf(cursor,LedgerCalendar.lastDay)).size else 0
            val after=confirmations.filter{it>cursor}.sorted()
            if(after.isNotEmpty()){val valid=RichLedger.occurrences(source,p,after.last(),cursor+1).map{it.originalEpochDay};require(valid.all{it in confirmations}){"非连续兑现无法用旧v6单一游标表达，请用原生现金流模块"};cursor=after.last()}
            val remaining=if(p.installmentTotal>1)(p.installmentRemaining-pastCount-after.size).coerceAtLeast(0)else 0
            val type=when(p.kind){EntryKind.REPAY->EntryType.DEBT;EntryKind.COLLECT,EntryKind.INCOME->EntryType.INCOME;else->EntryType.EXPENSE}
            plans+=PlannedTransactionEntity(p.sequence,p.title,type,p.amountMinor,p.currencyCode,accountName=target,startDateEpochDay=p.dueEpochDay,repeatRule=RepeatRule.valueOf(p.repeatRule.name),endDateEpochDay=p.endEpochDay,installmentTotal=p.installmentTotal,installmentRemaining=remaining,installmentTotalAmountCents=p.installmentTotalAmountMinor,balanceAppliedThroughEpochDay=cursor,isActive=p.active)
        }
        val budgets=source.budgets.map{b->val cycle=BudgetRules.cycle(b,today);b.copy(remainingCents=BudgetRules.remaining(source,b,today),startDateEpochDay=cycle?.first?:b.startDateEpochDay,endDateEpochDay=cycle?.second?:b.endDateEpochDay)}
        val data=ForecastBackup(accounts,plans,budgets,adjustments,source.settings.cashflow,RichLedger.cryptoBalances(source).map{CryptoHoldingEntity(it.key,it.value)})
        val text=BackupJson.encode(data);val decoded=BackupJson.decode(text)
        val original=LedgerForecast.forecast(state,today,366);val legacy=CashFlowCalculator().forecast(decoded.accounts,decoded.plans,decoded.budgets,decoded.adjustments,LocalDate.ofEpochDay(today),366)
        require(original.days.zip(legacy.days).all{(a,b)->a.cnyBalanceMinor==b.balanceCents&&a.usdBalanceMinor==b.usdBalanceCents}){"当前兑现/分期/预算无法无损表达为旧v6预测，请用原生现金流模块"}
        return ModuleExport(text.toByteArray(),"json","application/json","cashflow-forecast v6；仅文件增加CNY差额账户 ${Money.format(difference)}，本地账户不改。366天CNY/USD预测已核对一致；完整信息请另存本应用备份。估值价格和开关请用本应用备份保存。旧App自动过账由旧App控制。")
    }
}
