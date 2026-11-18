package com.zdownloader.app.ui

import androidx.compose.runtime.Composable

/**
 * Unified Facebook authentication bridge.
 * Reuses MediaFlow's existing BrowserScreen and BrowserWebViewHost infrastructure.
 * Eliminates duplicate WebViews, ensuring shared cookie persistence and renderer crash recovery.
 */
@Composable
fun FacebookLoginDialog(
    onDismiss: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    BrowserAuthDialog(
        initialUrl = "https://m.facebook.com/login/",
        onDismiss = onDismiss,
        onAuthSuccess = onLoginSuccess
    )
}
