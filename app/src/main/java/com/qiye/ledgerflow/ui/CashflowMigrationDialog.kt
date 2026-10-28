package com.qiye.ledgerflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.importer.*

@Composable fun CashflowMigrationDialog(preview:CashflowImportPreview,state:LedgerState,vm:LedgerViewModel){
    val initial=remember(preview.sourceKey,state){runCatching{preview.build(state,LocalClock.today())}}
    var details by remember{mutableStateOf(false)}
    var disable by remember(preview.sourceKey){mutableStateOf(false)}
    var update by remember(preview.sourceKey){mutableStateOf(false)}
    var adopt by remember(preview.sourceKey){mutableStateOf(false)}
    var amount by remember(preview.sourceKey){mutableStateOf(initial.getOrNull()?.suggestedOverallMinor?.let(Money::format).orEmpty())}
    val options=runCatching{CashflowMigrationOptions(disable,update,adopt,if(adopt)Money.parse(amount,true)else null)}
    val built=remember(preview.sourceKey,state,disable,update,adopt,amount){options.mapCatching{preview.build(state,LocalClock.today(),it)}}
    AlertDialog(onDismissRequest={if(!vm.busy)vm.cancelCashflowImport()},title={Text("啥时候有钱 · 导入预览")},text={Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        val b=preview.source
        Text("${b.accounts.size}账户 / ${b.plans.size}固定计划 / ${b.budgets.size}预算 / ${b.adjustments.size}调整 / ${b.holdings.size}持仓",fontSize=12.sp)
        Text("合并到现有数据，保留原账目、分类、借贷和计划。导入账户采用文件余额，已导入项目默认保留。确认前不会修改。",fontSize=11.sp)
        Toggle("停用其他原未兑现计划/预算（保留原记录）",disable){disable=it}
        if(state.cashflowSources.isNotEmpty()){
            Text("这些数据已导入过，默认保留当前进度。",fontSize=11.sp)
            Toggle("更新已导入的数据（已发生的计划保留）",update){update=it}
            if(update)Text("将更新账户余额、预算剩余、持仓和显示设置；已发生的计划保留。",fontSize=11.sp,color=ExpenseColor)
        }
        Toggle("同时调整人民币整体余额",adopt){adopt=it}
        Text("建议 ¥${initial.getOrNull()?.suggestedOverallMinor?.let(Money::format)?:"—"}，按参与预测的人民币账户余额合计，不含美元和数字币。当前 ¥${Money.format(LedgerEngine.balances(state).overallMinor)}。",fontSize=11.sp)
        if(adopt)OutlinedTextField(amount,{amount=it},label={Text("确认的整体余额（可手动修改）")})
        built.fold(onSuccess={result->TextButton({details=!details}){Text(if(details)"收起明细"else "查看导入明细")};if(details)result.messages.forEach{Text(it,fontSize=11.sp,color=Muted)}},onFailure={Text("无法导入：${it.message}",color=ExpenseColor,fontSize=11.sp)})
        Text("导入后自动入账关闭。过去未确认的项目保留原日期，不自动补记；导入前会保留完整备份。",fontSize=10.sp,color=Muted)
    }},confirmButton={TextButton(enabled=!vm.busy&&built.isSuccess&&built.getOrNull()?.duplicate==false,onClick={options.getOrNull()?.let(vm::confirmCashflowImport)}){Text(if(vm.busy)"保存中…"else "确认导入")}},dismissButton={TextButton(enabled=!vm.busy,onClick=vm::cancelCashflowImport){Text("取消")}})
}
