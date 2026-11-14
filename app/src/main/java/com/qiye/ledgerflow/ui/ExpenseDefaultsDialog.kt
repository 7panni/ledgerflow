package com.qiye.ledgerflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import com.qiye.ledgerflow.core.EntryKind
import com.qiye.ledgerflow.core.LedgerCategory
import com.qiye.ledgerflow.core.LedgerState

internal fun LedgerState.defaultExpenseCategory(): LedgerCategory? {
    val active=categories.filter { it.kind==EntryKind.EXPENSE && it.active }
    return active.firstOrNull { category ->
        category.id==settings.defaultExpenseCategoryId &&
            (category.parentId==null || active.any { it.id==category.parentId })
    }
}

@Composable
internal fun ExpenseDefaultsDialog(state: LedgerState, vm: LedgerViewModel, onClose: () -> Unit) {
    var categoryId by rememberSaveable { mutableStateOf(state.defaultExpenseCategory()?.id) }
    var includesBudget by rememberSaveable { mutableStateOf(!state.settings.defaultExpenseExcludesBudget) }
    val categories=state.categories.filter { category ->
        category.active && (category.parentId==null || state.categories.any { it.id==category.parentId && it.active })
    }
    AlertDialog(
        onDismissRequest={ if(!vm.busy)onClose() },
        title={ Text("日常支出默认值") },
        text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("用于新建日常支出，每笔仍可单独调整。")
                CategoryPicker(state.copy(categories=categories),EntryKind.EXPENSE,categoryId){categoryId=it.id}
                if(categoryId!=null)TextButton({categoryId=null}){Text("清除默认分类")}
                Toggle("默认占用预算",includesBudget){includesBudget=it}
            }
        },
        confirmButton={
            TextButton(enabled=!vm.busy,onClick={
                vm.change("日常支出默认值已保存",onClose){ live ->
                    live.copy(settings=live.settings.copy(
                        defaultExpenseCategoryId=categoryId,
                        defaultExpenseExcludesBudget=!includesBudget
                    ))
                }
            }){Text("保存")}
        },
        dismissButton={TextButton(onClick=onClose,enabled=!vm.busy){Text("取消")}}
    )
}
