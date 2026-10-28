package com.qiye.ledgerflow.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qiye.ledgerflow.core.*

data class ModuleDocument(val mime:String,val name:String)
private class ModuleCreateDocument:ActivityResultContract<ModuleDocument,Uri?>(){
    override fun createIntent(context:Context,input:ModuleDocument)=Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.mime).putExtra(Intent.EXTRA_TITLE,input.name)
    override fun parseResult(resultCode:Int,intent:Intent?):Uri?=if(resultCode==android.app.Activity.RESULT_OK)intent?.data else null
}
@Composable fun DataManagementScreen(state:LedgerState,padding:PaddingValues,vm:LedgerViewModel,onBack:()->Unit){
    var format by remember{mutableStateOf<DataModule?>(null)};var importing by remember{mutableStateOf<DataModule?>(null)};var clearing by remember{mutableStateOf<DataModule?>(null)};var clearCategories by remember{mutableStateOf(false)}
    var pendingModule by rememberSaveable{mutableStateOf("BOOK")}
    val nativeImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){it?.let{uri->vm.previewModuleImport(uri,DataModule.valueOf(pendingModule))}}
    val tableImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){it?.let(vm::previewImport)}
    val legacyImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){it?.let(vm::previewCashflowImport)}
    val save=rememberLauncherForActivityResult(ModuleCreateDocument()){it?.let(vm::writeModuleExport)}
    LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(14.dp,0.dp,14.dp,24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{SectionTitle("数据管理","‹ 管理",onBack)}
        item{Text("分别管理记账和现金流。清空或替换前自动保存完整备份。",fontSize=11.sp,color=Muted)}
        DataModule.entries.forEach{module->item{PanelBox{
            Text(if(module==DataModule.BOOK)"记账数据"else "现金流数据",fontSize=15.sp)
            Text(if(module==DataModule.BOOK)"${state.entries.size}条账目 · ${state.debts.size}笔债务 · ${state.categories.size}个分类"else "${state.accounts.count{!it.archived}}个账户 · ${state.plans.size}个计划 · ${state.budgets.size}个预算",fontSize=11.sp,color=Muted)
            Row{TextButton({importing=module},enabled=!vm.busy){Text("导入")};TextButton({format=module},enabled=!vm.busy){Text("导出")};TextButton({clearCategories=false;clearing=module},enabled=!vm.busy){Text("清空",color=ExpenseColor)}}
            Text(if(module==DataModule.BOOK)"清空账目与债务，默认保留分类；余额、预算和已兑现现金流固定保留。"else "清空资金/计划/预算/持仓与显示设置；账目、分类、债务历史保留，旧账户仍在历史账目中可见。",fontSize=10.sp,color=Muted)
        }}}
    }
    format?.let{module->AlertDialog(onDismissRequest={format=null},title={Text("选择导出格式")},text={Column{
        TextButton({format=null;vm.prepareModuleExport(module,"NATIVE")}){Text("本应用备份 · 完整保存")}
        if(module==DataModule.BOOK){TextButton({format=null;vm.prepareModuleExport(module,"XLSX")}){Text("22列XLSX · 互通推荐")};TextButton({format=null;vm.prepareModuleExport(module,"CSV")}){Text("22列CSV")}}
        else TextButton({format=null;vm.prepareModuleExport(module,"V6")}){Text("啥时候有钱JSON · v6兼容")}
        Text("其他软件无法接收的内容会先提示。完整保存请选本应用备份。",fontSize=11.sp)
    }},confirmButton={TextButton({format=null}){Text("取消")}})}
    importing?.let{module->AlertDialog(onDismissRequest={importing=null},title={Text("选择导入方式")},text={Column{
        TextButton({pendingModule=module.name;importing=null;nativeImport.launch(arrayOf("application/json","text/plain","application/octet-stream"))}){Text("本应用备份 · 替换所选数据")}
        if(module==DataModule.BOOK)TextButton({importing=null;tableImport.launch(arrayOf("*/*"))}){Text("CSV / XLSX · 合并历史账目预览")}
        else TextButton({importing=null;legacyImport.launch(arrayOf("application/json","text/plain","application/octet-stream"))}){Text("啥时候有钱v6 JSON · 合并预览")}
    }},confirmButton={TextButton({importing=null}){Text("取消")}})}
    vm.moduleExport?.let{export->AlertDialog(onDismissRequest=vm::cancelModuleExport,title={Text("导出预览")},text={Text(export.description)},confirmButton={TextButton({save.launch(ModuleDocument(export.mime,"记账与现金流-模块-${LedgerCalendar.format(LocalClock.today())}.${export.extension}"))},enabled=!vm.busy){Text("选择保存位置")}},dismissButton={TextButton(vm::cancelModuleExport,enabled=!vm.busy){Text("取消")}})}
    vm.moduleImportPreview?.let{p->AlertDialog(onDismissRequest=vm::cancelModuleImport,title={Text("替换${if(p.module==DataModule.BOOK)"记账"else "现金流"}数据？")},text={Text(if(p.module==DataModule.BOOK)"将恢复${p.data.entries.size}条账目、${p.data.debts.size}笔债务、${p.data.categories.size}个分类。当前资金、预算和兑现状态保留；导入账目作为历史，不重扣资金。将先保存完整保护副本。"else "将恢复${p.data.plans.size}个计划、${p.data.budgets.size}个预算和资金状态。当前账目、分类、债务历史保留；原账户和计划名称保留在历史记录中。将先保存完整保护副本。")},confirmButton={TextButton(vm::confirmModuleImport,enabled=!vm.busy){Text("确认替换")}},dismissButton={TextButton(vm::cancelModuleImport,enabled=!vm.busy){Text("取消")}})}
    clearing?.let{module->AlertDialog(onDismissRequest={if(!vm.busy)clearing=null},title={Text("清空${if(module==DataModule.BOOK)"记账数据"else "现金流数据"}？")},text={Column{
        Text(if(module==DataModule.BOOK)"删除${state.entries.size}条账目、${state.debts.size}笔债务与记账导入收据。当前整体及账户余额、预算剩余和已发生计划保留，不重复扣款。"else "资金归零、删除计划/预算/持仓/显示与来源；账目、分类、债务本金/利息/时间备注保留。旧账户保留在历史记录中，修改旧账不会改变清空后的余额。")
        if(module==DataModule.BOOK)Toggle("同时清除分类（默认保留）",clearCategories){clearCategories=it}
        Text("确认后先自动保存完整备份，再清空。也可先取消并导出模块/全量备份。",fontSize=11.sp,color=Muted)
    }},confirmButton={TextButton(enabled=!vm.busy,onClick={vm.migrate("所选模块已清空，另一模块已保留",{clearing=null}){if(module==DataModule.BOOK)DataModules.clearBook(it,LocalClock.today(),clearCategories)else DataModules.clearFlow(it)}}){Text("确认清空",color=ExpenseColor)}},dismissButton={TextButton({clearing=null},enabled=!vm.busy){Text("取消")}})}
}
