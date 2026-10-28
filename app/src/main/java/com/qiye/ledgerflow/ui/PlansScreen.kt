package com.qiye.ledgerflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qiye.ledgerflow.core.*

@Composable fun PlansScreen(state: LedgerState, padding: PaddingValues, onAdd: () -> Unit, onSelect: (Plan) -> Unit) {
    val confirmed = state.entries.mapNotNull { it.planId }.toSet()
    val pending = state.plans.filterNot { it.id in confirmed }.sortedWith(compareBy<Plan> { it.dueEpochDay }.thenBy { it.sequence })
    val completed = state.plans.filter { it.id in confirmed }.sortedByDescending { it.dueEpochDay }
    LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(14.dp, 0.dp, 14.dp, 86.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item { SectionTitle("全部计划", "新增", onAdd) }
        item { Text("待发生计划用于预测；确认实际发生才改变余额。逾期未确认计划按今天估算，可延期到真实预计日期。", color = Muted, fontSize = 10.sp, lineHeight = 14.sp) }
        item { PageSectionHeader("待发生 · ${pending.size}") }
        if (pending.isEmpty()) item { EmptyHint("暂无待发生计划。添加一笔未来支出或收入来查看预测变化。") }
        items(pending, key = { it.id }) { PlanRow(state, it, false) { onSelect(it) } }
        if (completed.isNotEmpty()) item { PageSectionHeader("已实际发生 · ${completed.size}") }
        items(completed, key = { it.id }) { PlanRow(state, it, true) { onSelect(it) } }
    }
}

// Port of DataCard: same straight border, panel, spacing and compact title/subtitle.
@Composable private fun PlanRow(state: LedgerState, plan: Plan, confirmed: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().border(1.dp, Line).clickable(onClick = onClick), shape = RoundedCornerShape(0.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(plan.title, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text((if (confirmed) "已发生 · " else if (plan.dueEpochDay < LocalClock.today()) "逾期 · " else "待发生 · ") + LedgerCalendar.format(plan.dueEpochDay) + " · " + state.accountName(plan.accountId), color = Muted, fontSize = 9.sp, lineHeight = 12.sp)
                }
                Text(signedCashMoney(if (plan.kind == EntryKind.EXPENSE) -plan.amountMinor else plan.amountMinor), fontWeight = FontWeight.Bold, fontSize = 11.sp, color = if (plan.kind == EntryKind.EXPENSE) ExpenseColor else IncomeColor)
            }
            Text(if (confirmed) "查看实际账目 / 撤销 ›" else "确认发生 / 编辑 / 延期 ›", color = LedgerGreen, fontSize = 9.sp)
        }
    }
}

@Composable fun PlanActions(state: LedgerState, plan: Plan, vm: LedgerViewModel, onDismiss: () -> Unit, onEdit: () -> Unit, onActual: (Entry) -> Unit) {
    val actual = state.entries.firstOrNull { it.planId == plan.id }
    var mode by rememberSaveable(plan.id) { mutableStateOf("view") }
    var date by rememberSaveable(plan.id) { mutableStateOf(LedgerCalendar.format(LocalClock.today())) }
    var time by rememberSaveable(plan.id) { mutableStateOf(LocalClock.timeText(LocalClock.minute())) }
    var postpone by rememberSaveable(plan.id) { mutableStateOf(LedgerCalendar.format(maxOf(LocalClock.today(), plan.dueEpochDay) + 1)) }
    var error by remember { mutableStateOf<String?>(null) }
    when (mode) {
        "view" -> AlertDialog(onDismissRequest = onDismiss, title = { Text(plan.title) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${plan.kind.label()} ¥${Money.format(plan.amountMinor)}")
                Text("预计 ${LedgerCalendar.format(plan.dueEpochDay)} · ${state.accountName(plan.accountId)}")
                if (plan.note.isNotBlank()) Text(plan.note)
                if (actual == null) {
                    if (plan.dueEpochDay < LocalClock.today()) Text("逾期未确认：预测暂按今天估算。", color = ExpenseColor)
                    Button(shape = RoundedCornerShape(0.dp), onClick = { mode = "confirm" }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("确认实际发生") }
                    OutlinedButton(shape = RoundedCornerShape(0.dp), onClick = onEdit, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("编辑计划") }
                    OutlinedButton(shape = RoundedCornerShape(0.dp), onClick = { mode = "postpone" }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("延期") }
                    TextButton(onClick = { mode = "delete" }, enabled = !vm.busy) { Text("删除计划", color = MaterialTheme.colorScheme.error) }
                } else {
                    Text("已在 ${LedgerCalendar.format(actual.epochDay)} ${LocalClock.timeText(actual.minuteOfDay)} 实际发生。预测不再重复计算。")
                    OutlinedButton(shape = RoundedCornerShape(0.dp), onClick = { onActual(actual) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("编辑关联实际账目") }
                    TextButton(onClick = { mode = "undo" }, enabled = !vm.busy) { Text("撤销实际发生") }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
        "confirm" -> AlertDialog(onDismissRequest = { if (!vm.busy) mode = "view" }, title = { Text("确认实际发生") }, text = {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${plan.title} · ${plan.kind.label()} ¥${Money.format(plan.amountMinor)}。确认后仅生成一笔实际账目。")
                OutlinedTextField(date, { date = it }, label = { Text("实际日期 YYYY-MM-DD") }, singleLine = true)
                OutlinedTextField(time, { time = it }, label = { Text("本地时间 HH:MM") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(enabled = !vm.busy, onClick = {
            try {
                val day = LedgerCalendar.parse(date); val minute = LocalClock.parseTime(time)
                LedgerEngine.confirmPlan(state, plan.id, day, minute, LocalClock.today())
                vm.change("已确认实际发生", onDismiss) { LedgerEngine.confirmPlan(it, plan.id, day, minute, LocalClock.today()) }
            } catch (failure: Exception) { error = failure.message }
        }) { Text(if (vm.busy) "保存中…" else "确认") } }, dismissButton = { TextButton(enabled = !vm.busy, onClick = { mode = "view" }) { Text("取消") } })
        "postpone" -> AlertDialog(onDismissRequest = { if (!vm.busy) mode = "view" }, title = { Text("延期计划") }, text = {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("原预计日期 ${LedgerCalendar.format(plan.dueEpochDay)}")
                OutlinedTextField(postpone, { postpone = it }, label = { Text("新日期 YYYY-MM-DD") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(enabled = !vm.busy, onClick = {
            try {
                val day = LedgerCalendar.parse(postpone)
                LedgerEngine.postponePlan(state, plan.id, day)
                vm.change("计划已延期", onDismiss) { LedgerEngine.postponePlan(it, plan.id, day) }
            } catch (failure: Exception) { error = failure.message }
        }) { Text("保存延期") } }, dismissButton = { TextButton(enabled = !vm.busy, onClick = { mode = "view" }) { Text("取消") } })
        "delete", "undo" -> AlertDialog(onDismissRequest = { if (!vm.busy) mode = "view" }, title = { Text(if (mode == "delete") "删除计划？" else "撤销实际发生？") }, text = {
            Text(if (mode == "delete") "删除待发生计划后，它将不再参与预测。" else "将删除关联实际账目，恢复待发生计划。余额按剩余账目与校正重新计算，预测恢复此计划。")
        }, confirmButton = { TextButton(enabled = !vm.busy, onClick = {
            vm.change(if (mode == "delete") "计划已删除" else "已撤销实际发生", onDismiss) {
                if (mode == "delete") LedgerEngine.deletePlan(it, plan.id) else LedgerEngine.undoPlan(it, plan.id)
            }
        }) { Text("确认") } }, dismissButton = { TextButton(enabled = !vm.busy, onClick = { mode = "view" }) { Text("取消") } })
    }
}
