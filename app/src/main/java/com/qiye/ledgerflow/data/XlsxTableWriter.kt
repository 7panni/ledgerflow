package com.qiye.ledgerflow.data

import java.io.ByteArrayOutputStream
import java.util.zip.*
import java.math.BigDecimal

object XlsxTableWriter {
    fun write(rows:List<List<String>>):ByteArray {
        require(rows.size<=100001&&rows.all{it.size==22})
        fun xml(s:String):String {require(s.none{it.code<32&&it !in listOf('\n','\r','\t')}){"含XML控制字符，请用原生/CSV"};return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")}
        val sheet=buildString{
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            rows.forEachIndexed{r,row->append("<row r=\"${r+1}\">");row.forEachIndexed{c,v->val ref="${('A'.code+c).toChar()}${r+1}";if(r>0&&c in listOf(1,11,14,16,18)&&v.isNotBlank()){require(BigDecimal(v).stripTrailingZeros().precision()<=15){"超Excel15位有效精度，请用原生/CSV"};append("<c r=\"$ref\"><v>${xml(v)}</v></c>")}else append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(v)}</t></is></c>")};append("</row>")}
            append("</sheetData></worksheet>")
        }
        val parts=mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
            "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="账单" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to sheet)
        val out=ByteArrayOutputStream();ZipOutputStream(out).use{z->parts.forEach{(name,text)->z.putNextEntry(ZipEntry(name));z.write(text.toByteArray(Charsets.UTF_8));z.closeEntry()}}
        require(out.size()<=20*1024*1024){"表格超过20MB，请用原生模块"};return out.toByteArray()
    }
}
