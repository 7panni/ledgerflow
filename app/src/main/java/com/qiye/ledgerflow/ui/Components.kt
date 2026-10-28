package com.qiye.ledgerflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qiye.ledgerflow.core.*

fun EntryKind.label(): String = when(this){EntryKind.EXPENSE->"支出";EntryKind.INCOME->"收入";EntryKind.TRANSFER->"转账";EntryKind.REFUND->"退款/报销";EntryKind.BORROW->"借入本金";EntryKind.LEND->"借出本金";EntryKind.REPAY->"归还本金";EntryKind.COLLECT->"收回本金"}
fun nativeMoney(amount:Long,code:String)=when(code){"CNY"->"¥";"USD"->"$";else->"$code "}+CurrencyUnits.format(amount,code)

fun LedgerState.accountName(id: String?): String = if (id == null) "无账户" else accounts.firstOrNull { it.id == id }?.name ?: "未知账户"

@Composable fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    PageSectionHeader(title) {
        if (action != null) TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp), modifier = Modifier.heightIn(min = 28.dp)) { Text(action, fontSize = 9.sp, color = Muted) }
    }
}

// Port of EmptyCard from the user's cashflow UI.
@Composable fun EmptyHint(text: String) {
    Card(Modifier.fillMaxWidth().border(1.dp, Line), shape = RoundedCornerShape(0.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Text(text, Modifier.padding(14.dp), color = Muted, fontSize = 10.sp)
    }
}

@Composable fun AccountPicker(state: LedgerState, selected: String?, onSelect: (String?) -> Unit, label: String = "账户（可留空）", allowNone: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(shape = RoundedCornerShape(0.dp), onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(state.accountName(selected) + "  ▾")
            }
            DropdownMenu(expanded, { expanded = false }) {
                if (allowNone) DropdownMenuItem(text = { Text("无账户") }, onClick = { onSelect(null); expanded = false })
                state.accounts.filter{AccountManagement.selectable(it)||it.id==selected}.forEach { account -> DropdownMenuItem(text = { Text(account.name) }, onClick = { onSelect(account.id); expanded = false }) }
                if (state.accounts.isEmpty() && !allowNone) DropdownMenuItem(text = { Text("请先在管理页添加账户") }, onClick = { expanded = false }, enabled = false)
            }
        }
    }
}

@Composable fun MessageDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } })
}
