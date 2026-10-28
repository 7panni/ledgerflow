package com.qiye.ledgerflow.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.qiye.ledgerflow.core.*
import kotlin.math.roundToInt

internal fun entryCanDelete(entry:Entry)=entry.debtId==null||entry.kind !in listOf(EntryKind.BORROW,EntryKind.LEND)
internal fun deleteActualEntry(state:LedgerState,id:String):LedgerState {
    val entry=state.entries.firstOrNull{it.id==id}?:error("记录不存在")
    return entry.settlementId?.let{RichLedger.undoSettlement(state,it)}?:LedgerEngine.deleteEntry(state,id)
}

@Composable internal fun EntryDeleteDialog(state:LedgerState,id:String,vm:LedgerViewModel,onDeleted:()->Unit={},onDismiss:()->Unit) {
    val entry=state.entries.firstOrNull{it.id==id}
    if(entry==null){LaunchedEffect(id){onDismiss()};return}
    AlertDialog(onDismissRequest={if(!vm.busy)onDismiss()},title={Text("确认删除/撤销？")},text={Text(
        if(!entryCanDelete(entry))"建立本金请在债务管理中删除整笔债务"
        else if(entry.settlementId!=null)"将同时撤销这一笔还款/收款的本金和利息，并恢复未结本金。"
        else "余额按保留的校正和其他账目重新计算；关联计划期次将恢复待发生。")},
        confirmButton={TextButton(enabled=!vm.busy&&entryCanDelete(entry),onClick={vm.change("记录已撤销",{onDismiss();onDeleted()}){deleteActualEntry(it,id)}}){Text("确认")}},
        dismissButton={TextButton(enabled=!vm.busy,onClick=onDismiss){Text("保留")}})
}

/** Horizontal slop selects this drag; vertical drags remain with the scrolling parent. */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun EntryActionRow(id:String,canDelete:Boolean,onEdit:()->Unit,onDelete:(()->Unit)?,content:@Composable (Modifier)->Unit) {
    var menu by remember(id){mutableStateOf(false)}
    var offset by remember(id){mutableFloatStateOf(0f)}
    val reveal=with(LocalDensity.current){88.dp.toPx()}
    Box(Modifier.fillMaxWidth().clipToBounds()){
        if(offset<0f&&onDelete!=null)Box(Modifier.matchParentSize().background(ExpenseColor),contentAlignment=Alignment.CenterEnd){
            TextButton(onClick={offset=0f;onDelete()},enabled=canDelete,modifier=Modifier.width(88.dp).fillMaxHeight()){Text("删除",color=Paper)}
        }
        val gesture=Modifier.offset{IntOffset(offset.roundToInt(),0)}.background(Panel)
            .draggable(state=rememberDraggableState{delta->offset=(offset+delta).coerceIn(-reveal,0f)},orientation=Orientation.Horizontal,
                enabled=onDelete!=null&&canDelete,onDragStarted={menu=false},onDragStopped={velocity->offset=if(offset < -reveal/2||velocity < -500f)-reveal else 0f})
            .combinedClickable(onClick={offset=0f;onEdit()},onLongClick=if(onDelete!=null)({menu=true})else null)
        content(gesture)
        DropdownMenu(menu,{menu=false}){
            DropdownMenuItem({Text("编辑")},{menu=false;offset=0f;onEdit()})
            DropdownMenuItem({Text("删除")},{menu=false;offset=0f;onDelete?.invoke()},enabled=canDelete)
        }
    }
}

@Composable internal fun ActualEntryCard(state:LedgerState,entries:List<Entry>,onDelete:(Entry)->Unit,onEntry:(Entry)->Unit) {
    val byId=remember(entries){entries.associateBy{it.id}}
    UpcomingEventsCard(entries.map{it.toBill(state)},onDelete={id->byId[id]?.let(onDelete)},canDelete={id->byId[id]?.let(::entryCanDelete)==true}){id->byId[id]?.let(onEntry)}
}
