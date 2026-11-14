@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.qiye.ledgerflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.qiye.ledgerflow.core.*

private fun refundableAmount(state: LedgerState, expense: Entry): Long =
    expense.amountMinor - state.entries.filter { it.refundOfId == expense.id }.sumOf { it.amountMinor }

@Composable
internal fun RefundEntryDialog(state: LedgerState, id: String, vm: LedgerViewModel, onClose: () -> Unit) {
    val expense = state.entries.firstOrNull { it.id == id && it.kind == EntryKind.EXPENSE }
    if (expense == null) {
        LaunchedEffect(id) { onClose() }
        return
    }

    val currency = expense.currencyCode
    val remaining = remember(state, expense) { refundableAmount(state, expense) }
    val today = LocalClock.today()
    val accounts = remember(state.accounts, currency) {
        state.accounts.filter { quickAccountForCurrency(it, currency) != null && AccountManagement.selectable(it) }
    }
    var accountId by rememberSaveable(id) { mutableStateOf(expense.accountId?.takeIf { key -> accounts.any { it.id == key } }) }
    var amount by rememberSaveable(id) { mutableStateOf("") }
    var date by rememberSaveable(id) { mutableLongStateOf(today) }
    var calendar by rememberSaveable(id) { mutableStateOf(false) }
    var error by rememberSaveable(id) { mutableStateOf<String?>(null) }
    val belowCeiling = runCatching { CurrencyUnits.parse(amount, currency) in 1..remaining }.getOrDefault(false)

    AlertDialog(
        onDismissRequest = { if (!vm.busy) onClose() },
        title = { Text("记录退款") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val originalLabel = listOf(expense.category, expense.subcategory).filter { it.isNotBlank() }.joinToString(" / ")
                Text("$originalLabel · ${LedgerCalendar.format(expense.epochDay)}")
                Text("原支出 ${nativeMoney(expense.amountMinor, currency)} · 可退 ${nativeMoney(remaining, currency)}")
                if (remaining > 0L) {
                    OutlinedTextField(amount, { amount = it; error = null }, Modifier.fillMaxWidth(),
                        label = { Text("退款金额（$currency）") }, enabled = !vm.busy, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    TextButton(enabled = !vm.busy, onClick = { amount = CurrencyUnits.format(remaining, currency); error = null }) {
                        Text(if (remaining == expense.amountMinor) "填入全额" else "填入剩余可退金额")
                    }
                    AccountPicker(state.copy(accounts = accounts), accountId, { accountId = it }, label = "退款到账账户", allowNone = false)
                    OutlinedButton(enabled = !vm.busy, onClick = { calendar = true }) { Text("退款日期 ${LedgerCalendar.format(date)}") }
                    if (amount.isNotBlank() && !belowCeiling)Text("请输入大于零且不超过可退金额的数值。", color = MaterialTheme.colorScheme.error)
                } else {
                    Text("这笔支出已全部退完。")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !vm.busy && belowCeiling && accountId != null, onClick = {
                val minor = CurrencyUnits.parse(amount, currency)
                val destination = accountId
                val day = date
                val minute = LocalClock.minute()
                vm.changeWithFailure("退款已保存", onClose, { error = it }) { live ->
                    val original = live.entries.firstOrNull { it.id == id && it.kind == EntryKind.EXPENSE } ?: error("原支出不存在")
                    require(minor in 1..refundableAmount(live, original)) { "退款超过剩余可退金额" }
                    require(day in original.epochDay..today) { "退款日期须在原支出日期至今天之间" }
                    val account = live.accounts.firstOrNull { it.id == destination && AccountManagement.selectable(it) }
                    require(account != null && quickAccountForCurrency(account, original.currencyCode) != null) { "请选择同币种的有效到账账户" }
                    val category = live.categories.firstOrNull { it.id == original.categoryId && it.active }
                    val categoryId = category?.takeIf { it.parentId == null || live.categories.any { parent -> parent.id == it.parentId && parent.active } }?.id
                    LedgerEngine.saveEntry(live, EntryDraft(EntryKind.REFUND, minor, day, minute, original.category,
                        account.id, categoryId = categoryId, subcategory = original.subcategory, currencyCode = original.currencyCode,
                        excludeFromBudget = original.excludeFromBudget, excludeFromStatistics = original.excludeFromStatistics,
                        affectsBudget = original.affectsBudget && original.budgetId != null, refundOfId = id, budgetId = original.budgetId), today)
                }
            }) { Text("保存退款") }
        },
        dismissButton = { TextButton(enabled = !vm.busy, onClick = onClose) { Text(if (remaining > 0L) "取消" else "关闭") } }
    )

    if (calendar) {
        val millisPerDay = 86_400_000L
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = date * millisPerDay,
            yearRange = 1900..2199,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    utcTimeMillis in expense.epochDay * millisPerDay..today * millisPerDay
            }
        )
        DatePickerDialog(onDismissRequest = { calendar = false }, confirmButton = {
            TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                picker.selectedDateMillis?.let { date = it / millisPerDay }
                calendar = false
            }) { Text("确定") }
        }, dismissButton = { TextButton({ calendar = false }) { Text("取消") } }) {
            DatePicker(picker, showModeToggle = false)
        }
    }
}
