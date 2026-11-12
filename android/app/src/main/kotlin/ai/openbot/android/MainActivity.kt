package ai.openbot.android

import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.Manifest
import ai.openbot.core.*
import ai.openbot.android.notifications.LocalNotifications
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import ai.openbot.android.ui.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var notificationUrl by mutableStateOf<String?>(null)
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent);setIntent(intent);notificationUrl=intent.dataString
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationUrl=intent.dataString
        enableEdgeToEdge()
        setContent {
            val factory=remember {viewModelFactory {initializer {OpenBotViewModel(application)}}}
            val vm: OpenBotViewModel = viewModel(factory=factory)
            val state by vm.ui.collectAsStateWithLifecycle()
            val night=state.preferences.darkTheme?:isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window,window.decorView).apply {
                    isAppearanceLightStatusBars=!night
                    isAppearanceLightNavigationBars=!night
                }
            }
            OpenBotTheme(state.preferences.darkTheme) { SourceDialogHost { OpenBotApp(vm,state,notificationUrl){notificationUrl=null;setIntent(Intent(intent).setData(null))} } }
        }
    }
}

@Composable private fun OpenBotApp(vm:OpenBotViewModel,state:AppState,notificationUrl:String?,onLinkConsumed:()->Unit) {
    val backDispatcher=LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var stack by rememberSaveable { mutableStateOf(listOf("Home")) }
    val route=stack.last()
    val pages=rememberSaveableStateHolder()
    val channel=state.conversations.find {it.id==state.selectedConversation}
    val bot=state.bots.find {it.id==(channel?.botId?:state.draftBotId)}
    val composing=state.draftBotId!=null
    var inboxChannel by rememberSaveable{mutableStateOf<String?>(null)}
    var inboxRequest by rememberSaveable{mutableStateOf<String?>(null)}
    var notificationNotice by rememberSaveable{mutableStateOf<String?>(null)}
    val context=androidx.compose.ui.platform.LocalContext.current
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        vm.savePreferences(state.preferences.copy(notificationsEnabled=granted))
        notificationNotice=if(granted)null else "Notifications are off. You can enable them in Android settings."
    }
    val currentNotificationUrl by rememberUpdatedState(notificationUrl)
    LaunchedEffect(notificationUrl,state.busy,state.bots.isNotEmpty()) {
        val url=notificationUrl
        if(url!=null&&!state.busy&&state.bots.isNotEmpty()) {
            val destination=notificationDestination(url)
            if(destination==null||destination is NotificationDestination.NeedsYou)onLinkConsumed()
            else {
                // The ViewModel reads SQLite, rather than dropping a fresh link because a UI
                // snapshot has not observed its newly created conversation yet.
                while(!vm.openNotification(destination){opened->
                    if(currentNotificationUrl==url) {
                        if(opened&&destination is NotificationDestination.Approvals) {
                            inboxChannel=destination.channelId;inboxRequest=destination.requestId;stack=listOf("Home","Approvals")
                        } else if(opened){inboxChannel=null;inboxRequest=null}
                        onLinkConsumed()
                    }
                })kotlinx.coroutines.delay(50)
            }
        }
    }
    val drawer=rememberDrawerState(DrawerValue.Closed)
    val scope=rememberCoroutineScope()
    val p=LocalOpenBotPalette.current
    val importModel=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let(vm::importModel)}
    fun navigate(destination:String) {
        inboxChannel=null;inboxRequest=null
        vm.openConversation(null)
        if(stack.last()!=destination)stack=stack+destination
        scope.launch{drawer.close()}
    }
    fun home() {vm.openConversation(null);stack=listOf("Home");scope.launch{drawer.close()}}
    fun back() {
        if(channel!=null||composing)vm.openConversation(null)
        else if(stack.size>1)stack=stack.dropLast(1)
    }
    BackHandler(channel!=null||composing||stack.size>1||drawer.isOpen) {
        if(drawer.isOpen)scope.launch{drawer.close()} else back()
    }
    Surface(Modifier.fillMaxSize(),color=p.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
            ModalNavigationDrawer(drawerState=drawer,gesturesEnabled=!LocalSourceDialogOpen.current,drawerContent={
                ModalDrawerSheet(Modifier.width(288.dp),drawerShape=RectangleShape,drawerContainerColor=p.sidebar,drawerTonalElevation=0.dp) {
                    AppSidebar(state,vm,route,::navigate,{id->vm.openConversation(id);scope.launch{drawer.close()}},::home)
                }
            }) {
                Column(Modifier.fillMaxSize().imePadding()) {
                    Row(Modifier.fillMaxWidth().height(if(route=="Home"&&channel==null&&!composing)48.dp else 56.dp).padding(horizontal=12.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        IconButton(onClick={scope.launch{drawer.open()}},modifier=Modifier.size(36.dp)) {TablerIcon(R.drawable.ic_layout_sidebar,"Show sidebar")}
                        if(channel!=null||composing||stack.size>1)OpenBotTextButton(onClick={backDispatcher?.onBackPressed()}) {TablerIcon(R.drawable.ic_chevron_left);Spacer(Modifier.width(4.dp));Text("Back")}
                        if(channel!=null||composing)Text(channel?.name?:bot?.name.orEmpty(),style=MaterialTheme.typography.titleSmall,maxLines=1)
                    }
                    if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp),color=p.ring,trackColor=p.muted)
                    state.error?.let {message->
                        Surface(color=MaterialTheme.colorScheme.errorContainer) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(message,Modifier.semantics{liveRegion=LiveRegionMode.Assertive},color=p.destructive)
                                OpenBotTextButton(onClick=vm::dismissError){Text("Dismiss")}
                            }
                        }
                    }
                    pages.SaveableStateProvider(channel?.let{"conversation:${it.id}"}?:if(composing)"draft:${state.draftSessionId}" else "page:$route") {
                    if(channel!=null||composing)ConversationScreen(state,bot,vm)
                    else when(route) {
                        "Home"->HomeScreen(state,vm)
                        "Bots"->BotsScreen(state,vm)
                        "Capabilities"->CapabilitiesScreen(state,vm)
                        "Connections"->ConnectionsScreen(state,vm)
                        "Approvals"->ApprovalsScreen(state,vm,inboxChannel,inboxRequest)
                        "Routines"->RoutinesScreen(state,vm)
                        "Settings"->SettingsScreen(state,vm,{navigate("Model")},{navigate("Rules")},{enabled->
                            notificationNotice=null
                            if(!enabled||LocalNotifications.allowed(context))vm.savePreferences(state.preferences.copy(notificationsEnabled=enabled))
                            else if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,context.packageName))
                        },notificationNotice)
                        "Rules"->ApprovalRulesScreen(state,vm)
                        "Dictation"->DictationSettingsScreen(state,vm)
                        "Model"->ModelScreen(state,vm,onDictation={navigate("Dictation")}){importModel.launch(arrayOf("*/*"))}
                    }
                    }
                }
            }
        }
    }
}
