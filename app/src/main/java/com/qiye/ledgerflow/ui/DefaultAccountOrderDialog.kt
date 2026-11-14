package com.qiye.ledgerflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qiye.ledgerflow.core.*

@Composable internal fun DefaultAccountOrderDialog(state:LedgerState,vm:LedgerViewModel,onClose:()->Unit) {
    var order by remember{mutableStateOf(orderedQuickEntryAccounts(state.accounts,state.settings.defaultAccountOrder).map{it.id})}
    val accounts=orderedQuickEntryAccounts(state.accounts,order)
    fun move(index:Int,step:Int){
        val ids=accounts.map{it.id}.toMutableList();val target=index+step
        if(target in ids.indices){val id=ids.removeAt(index);ids.add(target,id);order=ids}
    }
    AlertDialog(onDismissRequest={if(!vm.busy)onClose()},title={Text("默认账户顺序")},text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text("按顺序选择余额不为0的同币账户；都为0时使用第一项。",fontSize=12.sp,color=Muted)
            if(accounts.isEmpty())Text("暂无可用账户，请先添加账户。",fontSize=12.sp)
            accounts.forEachIndexed{index,account->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text("${account.name} · ${account.currencyCode}",Modifier.weight(1f),fontSize=12.sp)
                TextButton({move(index,-1)},enabled=!vm.busy&&index>0,contentPadding=PaddingValues(horizontal=6.dp)){Text("上移",fontSize=11.sp)}
                TextButton({move(index,1)},enabled=!vm.busy&&index<accounts.lastIndex,contentPadding=PaddingValues(horizontal=6.dp)){Text("下移",fontSize=11.sp)}
            }}
        }
    },confirmButton={TextButton(enabled=!vm.busy,onClick={val ids=accounts.map{it.id};vm.change("默认账户顺序已保存",onClose){it.copy(settings=it.settings.copy(defaultAccountOrder=ids))}}){Text("保存")}},
      dismissButton={TextButton(enabled=!vm.busy,onClick=onClose){Text("取消")}})
}
