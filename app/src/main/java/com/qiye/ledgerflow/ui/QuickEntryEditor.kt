@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.qiye.ledgerflow.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.cashflow.ui.parseCurrencyExpression

internal fun EntryDraft.withQuickEntryHistory(old:Entry?):EntryDraft = if(old==null)this else copy(
    affectsBalance=old.affectsBalance,affectsBudget=old.affectsBudget,debtId=old.debtId,
    isInterest=old.isInterest,sourceDetails=old.sourceDetails,settlementId=old.settlementId)

@Composable fun QuickEntryEditor(state:LedgerState,vm:LedgerViewModel,onBack:()->Unit,id:String?=null){
    val old=remember(id){state.entries.firstOrNull{it.id==id}}
    val opening=old?.debtId!=null&&old.kind in listOf(EntryKind.BORROW,EntryKind.LEND)
    val lockedKind=old!=null&&!old.kind.isOrdinary()&&old.kind!=EntryKind.TRANSFER
    var kind by rememberSaveable(id){mutableStateOf(old?.kind?:EntryKind.EXPENSE)}
    var expression by rememberSaveable(id){mutableStateOf(old?.let{CurrencyUnits.format(it.amountMinor,it.currencyCode)}?:"")};var calculated by rememberSaveable(id){mutableStateOf(false)}
    var categoryId by rememberSaveable(id){mutableStateOf(old?.categoryId)};var parentId by rememberSaveable(id){mutableStateOf(old?.categoryId?.let{key->state.categories.firstOrNull{it.id==key}?.let{it.parentId?:it.id}})}
    var fallback by rememberSaveable(id){mutableStateOf(old?.category?:"餐饮")}
    var account by rememberSaveable(id){mutableStateOf(old?.accountId)};var target by rememberSaveable(id){mutableStateOf(old?.targetAccountId)}
    var crypto by rememberSaveable(id){mutableStateOf(old?.currencyCode?.takeIf{CurrencyUnits.isCrypto(it)}?:"USDT")}
    var currency by rememberSaveable(id){mutableStateOf(old?.currencyCode?:"CNY")}
    var date by rememberSaveable(id){mutableStateOf(LedgerCalendar.format(old?.epochDay?:LocalClock.today()))}
    var time by rememberSaveable(id){mutableStateOf(LocalClock.timeText(old?.minuteOfDay?:LocalClock.minute(),old?.second?:0))}
    var note by rememberSaveable(id){mutableStateOf(old?.note.orEmpty())};var tags by rememberSaveable(id){mutableStateOf(old?.tags.orEmpty())}
    var budgetId by rememberSaveable(id){mutableStateOf(if(old!=null)old.budgetId else state.budgets.filter{it.isActive}.singleOrNull()?.id)}
    var excludeBudget by rememberSaveable(id){mutableStateOf(old?.excludeFromBudget?:false)};var excludeStats by rememberSaveable(id){mutableStateOf(old?.excludeFromStatistics?:false)}
    var refundId by rememberSaveable(id){mutableStateOf(old?.refundOfId)}
    var fallbackSub by rememberSaveable(id){mutableStateOf(old?.subcategory.orEmpty())}
    var deletion by remember{mutableStateOf(false)}
    var sheet by remember{mutableStateOf<String?>(null)};var error by remember{mutableStateOf<String?>(null)};var saved by remember{mutableStateOf(false)}
    val source=state.accounts.firstOrNull{it.id==account}
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    var noteFocused by remember{mutableStateOf(false)}
    var noteBounds by remember{mutableStateOf(Rect.Zero)}
    var noteImeSeen by remember{mutableStateOf(false)}
    var submitted by remember{mutableStateOf(false)}
    val density=LocalDensity.current
    val imeHeight=with(density){WindowInsets.ime.getBottom(this).toDp()}
    val navHeight=with(density){WindowInsets.navigationBars.getBottom(this).toDp()}
    val keyboardReserve=maxOf(243.dp,imeHeight-navHeight)
    LaunchedEffect(saved){if(saved){kotlinx.coroutines.delay(1200);saved=false}}
    fun finishNote(){focus.clearFocus();keyboard?.hide();noteImeSeen=false}
    LaunchedEffect(noteFocused,imeHeight){
        if(!noteFocused)noteImeSeen=false
        else if(imeHeight>0.dp)noteImeSeen=true
        else if(noteImeSeen)finishNote()
    }
    fun selectCurrency(code:String){
        finishNote();currency=code;if(CurrencyUnits.isCrypto(code))crypto=code
        val compatible=source?.let{it.currencyCode==code||it.currencyCode=="CRYPTO"&&CurrencyUnits.isCrypto(code)}?:true
        if(!compatible)account=null
        target=null;refundId=null;error=null;calculated=false
    }
    fun selectAccount(id:String?){
        account=id;target=null;refundId=null;error=null
        state.accounts.firstOrNull{it.id==id}?.let{currency=if(it.currencyCode=="CRYPTO")crypto else it.currencyCode}
        finishNote()
    }
    val currentAccounts=remember(state.accounts,account,target){state.accounts.filter{it.active&&!it.archived||it.id==account||it.id==target}}
    val categoryKind=RichLedger.categoryKind(kind)
    val parents=remember(state.categories,categoryKind){state.categories.filter{it.active&&it.parentId==null&&it.kind==categoryKind}.sortedWith(compareBy<LedgerCategory>{it.order}.thenBy{it.sequence})}
    val children=remember(state.categories,parentId,categoryKind){state.categories.filter{it.active&&it.parentId==parentId&&it.parentId!=null&&it.kind==categoryKind}.sortedWith(compareBy<LedgerCategory>{it.order}.thenBy{it.sequence})}
    val selected=state.categories.firstOrNull{it.id==categoryId}
    val labels=selected?.let{RichLedger.categoryLabels(state,it.id)}
    val category=if(kind==EntryKind.TRANSFER)"内部转账"else labels?.first?:fallback
    val sub=labels?.second?:fallbackSub
    fun selectKind(value:EntryKind){if(kind==value)return;kind=value;categoryId=null;parentId=null;fallback=if(value==EntryKind.INCOME)"工资"else "餐饮";refundId=null;fallbackSub="";error=null;saved=false}
    fun calculate():Long?=parseCurrencyExpression(expression,currency)?.let{runCatching{CurrencyUnits.parse(CurrencyUnits.format(it,currency),currency,true)}.getOrNull()}
    fun press(raw:String){
        if(vm.busy||opening)return
        finishNote();error=null;saved=false;val key=if(raw=="−")"-"else raw
        when(key){
            "C"->{expression="";calculated=false}
            "⌫"->{expression=expression.dropLast(1);calculated=false}
            "="->{val value=calculate();if(value==null)error="请检查算式或除数"else{expression=CurrencyUnits.format(value,currency);calculated=true}}
            "+","-","×","÷"->{if(expression.isBlank()){if(key=="-")expression="-"}else{expression=if(expression.last() in "+-×÷")expression.dropLast(1)+key else expression+key};calculated=false}
            "."->{if(calculated){expression="";calculated=false};val token=expression.takeLastWhile{it !in "+-×÷"};if('.' !in token)expression+=if(token.isEmpty())"0."else "."}
            else->{if(calculated){expression="";calculated=false};if(expression.length<100)expression+=key}
        }
    }
    fun save(again:Boolean){
        if(vm.busy||submitted)return
        finishNote()
        try{
            val minor=calculate()?:error("请检查算式或除数")
            require(minor>0){"金额须大于0"}
            val amount=CurrencyUnits.parse(CurrencyUnits.format(minor,currency),currency)
            val draft=EntryDraft(kind,amount,LedgerCalendar.parse(date),LocalClock.parseTime(time),category,account,if(kind==EntryKind.TRANSFER)target else null,note,LocalClock.parseSecond(time),categoryId,sub,tags,currency,excludeBudget,excludeStats,refundOfId=if(kind==EntryKind.REFUND)refundId else null,budgetId=if(kind==EntryKind.TRANSFER)null else budgetId).withQuickEntryHistory(old)
            error=null;submitted=true
            vm.changeWithFailure("账目已保存",{if(again){expression="";note="";calculated=false;saved=true;submitted=false}else onBack()},onFailure={message->error=message;submitted=false}){LedgerImport.saveCategorizedEntry(it,draft,LocalClock.today(),id)}
        }catch(failure:Exception){error=failure.message?:"未保存，请检查输入"}
    }
    BackHandler{if(noteFocused||imeHeight>0.dp)finishNote()else if(!vm.busy)onBack()}
    Scaffold(modifier=Modifier.pointerInput(noteFocused){
        awaitEachGesture{val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial);if(noteFocused&&!noteBounds.contains(down.position))finishNote()}
    },containerColor=Paper,contentWindowInsets=WindowInsets.safeDrawing,
        topBar={Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically){
            (if(lockedKind)listOf(kind)else listOf(EntryKind.EXPENSE,EntryKind.INCOME,EntryKind.TRANSFER).filter{it!=EntryKind.TRANSFER||old?.planId==null}).forEach{value->Column(Modifier.weight(1f).clickable(enabled=!vm.busy&&!lockedKind){selectKind(value)}.padding(vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(value.label(),color=if(kind==value)LedgerGreen else Muted,fontSize=16.sp,fontWeight=if(kind==value)FontWeight.Bold else FontWeight.Normal);Spacer(Modifier.height(4.dp));Box(Modifier.width(24.dp).height(2.dp).background(if(kind==value)LedgerGreen else Color.Transparent))}}
            TextButton(enabled=!vm.busy,onClick=onBack,modifier=Modifier.width(44.dp)){Text("×",fontSize=27.sp,color=Muted)}
        }},bottomBar={Column(Modifier.fillMaxWidth().background(Panel).navigationBarsPadding()){
            Text(error?:if(saved)"已保存，再记一笔"else "",Modifier.fillMaxWidth().height(22.dp).padding(horizontal=14.dp,vertical=3.dp),color=if(error!=null)ExpenseColor else LedgerGreen,fontSize=11.sp,maxLines=1)
            if(source?.currencyCode=="CRYPTO"||CurrencyUnits.isCrypto(currency))Row(Modifier.fillMaxWidth().height(42.dp).horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(7.dp)){
                listOf("USDT","BTC","LTC").forEach{code->FilterChip(currency==code,{if(!opening)selectCurrency(code)},{Text(code,fontSize=11.sp)})}
            }
            Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal=14.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
                BasicTextField(note,{note=it},Modifier.weight(1f).onGloballyPositioned{noteBounds=it.boundsInRoot()}.onFocusChanged{noteFocused=it.isFocused}.padding(vertical=10.dp),enabled=!vm.busy,singleLine=true,textStyle=MaterialTheme.typography.bodyMedium.copy(fontSize=12.sp,color=Ink),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={save(false)}),decorationBox={inner->if(note.isEmpty())Text("添加备注",color=Muted,fontSize=12.sp);inner()})
                Row(verticalAlignment=Alignment.CenterVertically){
                    Text(if(currency=="CNY")"¥"else if(currency=="USD")"$"else currency,Modifier.widthIn(min=44.dp).height(44.dp).clickable(enabled=!vm.busy&&!opening){if(currency in listOf("CNY","USD"))selectCurrency(if(currency=="CNY")"USD"else "CNY")else finishNote()}.wrapContentSize(),fontSize=25.sp,color=Ink)
                    Column(Modifier.widthIn(min=90.dp,max=190.dp).clickable{finishNote()},horizontalAlignment=Alignment.End){
                        Text(if(expression.any{it in "+-×÷"})expression else "",Modifier.height(18.dp),fontSize=12.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(calculate()?.let{CurrencyUnits.format(it,currency)}?:expression.ifBlank{"0.00"},Modifier.height(34.dp),fontSize=25.sp,fontWeight=FontWeight.Medium,color=Ink,maxLines=1)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(keyboardReserve)){
            if(!noteFocused)Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)){
            listOf(listOf("7","8","9","÷"),listOf("4","5","6","×"),listOf("1","2","3","−"),listOf(".","0","⌫","+")).forEach{row->Row{row.forEach{key->TextButton(enabled=!vm.busy,onClick={press(key)},modifier=Modifier.weight(1f).height(49.dp).border(.5.dp,Line),shape=RoundedCornerShape(0.dp)){Text(key,color=if(key in listOf("÷","×","−","+"))LedgerGreen else Ink,fontSize=24.sp)}}}}
            Row{(if(old==null)listOf("C","=","再记","完成")else listOf("C","=","删除","完成")).forEach{key->Button(enabled=!vm.busy,onClick={when(key){"再记"->save(true);"完成"->save(false);"删除"->if(!opening)deletion=true;else->press(key)}},modifier=Modifier.weight(1f).height(47.dp).border(.5.dp,Line),shape=RoundedCornerShape(0.dp),contentPadding=PaddingValues(0.dp),colors=ButtonDefaults.buttonColors(containerColor=if(key=="完成")LedgerGreen else Paper,contentColor=if(key=="完成")Color.White else LedgerGreen)){Text(if(vm.busy&&key=="完成")"保存中"else key,fontSize=16.sp)}}}
            }}
        }}){padding->Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).padding(horizontal=10.dp,vertical=8.dp)){
            if(kind==EntryKind.TRANSFER){
                QuickAccountRow("转出",state.accountName(account),currentAccounts,false,!vm.busy,{finishNote()},::selectAccount)
                QuickAccountRow("转入",state.accountName(target),currentAccounts.filter{it.id!=account&&it.currencyCode==source?.currencyCode},false,!vm.busy,{finishNote()},{target=it;finishNote()})
            }
            else if(kind.isOrdinary()){
                if(parents.isEmpty())QuickCategoryGrid(if(kind==EntryKind.INCOME)listOf("工资","红包","奖金","利息","其他收入")else listOf("餐饮","日用","交通","居住","其他支出"),{it==fallback},{fallback=it;fallbackSub="";categoryId=null})
                else QuickCategoryGrid(parents,{it.id==categoryId||it.id==parentId},{categoryId=it.id;parentId=it.id;fallback=it.name;fallbackSub=""}){it.name}
                if(children.isNotEmpty()){
                    HorizontalDivider(Modifier.padding(vertical=6.dp),color=Line)
                    QuickCategoryGrid(children,{it.id==categoryId},{categoryId=it.id}){it.name}
                }
            }
            HorizontalDivider(Modifier.padding(vertical=8.dp),color=Line)
            if(kind!=EntryKind.TRANSFER)QuickAccountRow("账户",state.accountName(account),currentAccounts,true,!vm.busy&&!opening,{finishNote()},::selectAccount)
            Row{Box(Modifier.weight(1f)){QuickToolRow("日期",date.takeLast(5)){finishNote();sheet="date"}};Box(Modifier.weight(1f)){QuickToolRow("更多",if(kind==EntryKind.REFUND)"退款/报销"else "标签 / 预算"){finishNote();sheet="more"}}}
        }}
    if(sheet!=null)AlertDialog(onDismissRequest={if(!vm.busy)sheet=null},title={Text(when(sheet){"date"->"日期与时间";else->"更多"})},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)){
        when(sheet){
            "date"->{OutlinedTextField(date,{date=it},label={Text("日期 YYYY-MM-DD")});OutlinedTextField(time,{time=it},label={Text("时间 HH:MM 或 HH:MM:SS")})}
            else->{
                OutlinedTextField(tags,{tags=it},label={Text("标签（逗号分隔）")})
                if(old!=null){
                    OutlinedTextField(category,{fallback=it;categoryId=null},label={Text("分类")})
                    OutlinedTextField(sub,{fallbackSub=it;categoryId=null},label={Text("子分类（可选）")})
                }
                if(kind.isOrdinary()){Toggle("不计入预算",excludeBudget){excludeBudget=it};if(!excludeBudget)BudgetPicker(state,budgetId){budgetId=it};Toggle("不计入收支统计",excludeStats){excludeStats=it};FilterChip(kind==EntryKind.REFUND,{selectKind(if(kind==EntryKind.REFUND)EntryKind.EXPENSE else EntryKind.REFUND)},{Text("退款 / 报销")})}
                if(kind==EntryKind.REFUND){Text("关联原支出（可选）",fontSize=12.sp);TextButton({refundId=null}){Text("不关联")};state.entries.filter{it.kind==EntryKind.EXPENSE&&it.currencyCode==currency}.sortedByDescending{it.sequence}.take(100).forEach{e->TextButton({refundId=e.id;categoryId=e.categoryId;fallback=e.category;parentId=e.categoryId?.let{id->state.categories.firstOrNull{it.id==id}?.parentId}}){Text((if(refundId==e.id)"✓ "else "")+LedgerCalendar.format(e.epochDay)+" ${e.category} "+nativeMoney(e.amountMinor,currency))}}}
            }
        }
    }},confirmButton={TextButton(enabled=!vm.busy,onClick={sheet=null}){Text("确定")}})
    if(deletion&&id!=null)EntryDeleteDialog(state,id,vm,onDeleted=onBack){deletion=false}

}

@Composable private fun QuickToolRow(label:String,value:String,onClick:()->Unit){Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(horizontal=5.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){Text(label,fontSize=11.sp,color=Muted);Spacer(Modifier.width(8.dp));Text(value,Modifier.weight(1f),fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text("›",color=Muted)}}

@Composable private fun <T> QuickCategoryGrid(items:List<T>,selected:(T)->Boolean,pick:(T)->Unit,label:(T)->String={it.toString()}){
    items.chunked(5).forEach{row->Row(Modifier.fillMaxWidth()){
        row.forEach{item->val text=label(item);val chosen=selected(item);Column(Modifier.weight(1f).clickable{pick(item)}.padding(vertical=6.dp),horizontalAlignment=Alignment.CenterHorizontally){
            Box(Modifier.size(39.dp).clip(CircleShape).background(if(chosen)LedgerGreen else Color(0xFFE9ECE6)),contentAlignment=Alignment.Center){Text(quickCategorySymbol(text),fontSize=18.sp,color=if(chosen)Color.White else Ink)}
            Text(text,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=if(chosen)LedgerGreen else Ink,modifier=Modifier.padding(top=4.dp))
        }}
        repeat(5-row.size){Spacer(Modifier.weight(1f))}
    }}
}
private fun quickCategorySymbol(name:String)=when{listOf("餐","饭","食","三餐").any{name.contains(it)}->"餐";name.contains("交通")||name.contains("出行")->"行";name.contains("工资")||name.contains("收入")->"¥";name.contains("购物")||name.contains("日用")->"购";name.contains("房")||name.contains("居住")->"家";else->name.take(1).ifBlank{"·"}}

@Composable private fun QuickAccountRow(label:String,value:String,accounts:List<Account>,allowNone:Boolean,enabled:Boolean,onOpen:()->Unit,onPick:(String?)->Unit){
    var expanded by remember{mutableStateOf(false)}
    Box{
        QuickToolRow(label,value){if(enabled){onOpen();expanded=true}}
        DropdownMenu(expanded,{expanded=false}){
            if(allowNone)DropdownMenuItem({Text("无账户")},{onPick(null);expanded=false})
            accounts.forEach{account->DropdownMenuItem({Text("${account.name} · ${account.currencyCode}")},{onPick(account.id);expanded=false})}
            if(accounts.isEmpty())DropdownMenuItem({Text("暂无可用账户")},{},enabled=false)
        }
    }
}
