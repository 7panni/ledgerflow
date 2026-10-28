package com.qiye.ledgerflow.ui

import android.app.Application
import com.qiye.ledgerflow.LedgerFlowTiming
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import com.qiye.ledgerflow.importer.*

object LocalClock {
    fun today(): Long = LocalDate.now().toEpochDay()
    fun minute(): Int = LocalTime.now().let { it.hour * 60 + it.minute }
    fun timeText(minute: Int, second: Int = 0): String = (minute / 60).toString().padStart(2, '0') + ":" + (minute % 60).toString().padStart(2, '0') + if(second>0) ":"+second.toString().padStart(2,'0') else ""
    fun parseSecond(text:String):Int { parseTime(text); return text.split(':').getOrNull(2)?.toInt() ?: 0 }
    fun parseTime(text: String): Int {
        require(Regex("\\d{2}:\\d{2}(:\\d{2})?").matches(text)) { "时间格式为 HH:MM" }
        val parts = text.split(':').map { it.toInt() }
        require(parts[0] in 0..23 && parts[1] in 0..59 && (parts.size==2 || parts[2] in 0..59)) { "时间不存在" }
        return parts[0] * 60 + parts[1]
    }
}

class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    private val store = AtomicLedgerStore(File(application.filesDir, "ledgerflow"))
    private val repository = LedgerRepository(store)
    var state by mutableStateOf<LedgerState?>(null); private set
    var busy by mutableStateOf(true); private set
    var readFailure by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var restoreCandidate by mutableStateOf<LedgerState?>(null); private set
    var hasRecovery by mutableStateOf(false); private set

    var importPreview by mutableStateOf<ImportPreview?>(null); private set
    var cashflowPreview by mutableStateOf<CashflowImportPreview?>(null); private set

    fun cancelCashflowImport(){cashflowPreview=null}
    fun previewCashflowImport(uri:Uri){
        if(busy)return;val snapshot=state?:return;busy=true
        viewModelScope.launch {
            try {cashflowPreview=withContext(Dispatchers.IO){val input=getApplication<Application>().contentResolver.openInputStream(uri)?:error("无法读取现金流备份");val bytes=input.use{stream->val out=ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=stream.read(buffer);if(n<0)break;require(out.size()+n<=20*1024*1024){"文件超过20MB"};out.write(buffer,0,n)};out.toByteArray()};CashflowImporter.preview(bytes.toString(Charsets.UTF_8)).also{it.build(snapshot,LocalClock.today())}}}
            catch(e:Exception){notice="未迁入，账本未变：${e.message}"}finally{busy=false}
        }
    }
    fun confirmCashflowImport(options:CashflowMigrationOptions){val preview=cashflowPreview?:return;migrate("现金流已迁入；原账本已保护",{cashflowPreview=null}){preview.build(it,LocalClock.today(),options).state}}

    init {
        viewModelScope.launch {
            LedgerFlowTiming.mark("repository_dispatch")
            try {
                val loaded=withContext(Dispatchers.IO) {
                    val opened=LedgerFlowTiming.measure("repository_open"){repository.open()}
                    LedgerFlowTiming.measure("automatic_post",opened.plans.size.toLong()){if(opened.settings.cashflow.automaticPosting)repository.mutate{RichLedger.automaticPost(it,LocalClock.today())}else opened}
                }
                state=loaded
                LedgerFlowTiming.mark("state_published",loaded.entries.size.toLong())
            }
            catch (error: Exception) { readFailure = error.message ?: "本地数据读取失败" }
            finally { hasRecovery = withContext(Dispatchers.IO) { store.latestRecoveryFile() != null }; busy = false;LedgerFlowTiming.mark("load_ready") }
        }
    }

    fun refreshAutomatic() {
        if(busy || state?.settings?.cashflow?.automaticPosting!=true)return
        change("到期计划已核对"){RichLedger.automaticPost(it,LocalClock.today())}
    }
    fun cancelImport(){importPreview=null}
    fun previewImport(uri:Uri){
        if(busy)return;val snapshot=state?:return;busy=true
        viewModelScope.launch {
            try { importPreview=withContext(Dispatchers.IO){
                val resolver=getApplication<Application>().contentResolver
                val name=resolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c->if(c.moveToFirst())c.getString(0) else "import.csv" }?:"import.csv"
                val bytes=resolver.openInputStream(uri)?.use { input->val out=ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=20*1024*1024){"文件超过20MB"};out.write(buffer,0,n)};out.toByteArray() }?:error("无法读取表格")
                TableImporter().preview(bytes,name,snapshot,LocalClock.today())
            }}catch(e:Exception){notice="未导入，账本未变：${e.message}"}finally{busy=false}
        }
    }
    fun confirmImport(applyBalances:Boolean,accountMapping:Map<String,String?> = emptyMap()){
        val preview=importPreview?:return;var inserted=0;var skipped=0
        migrate("导入已保存",{importPreview=null;notice="已导入${inserted}行，跳过${skipped}条已有记录"}){ s->val result=LedgerImport.commit(s,preview.ready,LocalClock.today(),applyBalances,accountMapping);inserted=result.insertedRows;skipped=result.duplicates;result.state }
    }
    fun migrate(success:String,onSuccess:()->Unit={},mutation:(LedgerState)->LedgerState){
        if(busy)return;busy=true
        viewModelScope.launch {try{state=withContext(Dispatchers.IO){repository.migrate(mutation)};notice=success;onSuccess()}
            catch(e:Exception){notice="迁入失败，现有账本未变：${e.message}"}
            finally{hasRecovery=withContext(Dispatchers.IO){store.latestRecoveryFile()!=null};busy=false}}
    }
    fun clearNotice() { notice = null }
    fun inform(text: String) { notice = text }
    fun cancelRestore() { restoreCandidate = null }

    fun change(success:String,onSuccess:()->Unit={},mutation:(LedgerState)->LedgerState) = changeWithFailure(success,onSuccess,null,mutation)

    fun changeWithFailure(success: String, onSuccess: () -> Unit = {}, onFailure: ((String) -> Unit)? = null, mutation: (LedgerState) -> LedgerState) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                state = withContext(Dispatchers.IO) { repository.mutate(mutation) }
                notice = success
                onSuccess()
            } catch (error: Exception) { val message="未保存：${error.message ?: "本地写入失败"}";notice=message;onFailure?.invoke(message) }
            finally { busy = false }
        }
    }

    fun exportTo(uri: Uri) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val text = repository.exportBackup()
                    val output = getApplication<Application>().contentResolver.openOutputStream(uri, "wt") ?: error("无法打开所选文件")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(text); it.flush() }
                }
                notice = "全量 JSON 备份已导出"
            } catch (error: Exception) { notice = "备份导出失败：${error.message}" }
            finally { busy = false }
        }
    }

    fun exportPreviousTo(uri: Uri) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val file = store.latestRecoveryFile() ?: error("没有恢复前原文件")
                    val output = getApplication<Application>().contentResolver.openOutputStream(uri, "wt") ?: error("无法打开所选文件")
                    output.use { target -> file.inputStream().use { input -> input.copyTo(target) }; target.flush() }
                }
                notice = "恢复前原文件已导出；如原文件已损坏，此副本也保留原损坏内容"
            } catch (error: Exception) { notice = "副本导出失败：${error.message}" }
            finally { busy = false }
        }
    }

    fun previewRestore(uri: Uri) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                restoreCandidate = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val input = resolver.openInputStream(uri) ?: error("不能读取所选文件")
                    val bytes = input.use { stream ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= JsonLedgerCodec.MAX_BYTES) { "文件超过 64 MB，未读取" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    repository.previewBackup(bytes.toString(Charsets.UTF_8))
                }
            } catch (error: Exception) { notice = "未恢复，现有数据未变：${error.message}" }
            finally { busy = false }
        }
    }

    fun confirmRestore() {
        val candidate = restoreCandidate ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                state = withContext(Dispatchers.IO) { repository.restore(candidate) }
                hasRecovery = true
                restoreCandidate = null
                readFailure = null
                notice = "恢复完成；可在管理页导出恢复前原文件"
            } catch (error: Exception) { notice = "恢复失败，现有账本未变：${error.message}" }
            finally { hasRecovery = withContext(Dispatchers.IO) { store.latestRecoveryFile() != null }; busy = false }
        }
    }
    var moduleImportPreview by mutableStateOf<NativeModulePreview?>(null);private set
    var moduleExport by mutableStateOf<ModuleExport?>(null);private set
    fun cancelModuleImport(){moduleImportPreview=null}
    fun cancelModuleExport(){moduleExport=null}
    fun prepareModuleExport(module:DataModule,format:String){
        if(busy)return;val current=state?:return;busy=true
        viewModelScope.launch{try{moduleExport=withContext(Dispatchers.IO){when(format){"NATIVE"->ModuleExchange.nativeExport(current,module,LocalClock.today());"V6"->ModuleExchange.legacyFlowExport(current,LocalClock.today());else->ModuleExchange.tableExport(current,format=="XLSX")}}}catch(e:Exception){notice="未导出：${e.message}"}finally{busy=false}}
    }
    fun writeModuleExport(uri:Uri){val prepared=moduleExport?:return;if(busy)return;busy=true
        viewModelScope.launch{try{withContext(Dispatchers.IO){getApplication<Application>().contentResolver.openOutputStream(uri,"wt")?.use{it.write(prepared.bytes);it.flush()}?:error("无法写入所选文件")};moduleExport=null;notice="模块备份已导出，本机数据未改"}catch(e:Exception){notice="导出失败：${e.message}"}finally{busy=false}}
    }
    fun previewModuleImport(uri:Uri,module:DataModule){if(busy)return;val current=state?:return;busy=true
        viewModelScope.launch{try{moduleImportPreview=withContext(Dispatchers.IO){val out=ByteArrayOutputStream();getApplication<Application>().contentResolver.openInputStream(uri)?.use{input->val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=JsonLedgerCodec.MAX_BYTES){"文件超过64MB"};out.write(buffer,0,n)}}?:error("无法读取模块文件");ModuleExchange.nativePreview(out.toString("UTF-8"),module).also{DataModules.restore(current,it.data,it.module,LocalClock.today(),it.key)}}}catch(e:Exception){notice="未导入，数据未变：${e.message}"}finally{busy=false}}
    }
    fun confirmModuleImport(){val p=moduleImportPreview?:return;migrate("所选模块已替换，另一模块已保留",{moduleImportPreview=null}){DataModules.restore(it,p.data,p.module,LocalClock.today(),p.key)}}
}
