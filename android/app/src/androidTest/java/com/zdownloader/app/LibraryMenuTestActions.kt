package com.zdownloader.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.espresso.Espresso

private fun ComposeTestRule.openLibraryMenu(menuTag:String) {
    val trigger=onNodeWithTag(menuTag)
    // The source picker is pinned; view and sort controls belong to lazy content.
    if(menuTag!="library_source_menu") trigger.performScrollTo()
    trigger.performClick()
}

internal fun ComposeTestRule.selectLibraryMenuOption(menuTag:String,optionTag:String) {
    openLibraryMenu(menuTag)
    onNodeWithTag(optionTag).performClick()
}

internal fun ComposeTestRule.assertLibraryMenuSelection(menuTag:String,optionTag:String) {
    openLibraryMenu(menuTag)
    onNodeWithTag(optionTag).assertIsSelected()
    Espresso.pressBack()
}
