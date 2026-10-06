package com.deepseek.harness.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deepseek.harness.api.ChatEngine
import com.deepseek.harness.api.InferenceClient
import com.deepseek.harness.api.MessageSink
import com.deepseek.harness.api.PlatformClient
import com.deepseek.harness.api.PlatformException
import com.deepseek.harness.auth.CallbackServer
import com.deepseek.harness.auth.Pkce
import com.deepseek.harness.data.ConversationStore
import com.deepseek.harness.data.CredentialStore
import com.deepseek.harness.model.Account
import com.deepseek.harness.model.Balance
import com.deepseek.harness.model.BUILTIN_MODELS
import com.deepseek.harness.model.ChatMessage
import com.deepseek.harness.model.Conversation
import com.deepseek.harness.model.ModelInfo
import com.deepseek.harness.shell.ShizukuManager
import com.deepseek.harness.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

data class UiState(
    val signedIn: Boolean = false,
    val account: Account? = null,
    val balance: Balance? = null,
    val balanceError: String? = null,
    val loadingBalance: Boolean = false,
    val conversations: List<Conversation> = emptyList(),
    val current: Conversation? = null,
    val sending: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val model: String = "deepseek-flash",
    val showReasoning: Boolean = false,
    val signingIn: Boolean = false,
    val shizukuInstalled: Boolean = false,
    val shizukuRunning: Boolean = false,
    val shizukuGranted: Boolean = false,
    val storageGranted: Boolean = false
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val credentials = CredentialStore(app)
    private val conversations = ConversationStore(app)
    private val platform = PlatformClient()
    private val inference = InferenceClient(
        tokenProvider = { credentials.loadToken() },
        userIdProvider = { credentials.anonymousUserId() },
        sessionIdProvider = { System.currentTimeMillis() }
    )
    private val tools = ToolRegistry(app, File(app.filesDir, "workspace"))
    private val engine = ChatEngine(inference, tools)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val models: List<ModelInfo> = BUILTIN_MODELS

    init {
        val token = credentials.loadToken()
        _state.value = _state.value.copy(
            signedIn = !token.isNullOrEmpty(),
            conversations = conversations.list(),
            model = credentials.loadModel()
        )
        if (!token.isNullOrEmpty()) {
            refreshAccount()
        }
        refreshPermissions()
    }

    fun refreshPermissions() {
        val context = getApplication<Application>()
        _state.value = _state.value.copy(
            shizukuInstalled = ShizukuManager.isInstalled(context),
            shizukuRunning = ShizukuManager.isRunning(),
            shizukuGranted = ShizukuManager.hasPermission(),
            storageGranted = hasStorageAccess(context)
        )
    }

    private fun hasStorageAccess(context: android.content.Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    fun requestShizukuPermission() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    ShizukuManager.requestPermission { granted -> cont.resume(granted) }
                }
            }
            _state.value = _state.value.copy(
                shizukuGranted = result,
                info = if (result) "Shizuku 权限已授予" else "Shizuku 权限被拒绝"
            )
            refreshPermissions()
        }
    }

    fun openShizukuInstall() {
        val context = getApplication<Application>()
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        val target = intent ?: android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://shizuku.rikka.app/download/")
        )
        target.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(target) }
            .onFailure { _state.value = _state.value.copy(error = "无法打开 Shizuku") }
    }

    fun requestStorageAccess() {
        val context = getApplication<Application>()
        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
        } else {
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { _state.value = _state.value.copy(error = "无法打开系统设置") }
    }

    fun dismissMessages() {
        _state.value = _state.value.copy(error = null, info = null)
    }

    fun setModel(model: String) {
        credentials.saveModel(model)
        _state.value = _state.value.copy(model = model)
    }

    fun toggleReasoning() {
        _state.value = _state.value.copy(showReasoning = !_state.value.showReasoning)
    }

    fun refreshAccount() {
        val token = credentials.loadToken() ?: return
        _state.value = _state.value.copy(loadingBalance = true, balanceError = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val account = platform.fetchAccount(token)
                    val balance = platform.fetchBalance(token)
                    account to balance
                }
            }
            result.onSuccess { (account, balance) ->
                _state.value = _state.value.copy(
                    signedIn = true,
                    account = account,
                    balance = balance,
                    loadingBalance = false,
                    balanceError = null
                )
            }.onFailure { e ->
                val code = (e as? PlatformException)?.code
                if (code == "expired") {
                    credentials.clearToken()
                    _state.value = _state.value.copy(
                        signedIn = false,
                        account = null,
                        balance = null,
                        loadingBalance = false,
                        error = "登录已失效，请重新登录"
                    )
                } else {
                    _state.value = _state.value.copy(
                        loadingBalance = false,
                        balanceError = e.message ?: "余额获取失败"
                    )
                }
            }
        }
    }

    fun signIn(onOpenBrowser: (String) -> Unit) {
        if (_state.value.signingIn) return
        _state.value = _state.value.copy(error = null, info = null, signingIn = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val verifier = Pkce.verifier()
                    val challenge = Pkce.challenge(verifier)
                    val stateValue = Pkce.state()
                    val server = CallbackServer()
                    val redirectUri = server.start()
                    try {
                        val init = platform.authInit(
                            challenge,
                            stateValue,
                            redirectUri,
                            locale(),
                            "desktop"
                        )
                        SignInStart(init, server, verifier, stateValue, redirectUri)
                    } catch (e: Exception) {
                        server.stop()
                        throw e
                    }
                }
            }
            result.onSuccess { start ->
                onOpenBrowser(start.init.authorizeUrl)
                _state.value = _state.value.copy(info = "已打开浏览器，请在网页中完成登录")
                viewModelScope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        val received = start.server.await(start.init.expiresInSeconds * 1000L)
                        if (received == null) {
                            val message = start.server.error() ?: "登录失败"
                            start.server.stop()
                            return@withContext Result.failure<Account?>(IllegalStateException(message))
                        }
                        val (code, returnedState) = received
                        if (returnedState != start.state) {
                            start.server.stop()
                            return@withContext Result.failure<Account?>(IllegalStateException("state 校验失败"))
                        }
                        runCatching {
                            val auth = platform.authExchange(
                                code,
                                start.verifier,
                                start.redirectUri,
                                credentials.deviceId(),
                                deviceModel(),
                                osVersion()
                            )
                            credentials.saveToken(auth.token)
                            auth.account
                        }.also { start.server.stop() }
                    }
                    outcome.onSuccess {
                        _state.value = _state.value.copy(signedIn = true, account = it, info = "登录成功", signingIn = false)
                        refreshAccount()
                    }.onFailure {
                        _state.value = _state.value.copy(error = it.message ?: "登录失败", signingIn = false)
                    }
                }
            }.onFailure {
                _state.value = _state.value.copy(error = it.message ?: "登录启动失败", signingIn = false)
            }
        }
    }

    private data class SignInStart(
        val init: com.deepseek.harness.api.AuthInit,
        val server: CallbackServer,
        val verifier: String,
        val state: String,
        val redirectUri: String
    )

    fun signOut() {
        val token = credentials.loadToken()
        credentials.clearToken()
        _state.value = _state.value.copy(signedIn = false, account = null, balance = null, info = "已退出登录")
        if (token != null) {
            viewModelScope.launch { withContext(Dispatchers.IO) { platform.logout(token) } }
        }
    }

    fun newConversation() {
        val conversation = conversations.create()
        _state.value = _state.value.copy(
            conversations = conversations.list(),
            current = conversation
        )
    }

    fun openConversation(id: String) {
        val conversation = conversations.load(id) ?: return
        _state.value = _state.value.copy(current = conversation)
    }

    fun deleteConversation(id: String) {
        conversations.delete(id)
        val current = _state.value.current
        _state.value = _state.value.copy(
            conversations = conversations.list(),
            current = if (current?.id == id) null else current
        )
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.sending) return
        val conversation = _state.value.current ?: conversations.create().also {
            _state.value = _state.value.copy(current = it, conversations = conversations.list())
        }
        conversation.messages.add(ChatMessage("user", trimmed))
        if (conversation.title == "新会话") {
            conversation.title = trimmed.take(20)
        }
        conversation.updatedAt = System.currentTimeMillis()
        conversations.save(conversation)
        _state.value = _state.value.copy(
            current = conversation.copy(messages = conversation.messages.toMutableList()),
            sending = true,
            conversations = conversations.list()
        )

        val history = conversation.messages.toList()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val sink = object : MessageSink {
                    override fun append(message: ChatMessage): Int {
                        conversation.messages.add(message)
                        publish(conversation)
                        return conversation.messages.size - 1
                    }

                    override fun update(index: Int, message: ChatMessage) {
                        if (index in conversation.messages.indices) {
                            conversation.messages[index] = message
                            publish(conversation)
                        }
                    }
                }
                engine.run(_state.value.model, history, sink) {}
                conversation.updatedAt = System.currentTimeMillis()
                conversations.save(conversation)
            }
            _state.value = _state.value.copy(
                sending = false,
                conversations = conversations.list()
            )
        }
    }

    private fun publish(conversation: Conversation) {
        _state.value = _state.value.copy(
            current = conversation.copy(messages = conversation.messages.toMutableList())
        )
    }

    fun stop() {
        _state.value = _state.value.copy(sending = false)
    }

    private fun locale(): String =
        if (java.util.Locale.getDefault().language == "zh") "zh_CN" else "en_US"

    private fun deviceModel(): String = "android-${android.os.Build.MODEL}"

    private fun osVersion(): String = "Android ${android.os.Build.VERSION.RELEASE}"

    companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}