package com.qiye.ledgerflow.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qiye.ledgerflow.BuildConfig
import com.qiye.ledgerflow.core.*

@Composable fun ManageScreen(state:LedgerState,padding:PaddingValues,vm:LedgerViewModel,onFeature:(String)->Unit,onExport:()->Unit,onRestore:()->Unit,onExportPrevious:()->Unit){
    var entryDefaults by remember{mutableStateOf(false)}
    var accountOrder by remember{mutableStateOf(false)}
    var settings by remember{mutableStateOf(false)};var start by remember(state.settings.monthStartDay){mutableStateOf(state.settings.monthStartDay.toString())};var error by remember{mutableStateOf<String?>(null)};var showCorrections by remember{mutableStateOf(false)};var cycleDetails by remember{mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),contentPadding=PaddingValues(14.dp,0.dp,14.dp,24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{SectionTitle("记账设置")}
        item{ManageGroup{
            ManageRow("默认账户顺序","按余额选择"){accountOrder=true};HorizontalDivider(color=Line,thickness=.5.dp)
            ManageRow("日常支出默认值","分类 / 预算"){entryDefaults=true};HorizontalDivider(color=Line,thickness=.5.dp)
            ManageRow("分类管理"){onFeature("categories")};HorizontalDivider(color=Line,thickness=.5.dp)
            ManageRow("借贷管理"){onFeature("debts")};HorizontalDivider(color=Line,thickness=.5.dp)
            ManageRow("每月账期","每月${state.settings.monthStartDay}日起"){settings=true}
        }}
        item{SectionTitle("现金流设置")}
        item{ManageGroup{
            ManageRow("显示与计划设置"){onFeature("settings")};HorizontalDivider(color=Line,thickness=.5.dp)
            ManageRow("余额调整记录","${state.corrections.size}条"){showCorrections=!showCorrections}
        }}
        if(showCorrections){
            if(state.corrections.isEmpty())item{EmptyHint("暂无余额调整记录。")}
            items(state.corrections.sortedByDescending{it.sequence},key={it.id}){c->val code=c.accountId?.let{id->state.accounts.firstOrNull{it.id==id}?.currencyCode}?:"CNY";PanelBox{
                Text(if(c.accountId==null)"人民币整体余额"else state.accountName(c.accountId),fontSize=12.sp)
                Text("${nativeMoney(c.beforeMinor,code)} → ${nativeMoney(c.afterMinor,code)}",fontSize=12.sp)
                Text("${LedgerCalendar.format(c.epochDay)} · ${c.reason}",fontSize=10.sp,color=Muted)
            }}
        }
        item{SectionTitle("数据与备份")}
        item{ManageGroup{
            ManageRow("数据管理","分别导入、导出、清空"){onFeature("data")};HorizontalDivider(color=Line,thickness=.5.dp)
            ManageRow("备份全部数据",onClick=onExport);HorizontalDivider(color=Line,thickness=.5.dp)
            ManageRow("恢复全部数据",onClick=onRestore)
            if(vm.hasRecovery){HorizontalDivider(color=Line,thickness=.5.dp);ManageRow("导出操作前备份",onClick=onExportPrevious)}
        }}
        item{Text("记账与现金流 ${BuildConfig.VERSION_NAME.substringBefore('-')}\n本机离线保存 · 桌面可添加快速记账＋",fontSize=10.sp,color=Muted)}
    }
    if(entryDefaults)ExpenseDefaultsDialog(state,vm){entryDefaults=false}
    if(accountOrder)DefaultAccountOrderDialog(state,vm){accountOrder=false}
    if(settings)AlertDialog(onDismissRequest={if(!vm.busy)settings=false},title={Text("每月账期")},text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState())){
        OutlinedTextField(start,{start=it},label={Text("每月起始日（1—31）")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
        val chosen=start.toIntOrNull()?.takeIf{it in 1..31}
        if(chosen!=null){val month=LedgerCalendar.periodMonth(LocalClock.today(),chosen);val range=LedgerCalendar.period(month.year,month.month,chosen)
            Text("本期：${LedgerCalendar.format(range.first)} — ${LedgerCalendar.format(range.last)}",fontSize=12.sp)
        }
        Text("用于首页、统计和月预算。保存会调整已有月预算周期，保留已用和剩余；周/自定义预算不变。",fontSize=11.sp)
        Text("短月从月末起算，下月恢复所选日期。",fontSize=10.sp,color=Muted)
        val monthly=state.budgets.filter{it.period==com.qiye.ledgerflow.cashflow.data.BudgetPeriod.MONTHLY}
        val different=monthly.count{BudgetRules.monthlyStartDay(it)!=(chosen?:state.settings.monthStartDay)}
        if(different>0||state.settings.cashflow.budgetMonthStartDay!=state.settings.monthStartDay){
            TextButton({cycleDetails=!cycleDetails}){Text(if(cycleDetails)"收起现有预算"else if(different>0)"${different}个月预算将调整 · 查看"else "旧账期设置不同 · 查看")}
            if(cycleDetails){
                if(state.settings.cashflow.budgetMonthStartDay!=state.settings.monthStartDay)Text("旧预算默认${state.settings.cashflow.budgetMonthStartDay}日起，保存后采用上方日期。",fontSize=10.sp,color=Muted)
                monthly.forEach{b->val c=BudgetRules.cycle(b,LocalClock.today());Text("${b.name} · 每月${BudgetRules.monthlyStartDay(b)}日 · "+(c?.let{"${LedgerCalendar.format(it.first)} — ${LedgerCalendar.format(it.second)}"}?:"尚未开始"),fontSize=10.sp,color=Muted)}
            }
        }
        error?.let{Text(it,color=ExpenseColor)}
    }},confirmButton={TextButton(enabled=!vm.busy,onClick={val d=start.toIntOrNull();if(d==null||d !in 1..31)error="请输入1—31"else vm.migrate("统一账期已保存",{settings=false}){BudgetRules.unifyMonthStart(it,d,LocalClock.today())}}){Text("保存")}},dismissButton={TextButton({settings=false}){Text("取消")}})
}
@Composable private fun ManageGroup(content:@Composable ColumnScope.()->Unit)=Column(Modifier.fillMaxWidth().background(Panel).border(1.dp,Line),content=content)
@Composable private fun ManageRow(label:String,detail:String="",onClick:()->Unit)=Row(Modifier.fillMaxWidth().clickable(onClick=onClick).heightIn(min=50.dp).padding(horizontal=12.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically){
    Text(label,Modifier.weight(1f),fontSize=13.sp)
    if(detail.isNotBlank())Text(detail,fontSize=10.sp,color=Muted)
    Text(" ›",fontSize=16.sp,color=Muted)
}
