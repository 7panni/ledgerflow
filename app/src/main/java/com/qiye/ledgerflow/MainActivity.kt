package com.qiye.ledgerflow

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.qiye.ledgerflow.ui.LedgerApp

class MainActivity : ComponentActivity() {
    private var quickEntryRequest by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        LedgerFlowTiming.mark("activity_create_start")
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        quickEntryRequest = savedInstanceState?.getInt("quickEntryRequest") ?: 0
        if (savedInstanceState == null && intent.action == ACTION_QUICK_ENTRY) quickEntryRequest++
        if (intent.action == ACTION_QUICK_ENTRY) setIntent(Intent(intent).setAction(Intent.ACTION_MAIN))
        window.decorView.viewTreeObserver.addOnDrawListener { LedgerFlowTiming.once("first_draw") }
        setContent { LedgerApp(quickEntryRequest) }
        LedgerFlowTiming.mark("activity_content_set")
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_QUICK_ENTRY) {
            quickEntryRequest++
            setIntent(Intent(intent).setAction(Intent.ACTION_MAIN))
        } else setIntent(intent)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("quickEntryRequest", quickEntryRequest)
        super.onSaveInstanceState(outState)
    }
    companion object { const val ACTION_QUICK_ENTRY = "com.qiye.ledgerflow.QUICK_ENTRY" }
}
