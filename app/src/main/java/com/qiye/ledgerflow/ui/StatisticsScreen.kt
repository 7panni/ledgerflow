package com.qiye.ledgerflow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qiye.ledgerflow.core.*
import java.util.Locale

private val categoryColors=listOf(Color(0xFF173F34),Color(0xFF6F9B83),Color(0xFFBA8C54),Color(0xFFA64C41),Color(0xFF6C8397),Color(0xFF91806A),Color(0xFF5D7971),Color(0xFFB4A765),Color(0xFF937493),Color(0xFF7F955F),Color(0xFFBA8273),Color(0xFF607B92))
private fun percent(value:Long,total:Long)=if(value>=0&&total>0)String.format(Locale.ROOT,"%.1f%%",value.toDouble()/total*100)else "—"

@Composable fun StatisticsScreen(state:LedgerState,padding:PaddingValues,onEntry:(Entry)->Unit,onDelete:(Entry)->Unit,onRefund:(Entry)->Unit){
    var period by rememberSaveable{mutableStateOf(StatisticsPeriod.MONTH)}
    var offset by rememberSaveable{mutableIntStateOf(0)}
    var mode by rememberSaveable{mutableStateOf(EntryKind.EXPENSE)}
    var drill by remember{mutableStateOf<StatisticsRow?>(null)}
    val window=StatisticsData.window(LocalClock.today(),state.settings.monthStartDay,period,offset)
    val selected=remember(state,window.range){StatisticsData.entries(state,window.range)}
    val summary=runCatching{LedgerEngine.periodSummary(state,window.range)}
    val rows=remember(selected,mode){StatisticsData.rows(selected,mode)}
    val total=rows.fold(0L){s,r->Money.add(s,r.amountMinor)}
    val pieTotal=rows.filter{it.amountMinor>0}.fold(0L){s,r->Money.add(s,r.amountMinor)}
    LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),contentPadding=PaddingValues(14.dp,0.dp,14.dp,24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            StatisticsPeriod.entries.forEach{p->FilterChip(period==p,{period=p;offset=0},{Text(p.label)})}
        }}
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
            TextButton({offset--},enabled=window.canPrevious){Text("‹ 上期")}
            Text(window.label,fontWeight=FontWeight.SemiBold,fontSize=15.sp)
            TextButton({offset++},enabled=window.canNext){Text("下期 ›")}
        }}
        item{Text("${LedgerCalendar.format(window.range.first)} — ${LedgerCalendar.format(window.range.last)} · 人民币",fontSize=10.sp,color=Muted)}
        item{PanelBox{summary.fold(onSuccess={s->Row(Modifier.fillMaxWidth()){
            StatisticsAmount("收入",s.incomeMinor,Modifier.weight(1f));StatisticsAmount("支出",s.expenseMinor,Modifier.weight(1f));StatisticsAmount("结余",s.netMinor,Modifier.weight(1f))
        }},onFailure={Text(it.message.orEmpty(),color=ExpenseColor)})}}
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(mode==EntryKind.EXPENSE,{mode=EntryKind.EXPENSE},{Text("支出")})
            FilterChip(mode==EntryKind.INCOME,{mode=EntryKind.INCOME},{Text("收入")})
        }}
        item{PageSectionHeader(if(mode==EntryKind.EXPENSE)"分类支出"else "分类收入")}
        if(rows.isEmpty())item{EmptyHint(if(mode==EntryKind.EXPENSE)"本期暂无支出。"else "本期暂无收入。")}
        else {
            item{Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){
                if(pieTotal>0)CategoryPie(rows,pieTotal)else Box(Modifier.size(170.dp).background(Line.copy(alpha=.45f),CircleShape),contentAlignment=Alignment.Center){Text("暂无可绘制金额",fontSize=12.sp,color=Muted)}
                Text("${cashMoney(total)} · ${rows.sumOf{it.entries.size}}笔",fontWeight=FontWeight.Bold,fontSize=15.sp)
            }}
            if(rows.any{it.amountMinor<0})item{Text("退款已冲减支出；负值分类保留明细，不参与饼图占比。",fontSize=10.sp,color=Muted)}
            item{Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=3.dp)){
                Text("分类",Modifier.weight(1f),fontSize=10.sp,color=Muted)
                Text("金额",Modifier.width(90.dp),textAlign=TextAlign.End,fontSize=10.sp,color=Muted)
                Text("占比",Modifier.width(52.dp),textAlign=TextAlign.End,fontSize=10.sp,color=Muted)
                Text("笔数",Modifier.width(36.dp),textAlign=TextAlign.End,fontSize=10.sp,color=Muted)
            }}
            itemsIndexed(rows,key={_,r->r.category to r.subcategory}){index,row->Column{
                Row(Modifier.fillMaxWidth().background(Panel).clickable{drill=row}.padding(horizontal=10.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.size(8.dp).background(categoryColors[index%categoryColors.size],CircleShape));Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)){
                        Text(row.category.ifBlank{"未分类"},fontSize=12.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                        if(row.subcategory.isNotBlank())Text(row.subcategory,fontSize=10.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    Text(cashMoney(row.amountMinor),Modifier.width(90.dp),fontSize=12.sp,textAlign=TextAlign.End,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(percent(row.amountMinor,pieTotal),Modifier.width(52.dp),fontSize=10.sp,color=Muted,textAlign=TextAlign.End)
                    Text(row.entries.size.toString(),Modifier.width(36.dp),fontSize=10.sp,color=Muted,textAlign=TextAlign.End)
                }
                HorizontalDivider(color=Line,thickness=.5.dp)
            }}
        }
        item{Text("退款冲减支出；转账、债务本金和余额调整不计入收支。",fontSize=10.sp,color=Muted)}
    }
    drill?.let{row->AlertDialog(onDismissRequest={drill=null},title={Text(row.category+if(row.subcategory.isNotBlank())" / ${row.subcategory}"else "")},text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState())){
        row.entries.sortedWith(compareByDescending<Entry>{it.epochDay}.thenByDescending{it.minuteOfDay}.thenByDescending{it.second}).forEach{e->
            key(e.id){EntryActionRow(e.id,entryCanDelete(e),{drill=null;onEntry(e)},{drill=null;onDelete(e)},
                onRefund=if(e.kind==EntryKind.EXPENSE)({onRefund(e)})else null){rowModifier->Column(rowModifier.fillMaxWidth().padding(vertical=10.dp)){
                Text("${LedgerCalendar.format(e.epochDay)} ${LocalClock.timeText(e.minuteOfDay)}",fontSize=10.sp,color=Muted)
                Text("${e.kind.label()} ${nativeMoney(e.amountMinor,e.currencyCode)}",fontSize=13.sp)
                e.billNote(state).takeIf { it.isNotBlank() }?.let { Text(it,fontSize=11.sp,color=Muted) }
            }}}
        }
    }},confirmButton={TextButton({drill=null}){Text("关闭")}})}
}
@Composable private fun StatisticsAmount(label:String,value:Long,modifier:Modifier)=Column(modifier,verticalArrangement=Arrangement.spacedBy(5.dp)){
    Text(label,fontSize=10.sp,color=Muted);Text(cashMoney(value),fontSize=14.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
}
@Composable private fun CategoryPie(rows:List<StatisticsRow>,total:Long){
    val description=rows.filter{it.amountMinor>0}.joinToString{it.category+" "+percent(it.amountMinor,total)}
    Canvas(Modifier.size(184.dp).semantics{contentDescription="分类饼图：$description"}){
        var start=-90f
        rows.forEachIndexed{index,row->if(row.amountMinor>0){
            val sweep=(row.amountMinor.toDouble()/total*360).toFloat()
            drawArc(categoryColors[index%categoryColors.size],start,if(sweep<360f)(sweep-.6f).coerceAtLeast(.05f)else sweep,true)
            start+=sweep
        }}
    }
}
