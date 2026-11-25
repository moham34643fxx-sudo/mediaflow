package com.zdownloader.app.adblock

import org.junit.Assert.assertEquals
import org.junit.Test

class CosmeticSelectorOrderTest {
    @Test
    fun matchingBannersSurviveAnOverfullPageRuleBatch() {
        val elementMatches = listOf(".adsbygoogle-box", ".ad_banner")
        val pageSelectors = List(432) { ".page-rule-$it" }

        val selectors = prioritizeCosmeticSelectors(elementMatches, pageSelectors).toList()

        assertEquals(elementMatches, selectors.take(2))
        assertEquals(256, selectors.size)
        assertEquals(".page-rule-253", selectors.last())
    }

    @Test
    fun selectorsRepeatedInBothBatchesAreEmittedOnceWithPageFallback() {
        val selectors = prioritizeCosmeticSelectors(
            listOf(".ad_banner"), listOf(".page-ad", ".ad_banner", "#promo")
        ).toList()

        assertEquals(listOf(".ad_banner", ".page-ad", "#promo"), selectors)
    }
}
