package com.qiye.ledgerflow

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log

/** Fixed stage names and numeric timing/counts only. Disabled in plain JVM tests. */
internal object LedgerFlowTiming {
    @Volatile private var origin = -1L
    private val seen = mutableSetOf<String>()
    @Synchronized fun start() { if(origin<0)origin=SystemClock.elapsedRealtime() }
    @Synchronized fun mark(stage:String,count:Long?=null,duration:Long?=null) {
        if(origin<0)return
        Log.i("LedgerFlowTiming","stage=$stage t_ms=${SystemClock.elapsedRealtime()-origin}"+(duration?.let{" duration_ms=$it"}?:"")+(count?.let{" count=$it"}?:""))
    }
    @Synchronized fun once(stage:String,count:Long?=null) { if(origin>=0&&seen.add(stage))mark(stage,count) }
    fun <T> measure(stage:String,count:Long?=null,block:()->T):T {
        if(origin<0)return block()
        val start=SystemClock.elapsedRealtime()
        try{return block()}finally{mark(stage,count,SystemClock.elapsedRealtime()-start)}
    }
}

class LedgerApplication:Application() {
    override fun attachBaseContext(base:Context) {
        LedgerFlowTiming.start();LedgerFlowTiming.mark("application_attach")
        super.attachBaseContext(base)
    }
    override fun onCreate() {
        LedgerFlowTiming.mark("application_create_start")
        super.onCreate()
        LedgerFlowTiming.mark("application_create_end")
    }
}
