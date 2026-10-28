package com.qiye.ledgerflow.data

import com.qiye.ledgerflow.core.LedgerState
import org.junit.Assert.*
import org.junit.Test

class JsonIntegerMappingTest {
    private val codec=JsonLedgerCodec()
    private fun decodeSequence(value:String)=codec.decode(codec.encode(LedgerState()).replace("\"nextSequence\": 1","\"nextSequence\": $value"))
    @Test fun exactIntegersSurviveIntLongAndBeyondDoublePrecision() {
        for(value in listOf(1L,Int.MAX_VALUE.toLong(),Int.MAX_VALUE+1L,9_007_199_254_740_993L,Long.MAX_VALUE-1))assertEquals(value,decodeSequence(value.toString()).nextSequence)
    }
    @Test fun fractionalQuotedBooleanAndOverflowValuesRemainRejected() {
        for(value in listOf("1.5","1.0","1e3","\"1\"","true","null","9223372036854775808","-9223372036854775809")){
            var rejected=false
            try{decodeSequence(value)}catch(expected:Exception){rejected=true}
            assertTrue("Invalid integer accepted: $value",rejected)
        }
    }
}
