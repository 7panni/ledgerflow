@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.qiye.ledgerflow.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.LedgerFlowTiming
import com.qiye.ledgerflow.cashflow.ui.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.delay

private sealed interface Form {
    data class Entry(val id: String? = null) : Form
    data class Plan(val id: String? = null) : Form
    data class Balance(val accountId: String? = null) : Form
    data object Account : Form
}

private val formSaver = Saver<Form?, List<String>>(
    save = { form -> when (form) {
        is Form.Entry -> listOf("entry", form.id.orEmpty())
        is Form.Plan -> listOf("plan", form.id.orEmpty())
        is Form.Balance -> listOf("balance", form.accountId.orEmpty())
        Form.Account -> listOf("account", "")
        null -> listOf("none", "")
    } },
    restore = { saved -> when (saved[0]) {
        "entry" -> Form.Entry(saved[1].ifEmpty { null })
        "plan" -> Form.Plan(saved[1].ifEmpty { null })
        "balance" -> Form.Balance(saved[1].ifEmpty { null })
        "account" -> Form.Account
        else -> null
    } },
)

@Composable fun LedgerApp(quickEntryRequest: Int, vm: LedgerViewModel = viewModel()) {
    CashflowTheme {
        var destination by rememberSaveable { mutableIntStateOf(0) }
        var extra by rememberSaveable { mutableStateOf<String?>(null) }
        var form by rememberSaveable(stateSaver = formSaver) { mutableStateOf<Form?>(null) }
        var selectedPlanId by rememberSaveable { mutableStateOf<String?>(null) }
        var consumedQuickRequest by rememberSaveable { mutableIntStateOf(0) }
        val state = vm.state
        LaunchedEffect(state!=null,vm.busy) {
            if(state!=null&&!vm.busy){withFrameNanos{};withFrameNanos{};LedgerFlowTiming.once("first_frame_operable",state.entries.size.toLong())}
        }
        var refundEntryId by rememberSaveable{mutableStateOf<String?>(null)}
        var deleteEntryId by remember{mutableStateOf<String?>(null)}
        androidx.activity.compose.BackHandler(enabled=extra!=null&&form==null){extra=null}
        LaunchedEffect(form) { if(form is Form.Entry) vm.clearNotice() }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportTo) }
        val exportPrevious = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportPreviousTo) }
        val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::previewRestore) }
        LaunchedEffect(quickEntryRequest, state != null) {
            if (quickEntryRequest > consumedQuickRequest && state != null && vm.readFailure == null) {
                consumedQuickRequest = quickEntryRequest
                form = Form.Entry()
            }
        }
        LaunchedEffect(state?.settings?.cashflow?.automaticPosting){if(state?.settings?.cashflow?.automaticPosting==true)vm.refreshAutomatic()}
        LaunchedEffect(vm.notice) { vm.notice?.let { message -> delay(if(message=="账目已保存")1200L else 3500L); if(vm.notice==message)vm.clearNotice() } }
        Surface(Modifier.fillMaxSize(), color = Paper) {
            Box {
                if (state == null) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        if (vm.busy) { CircularProgressIndicator(); Text("正在读取本机账本…", Modifier.padding(top = 16.dp)) }
                        else {
                            Text("账本读取受保护", style = MaterialTheme.typography.titleLarge)
                            Text("${vm.readFailure}\n\n原数据已保留，自动写入已锁定。可以选择有效备份，验证并确认后恢复。", Modifier.padding(vertical = 20.dp))
                            Button(onClick = { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !vm.busy) { Text("选择备份并预览") }
                        }
                    }
                } else when (val current = form) {
                    is Form.Entry -> EntryEditor(state, current.id, vm) { form = null }
                    is Form.Plan -> if(current.id==null)PlanEditor(state,null,vm){form=null} else EditExistingCashflowPlan(state,current.id,vm){form=null}
                    is Form.Balance -> BalanceEditor(state, current.accountId, vm) { form = null }
                    Form.Account -> AccountEditor(state, vm) { form = null }
                    null -> Scaffold(contentWindowInsets = WindowInsets.safeDrawing,
                        topBar = { CashTopBar(destination) },
                        bottomBar = { CashBottomBar(destination) { destination = it; extra=null } }, floatingActionButton = { if(extra==null&&destination in 0..1)FloatingActionButton(containerColor = LedgerGreen, contentColor = Color.White, onClick = { form = Form.Entry() }) { Text("＋", style = MaterialTheme.typography.headlineMedium) } }) { padding ->
                        val onEntry:(Entry)->Unit={form=Form.Entry(it.id)}
                        val onRefund:(Entry)->Unit={refundEntryId=it.id}
                        when(extra){
                            "data"->DataManagementScreen(state,padding,vm){extra=null}
                            "categories"->CategoriesScreen(state,padding,vm){extra=null}
                            "debts"->DebtsScreen(state,padding,vm,{extra=null},onEntry)
                            "settings"->FullCashflowPage(2,state,padding,vm){id->selectedPlanId=state.plans.firstOrNull{it.sequence==id}?.id}
                            else->when(destination){
                                0->HomeScreen(state,padding,vm,{form=Form.Balance()},{destination=3},onEntry,{form=Form.Entry()},{destination=1},{deleteEntryId=it.id},onRefund)
                                1->RecordsScreen(state,padding,onEntry,{deleteEntryId=it.id},onRefund)
                                2->StatisticsScreen(state,padding,onEntry,{deleteEntryId=it.id},onRefund)
                                3->FullCashflowPage(0,state,padding,vm){id->selectedPlanId=state.plans.firstOrNull{it.sequence==id}?.id}
                                4->FullCashflowPage(1,state,padding,vm){id->selectedPlanId=state.plans.firstOrNull{it.sequence==id}?.id}
                                else->ManageScreen(state,padding,vm,{extra=it},{export.launch("记账与现金流-${LedgerCalendar.format(LocalClock.today())}.json")},{restore.launch(arrayOf("application/json","text/plain","application/octet-stream"))},{exportPrevious.launch("操作前备份-${LedgerCalendar.format(LocalClock.today())}.json")})
                            }
                        }
                    }
                }
                if (vm.busy && state != null) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).statusBarsPadding().fillMaxWidth())
                vm.notice?.let { message ->
                    Text(message,Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top=58.dp,start=16.dp,end=16.dp).background(Ink,RoundedCornerShape(8.dp)).padding(horizontal=14.dp,vertical=9.dp),color=Color.White,style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        if(state!=null)refundEntryId?.let{id->RefundEntryDialog(state,id,vm){refundEntryId=null}}
        if(state!=null)deleteEntryId?.let{id->EntryDeleteDialog(state,id,vm){deleteEntryId=null}}
        state?.plans?.firstOrNull { it.id == selectedPlanId }?.let { plan ->
            OccurrencesDialog(state,plan,vm,{selectedPlanId=null},{selectedPlanId=null;form=Form.Plan(plan.id)})
        }
        vm.importPreview?.let{ImportPreviewDialog(it,vm)}
        if(state!=null)vm.cashflowPreview?.let{CashflowMigrationDialog(it,state,vm)}
        vm.restoreCandidate?.let { candidate ->
            AlertDialog(onDismissRequest = { if (!vm.busy) vm.cancelRestore() }, title = { Text("备份已验证，确认替换？") }, text = {
                Text("${candidate.entries.size} 条实际账目、${candidate.accounts.size} 个账户、${candidate.plans.size} 个计划、${candidate.corrections.size} 次校正、${candidate.categories.size} 个分类、${candidate.debts.size} 笔债务、${candidate.budgets.size} 个预算。\n整体余额 ¥${Money.format(LedgerEngine.balances(candidate).overallMinor)}。\n\n将替换当前账本，并先保留恢复前原文件。确认前不会写入。")
            }, confirmButton = { TextButton(onClick = vm::confirmRestore, enabled = !vm.busy) { Text(if (vm.busy) "恢复中…" else "确认恢复") } }, dismissButton = { TextButton(onClick = vm::cancelRestore, enabled = !vm.busy) { Text("取消") } })
        }
    }
}
