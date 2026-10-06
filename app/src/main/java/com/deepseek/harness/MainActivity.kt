package com.deepseek.harness

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    LaunchedEffect(state.error, state.info) {
        val message = state.error ?: state.info
        if (message != null) {
            snackbarHost.showSnackbar(message)
            viewModel.dismissMessages()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHost) }) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (!state.signedIn) {
                SignInScreen(
                    busy = state.loadingBalance,
                    statusText = state.info ?: state.error,
                    onSignIn = { viewModel.signIn(onOpenBrowser) }
                )
            } else {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet {
                            AppDrawer(
                                accountName = state.account?.name ?: "DeepSeek 用户",
                                accountContact = state.account?.contact.orEmpty(),
                                balanceText = state.balance?.normalText() ?: "--",
                                bonusText = state.balance?.bonusText() ?: "--",
                                balanceError = state.balanceError,
                                loadingBalance = state.loadingBalance,
                                conversations = state.conversations,
                                currentId = state.current?.id,
                                onNewConversation = {
                                    viewModel.newConversation()
                                    scope.launch { drawerState.close() }
                                },
                                onOpenConversation = {
                                    viewModel.openConversation(it)
                                    scope.launch { drawerState.close() }
                                },
                                onDeleteConversation = { viewModel.deleteConversation(it) },
                                onRefresh = { viewModel.refreshAccount() },
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