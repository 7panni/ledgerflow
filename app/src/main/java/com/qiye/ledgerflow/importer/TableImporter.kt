package com.qiye.ledgerflow.importer

import com.qiye.ledgerflow.core.*
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

enum class ImportStatus { READY, DUPLICATE, ERROR, UNSUPPORTED }
data class ImportLine(val line:Int,val status:ImportStatus,val message:String,val record:ImportRecord?=null)
data class ImportPreview(val lines:List<ImportLine>,val notices:List<String>,val newCategories:List<String> = emptyList()) {
    val ready get()=lines.filter { it.status==ImportStatus.READY }.mapNotNull { it.record }
    fun count(status:ImportStatus)=lines.count { it.status==status }
}
private class UnsupportedRow(message:String):IllegalArgumentException(message)

/** Target mapping is the neutral 22-column contract, not the legacy application's importer. */
class TableImporter {
    companion object { val HEADERS=listOf("日期","金额","账本","类型","分类","子分类","备注","标签","账户","转入账户","债务人","转账手续费","是否为报销","账单状态","报销金额","报销日期","退款金额","退款日期","原价","不计入预算","不计入收支","账单图片") }
    fun preview(bytes:ByteArray,name:String,state:LedgerState,today:Long):ImportPreview {
        require(bytes.size<=20*1024*1024) { "表格超过20MB" }
        val rows=if(name.lowercase().endsWith(".xlsx") || bytes.take(2)==listOf(80.toByte(),75.toByte())) XlsxRows.read(bytes) else {
            require(!name.lowercase().endsWith(".xls")) { "旧版XLS请另存为XLSX或CSV" }
            var text=bytes.toString(Charsets.UTF_8)
            if('\uFFFD' in text) text=bytes.toString(charset("GB18030"))
            parseCsv(text.removePrefix("\uFEFF"))
        }
        require(rows.size<=100_001) { "行数超过100000" }
        val headerIndex=rows.indexOfFirst { row->listOf("日期","金额","类型").all { it in row.map(::norm) } }
        require(headerIndex>=0) { "未找到日期、金额、类型表头" }
        val headers=rows[headerIndex].map(::norm);require(headers.distinct().size==headers.size) { "存在重复表头" }
        val notices=mutableListOf("默认仅恢复历史统计，不改变当前余额或预算；确认前不会写入。","会跳过已导入的相同记录。分批导出时，内容完全相同的记录仍需核对。")
        val missing=HEADERS.filterNot { it in headers };if(missing.isNotEmpty()) notices+="缺少可选列：${missing.joinToString("、")}；未提供的信息不会补造。"
        val counts=mutableMapOf<String,Int>();val existing=state.importedKeys.toSet();val output=mutableListOf<ImportLine>()
        rows.drop(headerIndex+1).forEachIndexed { index,row->
            if(row.all { it.isBlank() })return@forEachIndexed
            val line=headerIndex+index+2
            try {
                require(row.size<=headers.size || row.drop(headers.size).all { it.isBlank() }) { "列数多于表头" }
                val cells=headers.mapIndexed { i,k->k to norm(row.getOrElse(i){""}) }.toMap().toMutableMap()
                val parsed=parse(cells,line,today)
                listOfNotNull(parsed.accountName,parsed.targetAccountName).forEach { account->
                    if(state.accounts.any{it.name==account&&it.currencyCode!="CNY"})throw UnsupportedRow("同名账户为外币，表格没有币种列；请先核对账户")
                }
                val date=LocalDate.ofEpochDay(parsed.epochDay).atStartOfDay().plusMinutes(parsed.minuteOfDay.toLong()).plusSeconds(parsed.second.toLong())
                cells["日期"]=date.toString(); listOf("金额","转账手续费","退款金额","报销金额","原价").forEach { k->cells[k]=if(cells[k].isNullOrBlank())"0" else BigDecimal(cells.getValue(k)).stripTrailingZeros().toPlainString() }
                listOf("不计入预算","不计入收支","是否为报销").forEach { k->cells[k]=flag(cells[k].orEmpty()).toString() }
                listOf("退款日期","报销日期").forEach { k->if(!cells[k].isNullOrBlank())cells[k]=parseDate(cells.getValue(k)).toString() }
                cells["标签"]=cells["标签"].orEmpty().split(',', '，',';','；').map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted().joinToString(",")
                val canonical=HEADERS.joinToString("") { k->val v=cells[k].orEmpty();"${k.length}:$k${v.length}:$v" }
                val hash=MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                val ordinal=(counts[hash]?:0)+1;counts[hash]=ordinal
                val record=parsed.copy(key="$hash#$ordinal",tags=cells["标签"].orEmpty())
                val status=if(record.key in existing)ImportStatus.DUPLICATE else ImportStatus.READY
                output+=ImportLine(line,status,record.warnings.joinToString("；").ifBlank { if(status==ImportStatus.DUPLICATE)"相同记录已导入" else "可导入" },record)
            } catch(e:Exception){ output+=ImportLine(line,if(e is UnsupportedRow)ImportStatus.UNSUPPORTED else ImportStatus.ERROR,e.message?:"行格式无效") }
        }
        val paired=pairDebts(output,state)
        val missingCategories=linkedSetOf<String>()
        paired.filter{it.status==ImportStatus.READY}.mapNotNull{it.record}.filter{it.kind.isOrdinary()}.forEach { r->
            val kind=RichLedger.categoryKind(r.kind);val parent=state.categories.firstOrNull{it.kind==kind&&it.parentId==null&&it.name==r.category}
            val prefix=if(kind==EntryKind.INCOME)"收入"else "支出"
            if(parent==null)missingCategories+="$prefix · ${r.category}（父分类）"
            if(r.subcategory.isNotBlank()&&(parent==null||state.categories.none{it.parentId==parent.id&&it.name==r.subcategory}))missingCategories+="$prefix · ${r.category} / ${r.subcategory}（子分类）"
        }
        return ImportPreview(paired,notices,missingCategories.toList())
    }
    private fun parse(c:Map<String,String>,line:Int,today:Long):ImportRecord {
        fun v(k:String)=c[k].orEmpty()
        val sourceType=if(v("类型")=="债务")v("分类") else v("类型")
        val kind=when(sourceType){"支出"->EntryKind.EXPENSE;"收入"->EntryKind.INCOME;"转账"->EntryKind.TRANSFER;"退款"->EntryKind.REFUND;"借入"->EntryKind.BORROW;"借出"->EntryKind.LEND;"还款","归还"->EntryKind.REPAY;"收回"->EntryKind.COLLECT;else->throw UnsupportedRow("无法识别类型：${v("类型")}，请核对后再导入")}
        if(v("账单状态") !in listOf("","正常","已完成","已入账","未报销","已报销","部分报销"))throw UnsupportedRow("账单状态需核对：${v("账单状态")}")
        val date=parseDate(v("日期"));require(date.toLocalDate().toEpochDay()<=today) { "未来日期应作为计划，未导入实际账目" }
        val amount=minor(v("金额"));require(amount>0) { "金额须大于0" }
        fun nullableAccount(raw:String)=norm(raw).takeUnless { it in listOf("","无账户","未指定账户","无") }
        val account=nullableAccount(v("账户"));val target=nullableAccount(v("转入账户"))
        if(kind==EntryKind.TRANSFER)require(account!=null && target!=null && account!=target) { "转账须有两个不同账户" }
        val fee=minor(v("转账手续费"),true);val refund=minor(v("退款金额"),true);val reimb=minor(v("报销金额"),true)
        require(fee>=0 && refund>=0 && reimb>=0) { "手续费/退款/报销不能为负" }
        if(fee>0 && kind!=EntryKind.TRANSFER)throw UnsupportedRow("非转账行含转账手续费，需先核对")
        if((refund>0||reimb>0)&&kind!=EntryKind.EXPENSE)throw UnsupportedRow("退款/报销金额须填在原支出记录中")
        require(Money.add(refund,reimb)<=amount) { "退款加报销超过原支出金额" }
        fun returnedDate(k:String,value:Long):Long? { if(value==0L)return null;require(v(k).isNotBlank()) { "${k}缺失，未假定发生日期" };val d=parseDate(v(k)).toLocalDate().toEpochDay();require(d<=today) { "${k}在未来" };return d }
        val rd=returnedDate("退款日期",refund);val bd=returnedDate("报销日期",reimb)
        val warnings=mutableListOf<String>()
        if(v("账单图片").isNotBlank())warnings+="仅保留图片名称或地址，不含图片文件"
        if(v("债务人").isNotBlank()||!kind.isOrdinary() && kind!=EntryKind.TRANSFER)warnings+="保留对方及本金资金记录，表格不能恢复完整债务关系"
        if(flag(v("是否为报销"))&&reimb==0L)warnings+="待报销标记保留，未生成尚未收到的报销收入"
        if(v("原价").isNotBlank())warnings+="原价另存留查，支出以金额列为准"
        require(v("债务人").length<=100){"债务人名称过长"}
        val details=HEADERS.filter { v(it).isNotBlank() }.joinToString("\n") { "$it：${v(it)}" }
        require(details.length<=8000) { "来源说明过长" }
        return ImportRecord("",line,kind,amount,date.toLocalDate().toEpochDay(),date.hour*60+date.minute,date.second,v("分类").ifBlank { "未分类" },v("子分类"),v("备注"),v("标签"),account,if(kind==EntryKind.TRANSFER)target else null,fee,refund,rd,reimb,bd,flag(v("不计入预算")),flag(v("不计入收支")),details,warnings,counterparty=v("债务人"))
    }
    private fun pairDebts(lines:List<ImportLine>,state:LedgerState):List<ImportLine> {
        data class Principal(val key:String,val person:String,val kind:EntryKind,val day:Long,val minute:Int,val second:Int,var left:Long)
        val updates=mutableMapOf<Int,ImportLine>();val pool=linkedMapOf<String,Principal>()
        state.debts.forEach{d->val e=state.entries.first{it.debtId==d.id&&it.kind in listOf(EntryKind.BORROW,EntryKind.LEND)};val key=e.importKey?:"debt:${d.id}";pool[key]=Principal(key,d.person,e.kind,e.epochDay,e.minuteOfDay,e.second,RichLedger.outstanding(state,d.id))}
        lines.filter{it.record?.kind in listOf(EntryKind.BORROW,EntryKind.LEND)}.forEach{line->
            val r=line.record!!;val e=state.entries.firstOrNull{it.importKey==r.key}
            pool.putIfAbsent(r.key,Principal(r.key,r.counterparty,r.kind,r.epochDay,r.minuteOfDay,r.second,r.amountMinor))
            updates[line.line]=line.copy(status=if(e!=null&&e.debtId==null)ImportStatus.READY else line.status,record=r.copy(debtGroupKey=r.key),message="逐笔保留本金；利息/到期未提供。还款仅唯一对应时自动关联。")
        }
        lines.filter{it.record?.kind in listOf(EntryKind.REPAY,EntryKind.COLLECT)}.sortedWith(compareBy<ImportLine>{it.record!!.epochDay}.thenBy{it.record!!.minuteOfDay}.thenBy{it.record!!.second}.thenBy{it.record!!.key}).forEach{line->
            val r=line.record!!;val e=state.entries.firstOrNull{it.importKey==r.key}
            if(e?.debtId!=null){updates[line.line]=line.copy(message="已导入并关联本金，保持原关系");return@forEach}
            val eligible=pool.values.filter{p->r.counterparty.isNotBlank()&&p.person==r.counterparty&&p.kind==(if(r.kind==EntryKind.REPAY)EntryKind.BORROW else EntryKind.LEND)&&p.left>=r.amountMinor&& (p.day<r.epochDay||p.day==r.epochDay&&(p.minute<r.minuteOfDay||p.minute==r.minuteOfDay&&p.second<=r.second))}
            val only=eligible.singleOrNull()
            if(only!=null){only.left-=r.amountMinor;updates[line.line]=line.copy(status=if(e!=null)ImportStatus.READY else line.status,record=r.copy(debtGroupKey=only.key),message="唯一对应本金；归还可用不同账户，利息信息未提供。")}
            else updates[line.line]=line.copy(message=if(r.counterparty.isBlank())"对方未填写，将单独保留，不抵扣他人本金。"else "保留归还明细，按同名、同币种和借贷方向自动抵扣；日期或超额差异单列待核对。")
        }
        return lines.map{updates[it.line]?:it}
    }
    private fun minor(raw:String,blankZero:Boolean=false):Long { if(raw.isBlank()&&blankZero)return 0;val v=BigDecimal(raw).movePointRight(2).setScale(0,RoundingMode.UNNECESSARY).longValueExact();return Money.checked(v) }
    private fun flag(raw:String):Boolean=when(raw.trim().lowercase()){ "","否","false","0","n","no"->false;"是","true","1","y","yes"->true;else->error("无法识别的是/否标记：$raw") }
    private fun norm(raw:String)=Normalizer.normalize(raw.replace("\r\n","\n").replace('\r','\n'),Normalizer.Form.NFKC).trim()
    private fun parseDate(raw:String):LocalDateTime {
        val value=raw.trim().replace('/','-').replace('T',' ')
        for(pattern in listOf("uuuu-M-d H:mm:ss","uuuu-M-d H:mm","uuuu-M-d")){
            val format=DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT)
            runCatching { return if(pattern=="uuuu-M-d")LocalDate.parse(value,format).atStartOfDay() else LocalDateTime.parse(value,format) }
        }
        error("日期须为年-月-日，可含小时:分钟或秒")
    }
    // Generic quoted CSV loop reused from the user's importer; no legacy format/type/account mapping.
    private fun parseCsv(text:String):List<List<String>> {
        val rows=mutableListOf<List<String>>();val row=mutableListOf<String>();val cell=StringBuilder();var quoted=false;var index=0
        while(index<text.length){val ch=text[index]
            when {ch=='"'&&quoted&&index+1<text.length&&text[index+1]=='"'->{cell.append('"');index++};ch=='"'->quoted=!quoted;ch==','&&!quoted->{row+=cell.toString();cell.clear()};(ch=='\n'||ch=='\r')&&!quoted->{if(ch=='\r'&&index+1<text.length&&text[index+1]=='\n')index++;row+=cell.toString();cell.clear();rows+=row.toList();row.clear();require(rows.size<=100_001)};else->cell.append(ch)}
            require(cell.length<=16000) { "单元格过长" };index++
        }
        require(!quoted) { "CSV引号没有闭合" };if(cell.isNotEmpty()||row.isNotEmpty()){row+=cell.toString();rows+=row.toList()};return rows
    }
}

/** Bounded OOXML cell adapter over the platform ZIP/SAX libraries; no formula engine or external links. */
private object XlsxRows {
    fun read(bytes:ByteArray):List<List<String>> {
        val files=linkedMapOf<String,ByteArray>();var total=0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip->while(true){val e=zip.nextEntry?:break;require(files.size<512) { "XLSX包文件过多" };if(e.isDirectory)continue
            require(!e.name.contains("..")) { "XLSX路径无效" }
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val n=zip.read(buffer);if(n<0)break;total+=n;require(total<=64*1024*1024) { "XLSX解压内容超过64MB" };out.write(buffer,0,n)}
            files[e.name]=out.toByteArray()
        }}
        require("xl/workbook.xml" in files) { "不是可读取的XLSX，或文件加密" }
        var date1904=false;xml(files.getValue("xl/workbook.xml"),object:DefaultHandler(){override fun startElement(u:String?,l:String?,q:String,a:Attributes){if(q.endsWith("workbookPr"))date1904=a.getValue("date1904") in listOf("1","true")}})
        val shared=mutableListOf<String>();var inText=false;var current=StringBuilder()
        files["xl/sharedStrings.xml"]?.let { data->xml(data,object:DefaultHandler(){override fun startElement(u:String?,l:String?,q:String,a:Attributes){if(q=="si")current=StringBuilder();if(q=="t")inText=true};override fun characters(c:CharArray,s:Int,n:Int){if(inText)current.append(c,s,n)};override fun endElement(u:String?,l:String?,q:String){if(q=="t")inText=false;if(q=="si")shared+=current.toString()}}) }
        val dateFormats=mutableSetOf<Int>();val custom=mutableMapOf<Int,String>();val styles=mutableListOf<Int>();var inCellXfs=false
        files["xl/styles.xml"]?.let { data->xml(data,object:DefaultHandler(){override fun startElement(u:String?,l:String?,q:String,a:Attributes){when(q){"numFmt"->custom[a.getValue("numFmtId").toInt()]=a.getValue("formatCode");"cellXfs"->inCellXfs=true;"xf"->if(inCellXfs)styles+=a.getValue("numFmtId").toInt()}};override fun endElement(u:String?,l:String?,q:String){if(q=="cellXfs")inCellXfs=false}}) }
        dateFormats+=(14..22);dateFormats+=(45..47);custom.forEach { (id,format)->val clean=format.lowercase().replace(Regex("\\\"[^\\\"]*\\\"|\\\\."),"");if('y' in clean||'d' in clean)dateFormats+=id }
        val sheets=files.keys.filter { it.startsWith("xl/worksheets/")&&it.endsWith(".xml") }.sorted();require(sheets.isNotEmpty()) { "XLSX没有工作表" }
        val output=mutableListOf<List<String>>()
        for(sheet in sheets){
            var row=mutableMapOf<Int,String>();var type="";var style=0;var column=0;var value=StringBuilder();var reading=false;var formula=false
            xml(files.getValue(sheet),object:DefaultHandler(){override fun startElement(u:String?,l:String?,q:String,a:Attributes){when(q){"row"->row=mutableMapOf();"c"->{type=a.getValue("t")?:"";style=a.getValue("s")?.toIntOrNull()?:0;column=columnIndex(a.getValue("r")?:"A1");require(column<128);value=StringBuilder();formula=false};"v","t"->reading=true;"f"->formula=true}}
                override fun characters(c:CharArray,s:Int,n:Int){if(reading)value.append(c,s,n)}
                override fun endElement(u:String?,l:String?,q:String){when(q){"v","t"->reading=false;"c"->{var raw=value.toString();if(type=="s")raw=shared.getOrElse(raw.toInt()){error("共享文本索引无效")};if(formula)raw="[不支持公式]" else if(type!="s"&&type!="inlineStr"&&styles.getOrNull(style) in dateFormats&&raw.isNotBlank())raw=excelDate(raw,date1904);row[column]=raw};"row"->{if(row.isNotEmpty())output+=(0..row.keys.max()).map { row[it].orEmpty() };require(output.size<=100_001)}} }
            })
        }
        return output
    }
    private fun columnIndex(ref:String):Int {var n=0;for(c in ref.takeWhile { it.isLetter() })n=n*26+(c.uppercaseChar()-'A'+1);return n-1}
    private fun excelDate(raw:String,date1904:Boolean):String {
        val value=BigDecimal(raw);val day=value.setScale(0,RoundingMode.FLOOR).longValueExact();val seconds=value.subtract(BigDecimal.valueOf(day)).multiply(BigDecimal(86400)).setScale(0,RoundingMode.HALF_UP).longValueExact()
        val base=if(date1904)LocalDate.of(1904,1,1) else LocalDate.of(1899,12,31)
        return base.plusDays(day-if(!date1904&&day>=60)1 else 0).atStartOfDay().plusSeconds(seconds).toString().replace('T',' ')
    }
    private fun xml(bytes:ByteArray,handler:DefaultHandler){val factory=SAXParserFactory.newInstance();factory.isNamespaceAware=false;factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);runCatching {factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)};factory.newSAXParser().parse(ByteArrayInputStream(bytes),handler)}
}
