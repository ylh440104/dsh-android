package com.deepseek.harness.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deepseek.harness.api.ChatEngine
import com.deepseek.harness.api.InferenceClient
import com.deepseek.harness.api.MessageSink
import com.deepseek.harness.api.PlatformClient
import com.deepseek.harness.api.PlatformException
import com.deepseek.harness.api.RunResult
import com.deepseek.harness.auth.CallbackServer
import com.deepseek.harness.auth.Pkce
import com.deepseek.harness.data.AccountStore
import com.deepseek.harness.data.ConversationStore
import com.deepseek.harness.data.CredentialStore
import com.deepseek.harness.model.BUILTIN_MODELS
import com.deepseek.harness.model.ChatMessage
import com.deepseek.harness.model.Conversation
import com.deepseek.harness.model.ModelInfo
import com.deepseek.harness.model.StoredAccount
import com.deepseek.harness.model.formatAmount
import com.deepseek.harness.shell.ShizukuManager
import com.deepseek.harness.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    val accounts: List<StoredAccount> = emptyList(),
    val activeId: String? = null,
    val autoSwitch: Boolean = true,
    val totalBalanceText: String = "--",
    val loadingBalance: Boolean = false,
    val conversations: List<Conversation> = emptyList(),
    val current: Conversation? = null,
    val sending: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val model: String = "deepseek-flash",
    val showReasoning: Boolean = false,
    val signingIn: Boolean = false,
    val signingInLabel: String = "",
    val shizukuInstalled: Boolean = false,
    val shizukuRunning: Boolean = false,
    val shizukuGranted: Boolean = false,
    val storageGranted: Boolean = false
) {
    val active: StoredAccount? get() = accounts.firstOrNull { it.localId == activeId }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val credentials = CredentialStore(app)
    private val accountStore = AccountStore(app)
    private val conversations = ConversationStore(app)
    private val platform = PlatformClient()
    private val inference = InferenceClient(
        tokenProvider = { activeToken() },
        userIdProvider = { credentials.anonymousUserId() },
        sessionIdProvider = { System.currentTimeMillis() }
    )
    private val tools = ToolRegistry(app, File(app.filesDir, "workspace"))
    private val engine = ChatEngine(inference, tools)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val models: List<ModelInfo> = BUILTIN_MODELS

    init {
        migrateLegacyToken()
        val accounts = accountStore.list()
        var activeId = accountStore.activeId()
        if (activeId == null || accounts.none { it.localId == activeId }) {
            activeId = accounts.firstOrNull()?.localId
        }
        accountStore.setActive(activeId)
        _state.value = _state.value.copy(
            signedIn = accounts.isNotEmpty(),
            accounts = accounts,
            activeId = activeId,
            autoSwitch = accountStore.autoSwitch(),
            totalBalanceText = totalText(accounts),
            conversations = conversations.list(),
            model = credentials.loadModel()
        )
        if (accounts.isNotEmpty()) refreshAllBalances()
        refreshPermissions()
    }

    private fun migrateLegacyToken() {
        val legacy = credentials.legacyToken() ?: return
        accountStore.add(legacy, null)
        credentials.clearLegacyToken()
    }

    private fun activeToken(): String? =
        _state.value.active?.token ?: accountStore.list().firstOrNull()?.token

    private fun totalText(accounts: List<StoredAccount>): String {
        if (accounts.isEmpty()) return "--"
        val total = accounts.sumOf { it.totalValue }
        return formatAmount(total)
    }

    private fun publishAccounts(accounts: List<StoredAccount>, activeId: String? = null) {
        val current = _state.value
        _state.value = current.copy(
            accounts = accounts,
            activeId = activeId ?: current.activeId,
            signedIn = accounts.isNotEmpty(),
            totalBalanceText = totalText(accounts)
        )
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

    fun toggleAutoSwitch() {
        val next = !_state.value.autoSwitch
        accountStore.setAutoSwitch(next)
        _state.value = _state.value.copy(
            autoSwitch = next,
            info = if (next) "额度用尽时自动切换账号" else "已关闭自动切换"
        )
    }

    fun switchAccount(localId: String) {
        val accounts = accountStore.list()
        val target = accounts.firstOrNull { it.localId == localId } ?: return
        accountStore.setActive(localId)
        _state.value = _state.value.copy(
            activeId = localId,
            accounts = accounts,
            info = "已切换到 ${target.displayName}"
        )
        refreshAccount(localId)
    }

    fun removeAccount(localId: String) {
        val removed = accountStore.list().firstOrNull { it.localId == localId }
        val accounts = accountStore.remove(localId)
        var activeId = _state.value.activeId
        if (activeId == localId) {
            activeId = accounts.firstOrNull()?.localId
            accountStore.setActive(activeId)
        }
        _state.value = _state.value.copy(
            accounts = accounts,
            activeId = activeId,
            signedIn = accounts.isNotEmpty(),
            totalBalanceText = totalText(accounts),
            info = "已移除 ${removed?.displayName ?: "账号"}"
        )
        if (removed != null) {
            viewModelScope.launch { withContext(Dispatchers.IO) { platform.logout(removed.token) } }
        }
    }

    fun refreshAllBalances() {
        val accounts = accountStore.list()
        if (accounts.isEmpty()) return
        _state.value = _state.value.copy(loadingBalance = true)
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                for (account in accounts) {
                    refreshOne(account)
                }
                accounts
            }
            accountStore.updateAll(updated)
            publishAccounts(updated)
            _state.value = _state.value.copy(loadingBalance = false)
        }
    }

    fun refreshAccount(localId: String) {
        viewModelScope.launch {
            val accounts = withContext(Dispatchers.IO) {
                val list = accountStore.list()
                val target = list.firstOrNull { it.localId == localId } ?: return@withContext list
                refreshOne(target)
                accountStore.updateAll(list)
                list
            }
            publishAccounts(accounts)
        }
    }

    private fun refreshOne(account: StoredAccount) {
        runCatching {
            val profile = platform.fetchAccount(account.token)
            val balance = platform.fetchBalance(account.token)
            account.name = profile.name ?: account.name
            account.contact = profile.contact ?: account.contact
            account.avatarUrl = profile.avatarUrl ?: account.avatarUrl
            account.remoteId = profile.id ?: account.remoteId
            accountStore.applyBalance(account, balance)
        }.onFailure { e ->
            if ((e as? PlatformException)?.code == "expired") {
                account.lastError = "登录已失效"
                account.quotaExhausted = true
            } else {
                account.lastError = e.message ?: "额度获取失败"
            }
        }
    }

    private fun pickNextAccount(currentId: String): StoredAccount? {
        val accounts = accountStore.list()
        if (accounts.size < 2) return null
        val startIndex = accounts.indexOfFirst { it.localId == currentId }
        if (startIndex < 0) return null
        for (offset in 1 until accounts.size) {
            val candidate = accounts[(startIndex + offset) % accounts.size]
            if (!candidate.quotaExhausted && candidate.lastError == null) return candidate
        }
        for (offset in 1 until accounts.size) {
            val candidate = accounts[(startIndex + offset) % accounts.size]
            if (!candidate.quotaExhausted) return candidate
        }
        return null
    }

    fun signIn(onOpenBrowser: (String) -> Unit) {
        if (_state.value.signingIn) return
        _state.value = _state.value.copy(error = null, info = null, signingIn = true, signingInLabel = "正在启动登录…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val verifier = Pkce.verifier()
                    val challenge = Pkce.challenge(verifier)
                    val stateValue = Pkce.state()
                    val server = CallbackServer()
                    val redirectUri = server.start()
                    try {
                        val init = platform.authInit(challenge, stateValue, redirectUri, locale(), "desktop")
                        SignInStart(init, server, verifier, stateValue, redirectUri)
                    } catch (e: Exception) {
                        server.stop()
                        throw e
                    }
                }
            }
            result.onSuccess { start ->
                onOpenBrowser(start.init.authorizeUrl)
                _state.value = _state.value.copy(
                    info = "已打开浏览器，请在网页中完成登录",
                    signingInLabel = "等待网页授权…"
                )
                viewModelScope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        val received = start.server.await(start.init.expiresInSeconds * 1000L)
                        if (received == null) {
                            val message = start.server.error() ?: "登录失败"
                            start.server.stop()
                            return@withContext Result.failure<com.deepseek.harness.api.AuthResult>(IllegalStateException(message))
                        }
                        val (code, returnedState) = received
                        if (returnedState != start.state) {
                            start.server.stop()
                            return@withContext Result.failure<com.deepseek.harness.api.AuthResult>(IllegalStateException("state 校验失败"))
                        }
                        runCatching {
                            platform.authExchange(
                                code,
                                start.verifier,
                                start.redirectUri,
                                credentials.deviceId(),
                                deviceModel(),
                                osVersion()
                            )
                        }.also { start.server.stop() }
                    }
                    outcome.onSuccess { auth ->
                        val stored = accountStore.add(auth.token, auth.account)
                        val accounts = accountStore.list()
                        accountStore.setActive(stored.localId)
                        _state.value = _state.value.copy(
                            accounts = accounts,
                            activeId = stored.localId,
                            signedIn = true,
                            signingIn = false,
                            signingInLabel = "",
                            totalBalanceText = totalText(accounts),
                            info = "登录成功"
                        )
                        refreshAccount(stored.localId)
                    }.onFailure {
                        _state.value = _state.value.copy(
                            error = it.message ?: "登录失败",
                            signingIn = false,
                            signingInLabel = ""
                        )
                    }
                }
            }.onFailure {
                _state.value = _state.value.copy(
                    error = it.message ?: "登录启动失败",
                    signingIn = false,
                    signingInLabel = ""
                )
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
        val accounts = accountStore.list()
        accountStore.save(emptyList())
        accountStore.setActive(null)
        _state.value = _state.value.copy(
            signedIn = false,
            accounts = emptyList(),
            activeId = null,
            totalBalanceText = "--",
            info = "已退出全部账号"
        )
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                for (account in accounts) {
                    runCatching { platform.logout(account.token) }
                }
            }
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

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runTurn(conversation)
            }
            _state.value = _state.value.copy(
                sending = false,
                conversations = conversations.list()
            )
        }
    }

    private suspend fun runTurn(conversation: Conversation) {
        var attempts = 0
        var switchingNotified = false
        while (attempts < MAX_ACCOUNT_ATTEMPTS) {
            attempts++
            val account = _state.value.active ?: accountStore.list().firstOrNull()
            if (account == null) {
                conversation.messages.add(ChatMessage("assistant", "未登录，请先登录 DeepSeek 账号", isError = true))
                publish(conversation)
                return
            }
            val history = conversation.messages.toList()
            val result = withContext(Dispatchers.IO) {
                engine.run(_state.value.model, history, sinkFor(conversation), account.token) {}
            }
            when (result) {
                is RunResult.Ok -> return
                is RunResult.Unauthorized -> {
                    account.lastError = "登录已失效"
                    accountStore.update(account)
                    publishAccounts(accountStore.list())
                    conversation.messages.add(ChatMessage("assistant", "账号 ${account.displayName} 登录已失效，请重新登录", isError = true))
                    publish(conversation)
                    return
                }
                is RunResult.QuotaExhausted -> {
                    account.quotaExhausted = true
                    account.lastError = "额度已用尽"
                    accountStore.update(account)
                    val accounts = accountStore.list()
                    publishAccounts(accounts)
                    if (!_state.value.autoSwitch) {
                        conversation.messages.add(ChatMessage("assistant", "账号 ${account.displayName} 额度已用尽，自动切换已关闭", isError = true))
                        publish(conversation)
                        return
                    }
                    val next = pickNextAccount(account.localId)
                    if (next == null) {
                        conversation.messages.add(ChatMessage("assistant", "全部账号额度都已用尽", isError = true))
                        publish(conversation)
                        return
                    }
                    accountStore.setActive(next.localId)
                    publishAccounts(accounts, next.localId)
                    if (!switchingNotified) {
                        switchingNotified = true
                        _state.value = _state.value.copy(
                            info = "${account.displayName} 额度用尽，已自动切换到 ${next.displayName}"
                        )
                    }
                    delay(300)
                }
                is RunResult.Failed -> {
                    account.lastError = result.message
                    accountStore.update(account)
                    publishAccounts(accountStore.list())
                    return
                }
            }
        }
        conversation.messages.add(ChatMessage("assistant", "已尝试所有账号，仍未成功", isError = true))
        publish(conversation)
    }

    private fun sinkFor(conversation: Conversation): MessageSink = object : MessageSink {
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
        private const val MAX_ACCOUNT_ATTEMPTS = 6
    }
}