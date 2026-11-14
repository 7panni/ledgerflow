package com.qiye.ledgerflow.data

import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.cashflow.data.AccountType
import com.qiye.ledgerflow.ui.*
import org.junit.Assert.*
import org.junit.Test

class QuickAccountSelectionTest {
    private val day=LedgerCalendar.parse("2026-10-29")
    private fun accounts():LedgerState {
        var s=LedgerState()
        listOf("现金" to AccountType.CASH,"银行" to AccountType.CMB,"微信" to AccountType.WECHAT,"支付宝" to AccountType.ALIPAY,"备用" to AccountType.CUSTOM).forEach{(name,type)->s=RichLedger.saveAccount(s,Account("",0,name,50000,accountType=type))}
        return s
    }
    private fun reject(block:()->Unit){var failed=false;try{block()}catch(expected:Exception){failed=true};assertTrue(failed)}
    @Test fun existingCommonAccountsAppearInOrderAndOthersStayInMenu() {
        val s=accounts();val choices=quickAccountChoices(s.accounts)
        assertEquals(listOf("银行","微信","支付宝"),choices.quick.map{it.name})
        assertEquals(listOf("现金","备用"),choices.other.map{it.name})
        val renamed=s.accounts.map{if(it.name=="银行")it.copy(name="自制实际银行")else it}
        assertEquals("自制实际银行",quickAccountChoices(renamed).quick.first().name)
        val filtered=quickAccountChoices(s.accounts.map{when(it.name){"银行"->it.copy(archived=true);"微信"->it.copy(active=false);else->it}})
        assertEquals(listOf("支付宝"),filtered.quick.map{it.name});assertTrue(filtered.other.none{it.archived||!it.active})
    }
    @Test fun noSelectedAccountIsRejectedAndEachExplicitChoiceUsesExistingAccount() {
        val s=accounts();val draft=EntryDraft(EntryKind.EXPENSE,1234,day,600,"自制支出")
        reject{requireQuickEntryAccounts(s,draft)}
        for(name in listOf("银行","微信","支付宝","现金")) {
            val chosen=s.accounts.single{it.name==name};val selected=draft.copy(accountId=chosen.id)
            requireQuickEntryAccounts(s,selected)
            val after=LedgerImport.saveCategorizedEntry(s,selected,day)
            assertEquals(chosen.id,after.entries.single().accountId)
            assertEquals(HomeAssets.calculate(s).totalCnyMinor!!-1234,HomeAssets.calculate(after).totalCnyMinor)
        }
    }
    @Test fun editingOldDetachedImportAfterChoosingAccountDoesNotReplayFunding() {
        var s=accounts()
        s=LedgerImport.saveCategorizedEntry(s,EntryDraft(EntryKind.EXPENSE,1000,day-1,600,"自制历史",affectsBalance=false,affectsBudget=false),day)
        val old=s.entries.single();val before=LedgerEngine.balances(s)
        val draft=EntryDraft(old.kind,2000,old.epochDay,old.minuteOfDay,old.category,s.accounts.first().id,categoryId=old.categoryId).withQuickEntryHistory(old)
        requireQuickEntryAccounts(s,draft)
        val after=LedgerImport.saveCategorizedEntry(s,draft,day,old.id)
        assertEquals(before,LedgerEngine.balances(after));assertEquals(old.id,after.entries.single().id)
        assertFalse(after.entries.single().affectsBalance);assertFalse(after.entries.single().affectsBudget)
    }
    @Test fun incompatibleCurrencyClearsSelectionAndRequiresExplicitReselection() {
        var s=accounts();val cny=s.accounts.first()
        assertNull(quickAccountForCurrency(cny,"USD"))
        reject{requireQuickEntryAccounts(s,EntryDraft(EntryKind.EXPENSE,100,day,600,"自制美元",quickAccountForCurrency(cny,"USD"),currencyCode="USD"))}
        s=RichLedger.saveAccount(s,Account("",0,"自制美元",10000,"USD"));val usd=s.accounts.last()
        requireQuickEntryAccounts(s,EntryDraft(EntryKind.EXPENSE,100,day,600,"自制美元",usd.id,currencyCode="USD"))
        val crypto=Account("crypto",999,"自制数字币",0,"CRYPTO",AccountType.CRYPTO)
        assertEquals(crypto.id,quickAccountForCurrency(crypto,"BTC"));assertNull(quickAccountForCurrency(crypto,"CNY"))
    }
    @Test fun transfersStillRequireDifferentExistingSameCurrencyAccounts() {
        val s=accounts();val first=s.accounts.first();val second=s.accounts[1]
        val draft=EntryDraft(EntryKind.TRANSFER,100,day,600,"内部转账",first.id,second.id)
        requireQuickEntryAccounts(s,draft)
        reject{requireQuickEntryAccounts(s,draft.copy(targetAccountId=null))}
        reject{requireQuickEntryAccounts(s,draft.copy(targetAccountId=first.id))}
        val other=RichLedger.saveAccount(s,Account("",0,"自制美元",1000,"USD"))
        reject{requireQuickEntryAccounts(other,draft.copy(targetAccountId=other.accounts.last().id))}
    }
}
