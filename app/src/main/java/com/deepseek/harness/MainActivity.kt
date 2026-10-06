package com.deepseek.harness

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.deepseek.harness.ui.AppDrawer
import com.deepseek.harness.ui.AppViewModel
import com.deepseek.harness.ui.ChatScreen
import com.deepseek.harness.ui.DshTheme
import com.deepseek.harness.ui.SignInScreen
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DshTheme {
                AppRoot(viewModel) { url -> openBrowser(url) }
            }
        }
    }

    private fun openBrowser(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}

@Composable
private fun AppRoot(viewModel: AppViewModel, onOpenBrowser: (String) -> Unit) {
    val state by viewModel.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.error, state.signedIn) {
        val message = state.error
        if (message != null && state.signedIn) {
            snackbarHost.showSnackbar(message)
            viewModel.dismissMessages()
        }
    }

    LaunchedEffect(state.info, state.signedIn) {
        val message = state.info
        if (message != null && state.signedIn) {
            snackbarHost.showSnackbar(message)
            viewModel.dismissMessages()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHost) }) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (!state.signedIn) {
                SignInScreen(
                    busy = state.signingIn,
                    statusText = state.info ?: state.error,
                    onSignIn = { viewModel.signIn(onOpenBrowser) }
                )
            } else {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet {
                            AppDrawer(
                                accounts = state.accounts,
                                activeId = state.activeId,
                                totalBalanceText = state.totalBalanceText,
                                autoSwitch = state.autoSwitch,
                                loadingBalance = state.loadingBalance,
                                conversations = state.conversations,
                                currentId = state.current?.id,
                                shizukuInstalled = state.shizukuInstalled,
                                shizukuRunning = state.shizukuRunning,
                                shizukuGranted = state.shizukuGranted,
                                storageGranted = state.storageGranted,
                                onAddAccount = {
                                    viewModel.signIn(onOpenBrowser)
                                    scope.launch { drawerState.close() }
                                },
                                onSwitchAccount = { viewModel.switchAccount(it) },
                                onRemoveAccount = { viewModel.removeAccount(it) },
                                onToggleAutoSwitch = { viewModel.toggleAutoSwitch() },
                                onNewConversation = {
                                    viewModel.newConversation()
                                    scope.launch { drawerState.close() }
                                },
                                onOpenConversation = {
                                    viewModel.openConversation(it)
                                    scope.launch { drawerState.close() }
                                },
                                onDeleteConversation = { viewModel.deleteConversation(it) },
                                onRefresh = {
                                    viewModel.refreshAccount()
                                    viewModel.refreshPermissions()
                                },
                                onRequestShizuku = { viewModel.requestShizukuPermission() },
                                onInstallShizuku = { viewModel.openShizukuInstall() },
                                onRequestStorage = { viewModel.requestStorageAccess() },
                                onSignOut = {
                                    viewModel.signOut()
                                    scope.launch { drawerState.close() }
                                }
                            )
                        }
                    }
                ) {
                    ChatScreen(
                        conversation = state.current,
                        sending = state.sending,
                        model = state.model,
                        models = viewModel.models,
                        showReasoning = state.showReasoning,
                        onMenu = { scope.launch { drawerState.open() } },
                        onSend = { viewModel.send(it) },
                        onSelectModel = { viewModel.setModel(it) },
                        onToggleReasoning = { viewModel.toggleReasoning() }
                    )
                }
            }
        }
    }
}