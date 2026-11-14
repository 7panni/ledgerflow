package com.qiye.ledgerflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.importer.*

@Composable fun Toggle(label:String,checked:Boolean,onChange:(Boolean)->Unit)=Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f),fontSize=12.sp);Switch(checked,onChange)}
@Composable fun CategoryPicker(state:LedgerState,kind:EntryKind,selected:String?,onSelect:(LedgerCategory)->Unit){
    var open by remember{mutableStateOf(false)};val options=state.categories.filter{it.kind==RichLedger.categoryKind(kind)&&(it.active||it.id==selected)}.sortedWith(compareBy<LedgerCategory>{it.parentId?:it.id}.thenBy{it.order})
    if(options.isNotEmpty())Box{OutlinedButton({open=true},Modifier.fillMaxWidth(),shape=RoundedCornerShape(0.dp)){Text(selected?.let{RichLedger.categoryLabels(state,it)?.let{(a,b)->a+if(b.isNotBlank())" / $b" else ""}}?:"选择已有分类")};DropdownMenu(open,{open=false}){options.forEach{c->DropdownMenuItem({Text(RichLedger.categoryLabels(state,c.id)!!.let{(a,b)->a+if(b.isNotBlank())" / $b" else ""})},{onSelect(c);open=false})}}}
}
@Composable fun BudgetPicker(state:LedgerState,selected:Long?,onSelect:(Long?)->Unit){
    var open by remember{mutableStateOf(false)};val options=state.budgets.filter{it.isActive||it.id==selected}
    if(options.isNotEmpty())Box{OutlinedButton({open=true},Modifier.fillMaxWidth(),shape=RoundedCornerShape(0.dp)){Text(selected?.let{id->options.firstOrNull{it.id==id}?.name}?:if(options.size>1)"未指定预算（不会抵扣多预算）" else "未指定预算")};DropdownMenu(open,{open=false}){DropdownMenuItem({Text("不指定（单预算自动匹配）")},{onSelect(null);open=false});options.forEach{b->DropdownMenuItem({Text(b.name)},{onSelect(b.id);open=false})}}}
}
@Composable fun RecordsScreen(state:LedgerState,padding:PaddingValues,onEntry:(Entry)->Unit,onDelete:(Entry)->Unit,onRefund:(Entry)->Unit){
    var query by rememberSaveable{mutableStateOf("")};var kind by rememberSaveable{mutableStateOf<EntryKind?>(null)}
    val selected=remember(state,query,kind){state.entries.filter{e->(kind==null||e.kind==kind)&& (query.isBlank()||listOf(e.category,e.subcategory,e.billNote(state),e.tags,state.accountName(e.accountId),LedgerCalendar.format(e.epochDay),e.kind.label(),CurrencyUnits.format(e.amountMinor,e.currencyCode)).any{it.contains(query,true)})}.sortedWith(compareByDescending<Entry>{it.epochDay}.thenByDescending{it.minuteOfDay}.thenByDescending{it.second}.thenByDescending{it.sequence})}
    LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),contentPadding=PaddingValues(14.dp,0.dp,14.dp,86.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
        item{OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("搜索分类、备注、标签、账户、日期或金额")},singleLine=true)}
        item{Row(Modifier.horizontalScroll(rememberScrollState())){FilterChip(kind==null,{kind=null},{Text("全部")});listOf(EntryKind.EXPENSE,EntryKind.INCOME,EntryKind.TRANSFER,EntryKind.REFUND).forEach{v->FilterChip(kind==v,{kind=v},{Text(v.label(),fontSize=11.sp)})}}}
        item{Text("共 ${selected.size} 条 · 其他币种按原币显示",fontSize=10.sp,color=Muted)}
        if(selected.isEmpty())item{EmptyHint("没有匹配的实际账目。")}
        selected.groupBy{it.epochDay}.forEach{(day,entries)->item(key=day){Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text(LedgerCalendar.format(day),fontSize=10.sp,color=Muted);ActualEntryCard(state,entries,onDelete,onEntry,onRefund)}}}
    }
}
@Composable fun CategoriesScreen(state:LedgerState,padding:PaddingValues,vm:LedgerViewModel,onBack:()->Unit){
    var editId by remember{mutableStateOf<String?>(null)};var editing by remember{mutableStateOf(false)};var name by remember{mutableStateOf("")};var kind by remember{mutableStateOf(EntryKind.EXPENSE)};var parent by remember{mutableStateOf<String?>(null)};var active by remember{mutableStateOf(true)};var error by remember{mutableStateOf<String?>(null)}
    var listKind by rememberSaveable{mutableStateOf(EntryKind.EXPENSE)}
    var menuId by remember{mutableStateOf<String?>(null)}
    fun edit(c:LedgerCategory){editId=c.id;name=c.name;kind=c.kind;parent=c.parentId;active=c.active;error=null;editing=true}
    val ordered=state.categories.filter{it.parentId==null&&it.kind==listKind}.sortedBy{it.order}.flatMap{p->listOf(p)+state.categories.filter{it.parentId==p.id}.sortedBy{it.order}}
    LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(14.dp,0.dp,14.dp,24.dp),verticalArrangement=Arrangement.spacedBy(0.dp)){
        item{SectionTitle("分类管理","‹ 管理",onBack)}
        item{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf(EntryKind.EXPENSE,EntryKind.INCOME).forEach{v->FilterChip(listKind==v,{listKind=v},{Text(v.label())})}
            Spacer(Modifier.weight(1f));TextButton({editId=null;name="";kind=listKind;parent=null;active=true;error=null;editing=true}){Text("＋ 新增")}
        }}
        if(ordered.isEmpty())item{EmptyHint("暂无${listKind.label()}分类，点新增添加。")}
        items(ordered,key={it.id}){c->
            Row(Modifier.fillMaxWidth().background(Panel).clickable{edit(c)}.padding(start=if(c.parentId==null)12.dp else 28.dp).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){
                Text((if(c.parentId==null)"" else "↳ ")+c.name,Modifier.weight(1f),fontWeight=if(c.parentId==null)FontWeight.SemiBold else FontWeight.Normal,fontSize=13.sp)
                if(!c.active)Text("已停用",fontSize=10.sp,color=Muted)
                Box{TextButton({menuId=c.id},contentPadding=PaddingValues(0.dp),modifier=Modifier.width(44.dp)){Text("⋮",fontSize=19.sp)}
                    DropdownMenu(menuId==c.id,{menuId=null}){
                        DropdownMenuItem({Text("编辑")},{menuId=null;edit(c)})
                        DropdownMenuItem({Text("上移")},{menuId=null;vm.change("排序已更新"){RichLedger.moveCategory(it,c.id,-1)}})
                        DropdownMenuItem({Text("下移")},{menuId=null;vm.change("排序已更新"){RichLedger.moveCategory(it,c.id,1)}})
                        DropdownMenuItem({Text("删除",color=ExpenseColor)},{menuId=null;vm.change("分类已删除"){RichLedger.deleteCategory(it,c.id)}})
                    }
                }
            }
            HorizontalDivider(color=Line,thickness=.5.dp)
        }
        item{Text("点分类编辑；右侧菜单排序或删除。在用分类保留旧账关系。",Modifier.padding(top=12.dp),fontSize=10.sp,color=Muted)}
    }
    if(editing)AlertDialog(onDismissRequest={if(!vm.busy)editing=false},title={Text(if(editId==null)"新增分类" else "编辑分类")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        OutlinedTextField(name,{name=it},label={Text("名称")},singleLine=true)
        Row{listOf(EntryKind.EXPENSE,EntryKind.INCOME).forEach{v->FilterChip(kind==v,{kind=v;parent=null},{Text(v.label())})}}
        var open by remember{mutableStateOf(false)};Box{OutlinedButton({open=true}){Text(parent?.let{id->state.categories.firstOrNull{it.id==id}?.name}?:"父分类（不选则为一级）")};DropdownMenu(open,{open=false}){DropdownMenuItem({Text("作为一级分类")},{parent=null;open=false});state.categories.filter{it.parentId==null&&it.kind==kind&&it.id!=editId}.forEach{p->DropdownMenuItem({Text(p.name)},{parent=p.id;open=false})}}}
        Toggle("启用",active){active=it};error?.let{Text(it,color=ExpenseColor)}
    }},confirmButton={TextButton(enabled=!vm.busy,onClick={try{RichLedger.saveCategory(state,name,kind,parent,editId,active);vm.change("分类已保存",{editing=false}){RichLedger.saveCategory(it,name,kind,parent,editId,active)}}catch(e:Exception){error=e.message}}){Text("保存")}},dismissButton={TextButton({editing=false}){Text("取消")}})
}
@Composable fun PanelBox(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit)=Column(modifier.fillMaxWidth().background(Panel).border(1.dp,Line).padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp),content=content)

@Composable fun OccurrencesDialog(state:LedgerState,plan:Plan,vm:LedgerViewModel,onClose:()->Unit,onEdit:()->Unit){
    var limit by remember(plan.id){mutableIntStateOf(60)};var picked by remember{mutableStateOf<PlanOccurrence?>(null)};var mode by remember{mutableStateOf("")};var date by remember{mutableStateOf("")};var time by remember{mutableStateOf(LocalClock.timeText(LocalClock.minute()))};var error by remember{mutableStateOf<String?>(null)}
    val today=LocalClock.today();val occurrences=remember(state,plan){RichLedger.occurrences(state,plan,today+366)}
    AlertDialog(onDismissRequest={if(!vm.busy)onClose()},title={Text(plan.title)},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("确认、撤销或延期只影响所选这一期。",fontSize=11.sp,color=Muted)
        if(plan.postingFromEpochDay!=null)Text("历史不补扣：从 ${LedgerCalendar.format(plan.postingFromEpochDay!!)} 起。",fontSize=10.sp,color=Muted)
        Button(onEdit,enabled=!vm.busy,shape=RoundedCornerShape(0.dp)){Text("编辑计划")}
        TextButton(enabled=!vm.busy,onClick={vm.change("计划状态已更新"){s->s.copy(plans=s.plans.map{if(it.id==plan.id)it.copy(active=!it.active)else it})}}){Text(if(plan.active)"停用计划" else "启用计划")}
        TextButton(enabled=!vm.busy,onClick={vm.change("计划已删除",onClose){LedgerEngine.deletePlan(it,plan.id)}}){Text("删除计划",color=ExpenseColor)}
        if(occurrences.isEmpty())Text("此区间没有有效期次。")
        occurrences.take(limit).forEach{o->Column(Modifier.fillMaxWidth().border(.5.dp,Line).padding(8.dp)){
            Text("${LedgerCalendar.format(o.dueEpochDay)} · ${nativeMoney(o.amountMinor,plan.currencyCode)}",fontWeight=FontWeight.Bold,fontSize=12.sp)
            if(o.originalEpochDay!=o.dueEpochDay)Text("原期次 ${LedgerCalendar.format(o.originalEpochDay)}",fontSize=10.sp,color=Muted)
            if(o.settled){Text("已发生 · 原账目已清除，不会重复入账",fontSize=11.sp)}
            else if(o.actual!=null){Text("已在 ${LedgerCalendar.format(o.actual!!.epochDay)} 实际发生",fontSize=11.sp);TextButton(enabled=!vm.busy,onClick={vm.change("此期已撤销"){RichLedger.undoOccurrence(it,plan.id,o.originalEpochDay)}}){Text("撤销此期实际记录")}}
            else Row{TextButton({picked=o;mode="confirm";date=LedgerCalendar.format(today);error=null},enabled=!vm.busy){Text("确认实际发生")};TextButton({picked=o;mode="postpone";date=LedgerCalendar.format(maxOf(today,o.dueEpochDay)+1);error=null},enabled=!vm.busy){Text("延期")}}
        }}
        if(occurrences.size>limit)TextButton({limit+=60}){Text("再显示60期（共${occurrences.size}期）")}
    }},confirmButton={TextButton(onClose){Text("关闭")}})
    picked?.let{o->AlertDialog(onDismissRequest={if(!vm.busy)picked=null},title={Text(if(mode=="confirm")"确认此期实际发生" else "延期此期")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("原期次 ${LedgerCalendar.format(o.originalEpochDay)} · ${nativeMoney(o.amountMinor,plan.currencyCode)}");OutlinedTextField(date,{date=it},label={Text(if(mode=="confirm")"实际日期 YYYY-MM-DD" else "新预计日期 YYYY-MM-DD")});if(mode=="confirm")OutlinedTextField(time,{time=it},label={Text("时间 HH:MM")});error?.let{Text(it,color=ExpenseColor)}}},confirmButton={TextButton(enabled=!vm.busy,onClick={try{val day=LedgerCalendar.parse(date);val minute=LocalClock.parseTime(time);val fn:(LedgerState)->LedgerState={s->if(mode=="confirm")RichLedger.confirmOccurrence(s,plan.id,o.originalEpochDay,day,minute,today)else RichLedger.postponeOccurrence(s,plan.id,o.originalEpochDay,day)};fn(state);vm.change("期次已保存",{picked=null},fn)}catch(e:Exception){error=e.message}}){Text("确认")}},dismissButton={TextButton({picked=null}){Text("取消")}})}
}

@Composable fun ImportPreviewDialog(preview:ImportPreview,vm:LedgerViewModel){
    val sources=preview.ready.flatMap{listOfNotNull(it.accountName,it.targetAccountName)}.distinct()
    var mapping by remember(preview){mutableStateOf(sources.mapNotNull{n->vm.state?.accounts?.firstOrNull{it.name==n&&AccountManagement.selectable(it)&&it.currencyCode=="CNY"}?.let{n to it.id}}.toMap<String,String?>())}
    var balances by remember{mutableStateOf(false)};var limit by remember{mutableIntStateOf(100)}
    AlertDialog(onDismissRequest={if(!vm.busy)vm.cancelImport()},title={Text("表格导入预览")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("可导入 ${preview.count(ImportStatus.READY)} · 重复 ${preview.count(ImportStatus.DUPLICATE)} · 错误 ${preview.count(ImportStatus.ERROR)} · 不支持 ${preview.count(ImportStatus.UNSUPPORTED)}",fontWeight=FontWeight.Bold)
        preview.notices.forEach{Text(it,fontSize=11.sp,color=Muted)}
        Text("将新建${preview.newCategories.size}个分类/子分类；同名同层级复用，收入/支出分别管理。",fontSize=11.sp)
        preview.newCategories.forEach{Text(it,fontSize=10.sp,color=Muted)}
        Toggle("也改变当前余额/预算（历史迁移默认关闭）",balances){balances=it}
        if(balances)sources.forEach{name->var open by remember{mutableStateOf(false)};Box{OutlinedButton({open=true}){Text("$name → "+if(mapping.containsKey(name))vm.state?.accountName(mapping[name])else "请选择资金账户")};DropdownMenu(open,{open=false}){
            DropdownMenuItem({Text("无账户（仅影响整体余额）")},{mapping=mapping+(name to null);open=false})
            vm.state?.accounts?.filter{AccountManagement.selectable(it)&&it.currencyCode=="CNY"}?.forEach{a->DropdownMenuItem({Text(a.name)},{mapping=mapping+(name to a.id);open=false})}
        }}}
        if(balances)Text("确认后会把这些记录作为现在新增的余额动作，可能与已经校准的当前余额重复。仅明确需要时启用。",color=ExpenseColor,fontSize=11.sp)
        preview.lines.take(limit).forEach{line->PanelBox{Text("第${line.line}行 · ${when(line.status){ImportStatus.READY->"可导入";ImportStatus.DUPLICATE->"重复";ImportStatus.ERROR->"错误";ImportStatus.UNSUPPORTED->"不支持"}}",fontWeight=FontWeight.Bold,fontSize=11.sp);line.record?.let{Text("${LedgerCalendar.format(it.epochDay)} ${it.kind.label()} ${cashMoney(it.amountMinor)} · ${it.category}",fontSize=11.sp)};Text(line.message,fontSize=10.sp,color=if(line.status in listOf(ImportStatus.ERROR,ImportStatus.UNSUPPORTED))ExpenseColor else Muted)}}
        if(preview.lines.size>limit)TextButton({limit+=100}){Text("再显示100行")}
    }},confirmButton={TextButton(enabled=!vm.busy&&preview.ready.isNotEmpty()&&(!balances||sources.all{mapping.containsKey(it)}),onClick={vm.confirmImport(balances,mapping)}){Text(if(vm.busy)"保存中…" else "导入可用记录")}},dismissButton={TextButton(enabled=!vm.busy,onClick=vm::cancelImport){Text("取消")}})
}

@Composable fun DebtsScreen(state:LedgerState,padding:PaddingValues,vm:LedgerViewModel,onBack:()->Unit,onEntry:(Entry)->Unit){
    var selected by remember{mutableStateOf<String?>(null)};var create by remember{mutableStateOf(false)};var settle by remember{mutableStateOf(false)};var edit by remember{mutableStateOf(false)}
    var side by remember{mutableStateOf(DebtKind.BORROWED)};var person by remember{mutableStateOf("")};var amount by remember{mutableStateOf("")};var day by remember{mutableStateOf(LedgerCalendar.format(LocalClock.today()))};var due by remember{mutableStateOf("")};var interest by remember{mutableStateOf("0")};var account by remember{mutableStateOf<String?>(null)};var note by remember{mutableStateOf("")};var error by remember{mutableStateOf<String?>(null)};var request by rememberSaveable{mutableStateOf("")};var budgetId by remember{mutableStateOf<Long?>(null)}
    var expandedPerson by remember{mutableStateOf<String?>(null)}
    var repayGroup by remember{mutableStateOf<String?>(null)}
    val groups=remember(state){DebtGroups.all(state)}
    var showArchived by rememberSaveable{mutableStateOf(false)}
    val people=remember(state){DebtGroups.people(state)}
    val visiblePeople=people.filter{showArchived||!DebtGroups.isPersonArchived(state,it)}
    fun begin(d:Debt?=null){side=d?.kind?:DebtKind.BORROWED;person=d?.person.orEmpty();amount=d?.let{CurrencyUnits.format(it.principalMinor,it.currencyCode)}?:"";day=LedgerCalendar.format(d?.openedEpochDay?:LocalClock.today());due=d?.dueEpochDay?.let(LedgerCalendar::format).orEmpty();interest=d?.let{if(it.interestProvided)CurrencyUnits.format(it.interestMinor,it.currencyCode)else ""}?:"0";account=d?.accountId;note=debtVisibleNote(d?.note.orEmpty());error=null}
    LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(14.dp,0.dp,14.dp,30.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
        item{SectionTitle("借贷","‹ 管理",onBack)}
        item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
            Button({begin();side=DebtKind.BORROWED;create=true},Modifier.weight(1f),shape=RoundedCornerShape(0.dp)){Text("借入")}
            OutlinedButton({begin();side=DebtKind.LENT;create=true},Modifier.weight(1f),shape=RoundedCornerShape(0.dp)){Text("借出")}
        }}
        item{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Spacer(Modifier.weight(1f));TextButton({showArchived=!showArchived}){Text(if(showArchived)"隐藏已归档"else "显示已归档")}
        }}
        if(visiblePeople.isEmpty())item{EmptyHint(if(groups.isEmpty())"暂无借贷记录。"else "已归档的借贷已隐藏。")}
        items(visiblePeople,key={it.key}){personGroup->PanelBox{
            Row(Modifier.fillMaxWidth().clickable{expandedPerson=if(expandedPerson==personGroup.key)null else personGroup.key},verticalAlignment=Alignment.CenterVertically){
                Text(personGroup.name,Modifier.weight(1f),fontSize=15.sp,fontWeight=FontWeight.Bold)
                Text(if(expandedPerson==personGroup.key)"▴"else "▾",fontSize=13.sp,color=Muted)
            }
            if(expandedPerson==personGroup.key){
                val archived=DebtGroups.isPersonArchived(state,personGroup)
                val eligible=DebtGroups.canArchivePerson(state,personGroup)
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    TextButton(enabled=!vm.busy&&(archived||eligible),onClick={vm.change(if(archived)"此人借贷已恢复显示"else "此人借贷已归档"){if(archived)DebtGroups.restorePerson(it,personGroup.key)else DebtGroups.archivePerson(it,personGroup.key)}}){Text(if(archived)"恢复此人"else "归档此人")}
                    if(!archived&&!eligible)Text(if(personGroup.groups.any{it.unmatched.isNotEmpty()})"有待核对"else "尚未结清",fontSize=10.sp,color=Muted)
                }
            personGroup.groups.forEach{g->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text("${g.currencyCode} · ${if(g.kind==DebtKind.BORROWED)"借入"else "借出"}",Modifier.weight(1f),fontSize=14.sp,fontWeight=FontWeight.Bold)
                Text("${g.entries.size}笔",fontSize=10.sp,color=Muted)
            }
            val projects=g.loans
            val shownPrincipal=projects.fold(0L){n,d->Money.add(n,d.principalMinor)}
            val shownRemaining=projects.fold(0L){n,d->Money.add(n,g.remainingByDebt.getValue(d.id))}
            Text("本金 ${nativeMoney(shownPrincipal,g.currencyCode)} · 已还 ${nativeMoney(shownPrincipal-shownRemaining,g.currencyCode)}",fontSize=12.sp)
            Text("未结 ${nativeMoney(shownRemaining,g.currencyCode)}",fontSize=14.sp,fontWeight=FontWeight.Bold)
            projects.forEach{d->Column(Modifier.fillMaxWidth().padding(top=7.dp)){
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f).clickable{selected=d.id}){
                        Text("${LedgerCalendar.format(d.openedEpochDay)} · 本金 ${nativeMoney(d.principalMinor,d.currencyCode)}",fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                        Text("未结 ${nativeMoney(g.remainingByDebt.getValue(d.id),d.currencyCode)} · 查看明细 ›",fontSize=10.sp,color=Muted)
                        if(debtVisibleNote(d.note).isNotBlank())Text(debtVisibleNote(d.note),fontSize=10.sp,color=Muted,maxLines=2)
                    }

                }
                DebtProjectRecords(state,d.id,onEntry)
            }}
            g.unmatched.forEach{u->Text("${LedgerCalendar.format(u.entry.epochDay)}：${u.reason} ${nativeMoney(u.amountMinor,g.currencyCode)}，待核对",fontSize=10.sp,color=ExpenseColor)}
            if(g.key.startsWith("unknown-"))Text("对方未填写，暂不与其他记录合并。",fontSize=10.sp,color=Muted)
            if(g.remainingMinor>0&&!g.key.startsWith("unknown-"))TextButton({repayGroup=g.key;amount=CurrencyUnits.format(g.remainingMinor,g.currencyCode);day=LedgerCalendar.format(LocalClock.today());account=g.loans.firstOrNull()?.accountId?.takeIf{id->state.accounts.any{it.id==id&&AccountManagement.selectable(it)}};note="";request="group-${state.nextSequence}";error=null}){Text(if(g.kind==DebtKind.BORROWED)"归还本金"else "收回本金")}

            }
            }
        }}
    }
    groups.firstOrNull{it.key==repayGroup}?.let{g->AlertDialog(onDismissRequest={repayGroup=null},title={Text(if(g.kind==DebtKind.BORROWED)"归还本金"else "收回本金")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("${g.person} · 未结${nativeMoney(g.remainingMinor,g.currencyCode)}")
        OutlinedTextField(amount,{amount=it},label={Text("本金（${g.currencyCode}）")})
        AccountPicker(state.copy(accounts=state.accounts.filter{it.currencyCode==g.currencyCode}),account,{account=it})
        OutlinedTextField(day,{day=it},label={Text("日期 YYYY-MM-DD")});OutlinedTextField(note,{note=it},label={Text("备注")});error?.let{Text(it,color=ExpenseColor)}
    }},confirmButton={TextButton(enabled=!vm.busy,onClick={try{val value=CurrencyUnits.parse(amount,g.currencyCode);val date=LedgerCalendar.parse(day);val fn:(LedgerState)->LedgerState={DebtGroups.repay(it,g.key,value,date,LocalClock.minute(),account,note,LocalClock.today(),request)};fn(state);vm.change("本金已记录",{repayGroup=null},fn)}catch(e:Exception){error=e.message}}){Text("保存")}},dismissButton={TextButton({repayGroup=null}){Text("取消")}})}
    val debt=state.debts.firstOrNull{it.id==selected}
    if(debt!=null&&!settle&&!edit)AlertDialog(onDismissRequest={selected=null},title={Text(debt.person)},text={Column(Modifier.heightIn(max=470.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("${if(LedgerImport.uncertain(state,debt))"已关联后本金余额"else "未结本金"} ${nativeMoney(RichLedger.outstanding(state,debt.id),debt.currencyCode)}${if(debt.interestProvided)"\n未结利息 ${nativeMoney(RichLedger.interestOutstanding(state,debt.id),debt.currencyCode)}"else ""}",fontWeight=FontWeight.Bold)
        if(LedgerImport.uncertain(state,debt))Text("有日期或金额不符的归还记录，请查看分组中的差额。",fontSize=11.sp,color=ExpenseColor)
        if(debtVisibleNote(debt.note).isNotBlank())Text(debtVisibleNote(debt.note))
        if(RichLedger.outstanding(state,debt.id)>0&&!LedgerImport.uncertain(state,debt))Button({amount=CurrencyUnits.format(RichLedger.outstanding(state,debt.id),debt.currencyCode);interest=CurrencyUnits.format(RichLedger.interestOutstanding(state,debt.id),debt.currencyCode);day=LedgerCalendar.format(LocalClock.today());account=debt.accountId?.takeIf{id->state.accounts.any{it.id==id&&AccountManagement.selectable(it)}};request="settlement-${debt.id}-${state.nextSequence}";budgetId=state.budgets.filter{it.isActive}.singleOrNull()?.id;error=null;settle=true},shape=RoundedCornerShape(0.dp)){Text(if(debt.kind==DebtKind.BORROWED)"部分 / 全部还款" else "部分 / 全部收回")}
        OutlinedButton({begin(debt);edit=true},shape=RoundedCornerShape(0.dp)){Text("编辑对方 / 到期日 / 约定利息")}
        DebtProjectRecords(state,debt.id){selected=null;onEntry(it)}
        DebtGroups.projectEntries(state,debt.id).map{it.entry}.filter{it.settlementId!=null&&it.kind in listOf(EntryKind.REPAY,EntryKind.COLLECT)}.forEach{e->TextButton(enabled=!vm.busy,onClick={vm.change("本次本息已撤销"){RichLedger.undoSettlement(it,e.settlementId!!)}}){Text("撤销${LedgerCalendar.format(e.epochDay)}本次本息（全部原记录）")}}
        TextButton(enabled=!vm.busy,onClick={vm.change("债务已删除",{selected=null}){RichLedger.deleteDebt(it,debt.id)}}){Text("删除债务（有还款时需先撤销）",color=ExpenseColor)}
    }},confirmButton={TextButton({selected=null}){Text("关闭")}})
    if(create||edit){val existing=if(edit)debt else null;val code=existing?.currencyCode?:state.accounts.firstOrNull{it.id==account}?.currencyCode?:"CNY"
        AlertDialog(onDismissRequest={if(!vm.busy){create=false;edit=false}},title={Text(if(edit)"编辑债务" else "建立债务")},text={Column(Modifier.heightIn(max=470.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            if(!edit)Row{DebtKind.entries.forEach{v->FilterChip(side==v,{side=v},{Text(if(v==DebtKind.BORROWED)"我借入" else "我借出")})}}
            OutlinedTextField(person,{person=it},label={Text("对方")});if(!edit){AccountPicker(state.copy(accounts=state.accounts.filter{it.currencyCode!="CRYPTO"}),account,{account=it});OutlinedTextField(amount,{amount=it},label={Text("本金（$code）")});OutlinedTextField(day,{day=it},label={Text("借款日期 YYYY-MM-DD")})}
            OutlinedTextField(due,{due=it},label={Text("到期日期（选填）")});OutlinedTextField(interest,{interest=it},label={Text("约定总利息（$code，选填）")});OutlinedTextField(note,{note=it},label={Text("备注")});error?.let{Text(it,color=ExpenseColor)}
        }},confirmButton={TextButton(enabled=!vm.busy,onClick={try{val end=due.takeIf{it.isNotBlank()}?.let(LedgerCalendar::parse);val intValue=if(interest.isBlank())0 else CurrencyUnits.parse(interest,code);val fn:(LedgerState)->LedgerState={s->if(existing!=null)RichLedger.updateDebt(s,existing.id,person,end,intValue,debtSavedNote(existing.note,note),interestProvided=interest.isNotBlank())else RichLedger.createDebt(s,side,person,CurrencyUnits.parse(amount,code),LedgerCalendar.parse(day),account,end,intValue,note,LocalClock.today(),interestProvided=interest.isNotBlank())};fn(state);vm.change("债务已保存",{create=false;edit=false},fn)}catch(e:Exception){error=e.message}}){Text("保存")}},dismissButton={TextButton({create=false;edit=false}){Text("取消")}})
    }
    if(settle&&debt!=null)AlertDialog(onDismissRequest={if(!vm.busy)settle=false},title={Text(if(debt.kind==DebtKind.BORROWED)"归还本金 / 利息" else "收回本金 / 利息")},text={Column(Modifier.heightIn(max=430.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("未结本金 ${nativeMoney(RichLedger.outstanding(state,debt.id),debt.currencyCode)}")
        OutlinedTextField(amount,{amount=it},label={Text("本次本金（${debt.currencyCode}）")});OutlinedTextField(interest,{interest=it},label={Text("本次利息（可为0）")});AccountPicker(state.copy(accounts=state.accounts.filter{it.currencyCode==debt.currencyCode}),account,{account=it});OutlinedTextField(day,{day=it},label={Text("实际日期 YYYY-MM-DD")});if(debt.currencyCode=="CNY"&&debt.kind==DebtKind.BORROWED)BudgetPicker(state,budgetId){budgetId=it};error?.let{Text(it,color=ExpenseColor)}
    }},confirmButton={TextButton(enabled=!vm.busy,onClick={try{val principal=CurrencyUnits.parse(amount,debt.currencyCode);val interestValue=CurrencyUnits.parse(interest,debt.currencyCode);val date=LedgerCalendar.parse(day);val fn:(LedgerState)->LedgerState={s->RichLedger.settleDebt(s,debt.id,principal,interestValue,date,LocalClock.minute(),account,LocalClock.today(),request,budgetId)};fn(state);vm.change("本息已保存",{settle=false},fn)}catch(e:Exception){error=e.message}}){Text("确认保存本息")}},dismissButton={TextButton({settle=false}){Text("取消")}})
}

@Composable private fun DebtProjectRecords(state:LedgerState,id:String,onEntry:(Entry)->Unit){
    DebtGroups.projectEntries(state,id).forEach{(e,portion)->Column(Modifier.fillMaxWidth().clickable{onEntry(e)}.padding(vertical=6.dp)){
        Text("${LedgerCalendar.format(e.epochDay)} ${LocalClock.timeText(e.minuteOfDay,e.second)} · ${if(e.isInterest)"利息"else e.kind.label()} ${nativeMoney(e.amountMinor,e.currencyCode)}",fontSize=11.sp)
        if(portion>0)Text("原归还全额如上 · 抵扣本项 ${nativeMoney(portion,e.currencyCode)}",fontSize=10.sp,color=Muted)
        Text(state.accountName(e.accountId),fontSize=10.sp,color=Muted)
        if(debtVisibleNote(e.note).isNotBlank())Text(debtVisibleNote(e.note),fontSize=10.sp,color=Muted)
    }}
}

private val generatedDebtNote="历史本金配对；来源未提供利息及到期信息"
private fun debtVisibleNote(note:String)=note.replace(generatedDebtNote,"").trim()
private fun debtSavedNote(original:String,edited:String):String=if(edited==debtVisibleNote(original))original else if(generatedDebtNote in original)(generatedDebtNote+"\n"+edited).trim()else edited
