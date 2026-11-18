package com.zdownloader.app.ui

import androidx.compose.runtime.*
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.zdownloader.app.R

/** Canonical destinations shared by the graph, controls and navigation actions. */
internal enum class PrimaryDestination(val route:String,val label:Int) {
    HOME("home",R.string.home), DOWNLOADS("downloads",R.string.downloads), LIBRARY("library",R.string.library)
}
internal enum class SecondaryDestination(val route:String) {
    PREVIEW("preview"), SETTINGS("settings"), STORAGE("storage_management"), STATUS("status_saver"), BROWSER("browser"), EXTRACT_AUDIO("extract_audio"), LOCAL_MEDIA("local_media")
}
internal object MediaFlowGraphs {
    const val PRIMARY="mediaflow.primary"
    const val SECONDARY="mediaflow.secondary"
    const val WELCOME="mediaflow.welcome"
}
@Composable internal fun rememberMediaFlowController():NavHostController=rememberNavController()
internal fun NavDestination?.primaryDestination():PrimaryDestination? {
    val destination=this?.hierarchy?.firstOrNull {it.parent?.route==MediaFlowGraphs.PRIMARY} ?: return null
    return PrimaryDestination.entries.firstOrNull {it.route==destination.route}
}
internal fun NavDestination?.isPrimaryDestination()=this?.hierarchy?.any {it.route==MediaFlowGraphs.PRIMARY}==true
internal fun primaryTabTransition(from:NavDestination?,to:NavDestination?)=from.isPrimaryDestination()&&to.isPrimaryDestination()

/** Read the controller's current destination synchronously, not a delayed UI snapshot. */
internal fun NavHostController.selectPrimary(destination:PrimaryDestination) {
    if(currentDestination.primaryDestination()==destination) return
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) {saveState=true}
        launchSingleTop=true
        restoreState=true
    }
}
internal fun NavHostController.openSecondary(destination:SecondaryDestination) {
    if(currentDestination?.route==destination.route) return
    navigate(destination.route) {launchSingleTop=true}
}
/** A delayed/repeated secondary Back event must never pop a primary root. */
internal fun NavHostController.closeSecondary():Boolean =
    if(currentDestination?.hierarchy?.any {it.route==MediaFlowGraphs.SECONDARY}==true) popBackStack() else false

/** Modal/detail composition owns suppression; nested overlays cannot restore it early. */
@Stable internal class PrimaryNavigationVisibility {
    private val owners=mutableStateMapOf<Any,Unit>()
    val suppressed get()=owners.isNotEmpty()
    fun acquire(owner:Any) {owners[owner]=Unit}
    fun release(owner:Any) {owners.remove(owner)}
}
internal val LocalPrimaryNavigationVisibility=staticCompositionLocalOf<PrimaryNavigationVisibility?> {null}
@Composable internal fun SecondaryNavigationOverlay() {
    val host=LocalPrimaryNavigationVisibility.current
    val owner=remember {Any()}
    DisposableEffect(host,owner) {host?.acquire(owner);onDispose {host?.release(owner)}}
}
