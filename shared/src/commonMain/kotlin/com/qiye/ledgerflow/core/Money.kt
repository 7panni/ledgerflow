package com.qiye.ledgerflow.core

object Money {
    const val LIMIT: Long = 9_000_000_000_000_000L

    fun checked(value: Long): Long {
        require(value in -LIMIT..LIMIT) { "金额超出可支持范围" }
        return value
    }

    fun add(a: Long, b: Long): Long {
        checked(a); checked(b)
        require(!(b > 0 && a > LIMIT - b) && !(b < 0 && a < -LIMIT - b)) { "金额合计超出可支持范围" }
        return a + b
    }

    fun parse(text: String, signed: Boolean = false): Long {
        val value = text.trim()
        val pattern = if (signed) Regex("-?\\d{1,14}(\\.\\d{1,2})?") else Regex("\\d{1,14}(\\.\\d{1,2})?")
        require(pattern.matches(value)) { "请输入金额，最多两位小数，不使用逗号或科学计数法" }
        val negative = value.startsWith("-")
        val parts = value.removePrefix("-").split('.')
        val minor = parts[0].toLong() * 100 + (parts.getOrNull(1) ?: "").padEnd(2, '0').toLong()
        return checked(if (negative) -minor else minor)
    }

    fun format(minor: Long): String {
        checked(minor)
        val absolute = if (minor < 0) -minor else minor
        return (if (minor < 0) "-" else "") + (absolute / 100) + "." + (absolute % 100).toString().padStart(2, '0')
    }
}
