package com.zdownloader.app.engine

import java.math.BigDecimal

internal object OutputDuration {
    private val minimumTolerance=BigDecimal("0.5")
    private val relativeTolerance=BigDecimal("0.03")

    fun matches(actual:Double,expected:Double):Boolean {
        if(!actual.isFinite()||!expected.isFinite()) return false
        val target=BigDecimal.valueOf(expected)
        // Compare decimal media times without subtraction rounding outside an inclusive boundary.
        val difference=BigDecimal.valueOf(actual).subtract(target).abs()
        return difference<=target.multiply(relativeTolerance).max(minimumTolerance)
    }
}
