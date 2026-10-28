@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.qiye.ledgerflow.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qiye.ledgerflow.core.*

// Keep the existing full-page/saveable form lifecycle; reuse EditDialog's panel and spacing.
@Composable private fun FormPage(title: String, busy: Boolean, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler { if (!busy) onBack() }
    Scaffold(modifier = Modifier.imePadding(), containerColor = Paper, contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Row(Modifier.fillMaxWidth().background(Paper).statusBarsPadding().height(62.dp).padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.5).sp)
                TextButton(onClick = onBack, enabled = !busy) { Text("取消", fontSize = 11.sp) }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp).background(Panel).border(1.dp, Line).padding(horizontal = 11.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
    }
}

@Composable private fun KindPicker(kind: EntryKind, onPick: (EntryKind) -> Unit, allowTransfer: Boolean) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        listOf(EntryKind.EXPENSE,EntryKind.INCOME,EntryKind.TRANSFER,EntryKind.REFUND).filter { allowTransfer || it != EntryKind.TRANSFER }.forEach {
            FilterChip(selected = it == kind, onClick = { onPick(it) }, label = { Text(it.label()) })
        }
    }
}

@Composable private fun CategoryInput(value: String, onChange: (String) -> Unit, kind: EntryKind) {
    Column {
        OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text("分类") }, singleLine = true)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (if (kind == EntryKind.INCOME) listOf("工资", "其他收入") else listOf("餐饮", "日用", "交通", "居住", "其他支出")).forEach {
                SuggestionChip(onClick = { onChange(it) }, label = { Text(it) })
            }
        }
    }
}

@Composable fun EntryEditor(state:LedgerState,id:String?,vm:LedgerViewModel,onBack:()->Unit){
    QuickEntryEditor(state,vm,onBack,id)
}

@Composable fun PlanEditor(state: LedgerState, id: String?, vm: LedgerViewModel, onBack: () -> Unit) {
    val existing = state.plans.firstOrNull { it.id == id }
    var title by rememberSaveable(id) { mutableStateOf(existing?.title ?: "") }
    var kind by rememberSaveable(id) { mutableStateOf(existing?.kind ?: EntryKind.EXPENSE) }
    var amount by rememberSaveable(id) { mutableStateOf(existing?.let { Money.format(it.amountMinor) } ?: "") }
    var date by rememberSaveable(id) { mutableStateOf(LedgerCalendar.format(existing?.dueEpochDay ?: LocalClock.today() + 1)) }
    var category by rememberSaveable(id) { mutableStateOf(existing?.category ?: "其他支出") }
    var account by rememberSaveable(id) { mutableStateOf(existing?.accountId) }
    var note by rememberSaveable(id) { mutableStateOf(existing?.note ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    FormPage(if (existing == null) "新增计划" else "编辑计划", vm.busy, onBack) {
        Text("这里添加一次计划；固定周期、分期与预算可在计划页新增。保存计划只影响预测。", style = MaterialTheme.typography.bodySmall)
        KindPicker(kind, { kind = it; if (existing == null) category = if (it == EntryKind.INCOME) "其他收入" else "其他支出" }, false)
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("计划名称") }, singleLine = true)
        OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text("预计金额（元）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(date, { date = it }, Modifier.fillMaxWidth(), label = { Text("预计日期 YYYY-MM-DD") }, singleLine = true)
        CategoryInput(category, { category = it }, kind)
        AccountPicker(state, account, { account = it })
        OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("备注（可选）") }, minLines = 2, maxLines = 5)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(shape = RoundedCornerShape(0.dp), enabled = !vm.busy, modifier = Modifier.fillMaxWidth(), onClick = {
            try {
                val draft = PlanDraft(title, kind, Money.parse(amount), LedgerCalendar.parse(date), category, account, note)
                LedgerEngine.savePlan(state, draft, id)
                error = null
                vm.change("计划已保存", onBack) { LedgerEngine.savePlan(it, draft, id) }
            } catch (failure: Exception) { error = failure.message }
        }) { Text(if (vm.busy) "保存中…" else "保存计划") }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable fun BalanceEditor(state: LedgerState, accountId: String?, vm: LedgerViewModel, onBack: () -> Unit) {
    val balances = LedgerEngine.balances(state)
    val current = if (accountId == null) balances.overallMinor else balances.accountsMinor.getValue(accountId)
    val currency=accountId?.let{key->state.accounts.first{it.id==key}.currencyCode}?:"CNY"
    var amount by rememberSaveable(accountId) { mutableStateOf(CurrencyUnits.format(current,currency)) }
    var reason by rememberSaveable(accountId) { mutableStateOf(if (state.corrections.isEmpty() && accountId == null) "初始余额" else "余额校正") }
    var error by remember { mutableStateOf<String?>(null) }
    FormPage(if (accountId == null) "校正整体余额" else "校正 ${state.accountName(accountId)}", vm.busy, onBack) {
        Text("当前 ${nativeMoney(current,currency)}", style = MaterialTheme.typography.titleLarge)
        Text(if (accountId == null) "请输入确认过的整体余额。账户余额保持独立，校正记录不计入普通收支。" else "人民币账户按新旧差额同步整体余额，保留原有差额；其他币种只校正原币。均不计普通收支。", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text("校正后的余额（$currency，可为负）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
        OutlinedTextField(reason, { reason = it }, Modifier.fillMaxWidth(), label = { Text("校正说明") }, singleLine = true)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(shape = RoundedCornerShape(0.dp), enabled = !vm.busy, modifier = Modifier.fillMaxWidth(), onClick = {
            try {
                val minor = CurrencyUnits.parse(amount,currency,signed = true)
                LedgerEngine.calibrateAccount(state, accountId, minor, LocalClock.today(), LocalClock.minute(), reason)
                error = null
                vm.change("余额校正已保存", onBack) { LedgerEngine.calibrateAccount(it, accountId, minor, LocalClock.today(), LocalClock.minute(), reason) }
            } catch (failure: Exception) { error = failure.message }
        }) { Text(if (vm.busy) "保存中…" else "确认校正") }
    }
}

@Composable fun AccountEditor(state: LedgerState, vm: LedgerViewModel, onBack: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var balance by rememberSaveable { mutableStateOf("0.00") }
    var error by remember { mutableStateOf<String?>(null) }
    FormPage("新增账户", vm.busy, onBack) {
        Text("账户是整体余额之外的辅助明细。新增账户的余额不会重复加到整体余额。", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("账户名称") }, singleLine = true)
        OutlinedTextField(balance, { balance = it }, Modifier.fillMaxWidth(), label = { Text("此账户初始余额（元，可为负）") }, singleLine = true)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(shape = RoundedCornerShape(0.dp), enabled = !vm.busy, modifier = Modifier.fillMaxWidth(), onClick = {
            try {
                val opening = Money.parse(balance, true)
                LedgerEngine.addAccount(state, name, opening)
                error = null
                vm.change("账户已添加", onBack) { LedgerEngine.addAccount(it, name, opening) }
            } catch (failure: Exception) { error = failure.message }
        }) { Text(if (vm.busy) "保存中…" else "添加账户") }
    }
}
