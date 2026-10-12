package com.deepseek.harness.model

import org.json.JSONArray
import org.json.JSONObject

data class Wallet(val currency: String, val balance: String)

data class Balance(
    val normal: List<Wallet> = emptyList(),
    val bonus: List<Wallet> = emptyList()
) {
    fun normalText(): String = if (normal.isEmpty()) "--" else normal.joinToString("  ") { "${it.balance} ${it.currency}" }
    fun bonusText(): String = if (bonus.isEmpty()) "--" else bonus.joinToString("  ") { "${it.balance} ${it.currency}" }
    fun normalTotal(): Double = normal.sumOf { it.balance.toDoubleOrNull() ?: 0.0 }
    fun bonusTotal(): Double = bonus.sumOf { it.balance.toDoubleOrNull() ?: 0.0 }
    fun total(): Double = normalTotal() + bonusTotal()
    fun isExhausted(): Boolean = total() <= 0.0
    fun totalText(): String = formatAmount(total())
}

data class Account(
    val id: String? = null,
    val name: String? = null,
    val contact: String? = null,
    val avatarUrl: String? = null
)

data class StoredAccount(
    val localId: String,
    val token: String,
    var name: String? = null,
    var contact: String? = null,
    var avatarUrl: String? = null,
    var remoteId: String? = null,
    var kind: String = KIND_OAUTH,
    var baseUrl: String = "",
    var models: MutableList<String> = mutableListOf(),
    var selectedModel: String = "",
    var normalText: String = "",
    var bonusText: String = "",
    var normalValue: Double = 0.0,
    var bonusValue: Double = 0.0,
    var quotaExhausted: Boolean = false,
    var lastError: String? = null,
    var lastUpdated: Long = 0L
) {
    val totalValue: Double get() = normalValue + bonusValue

    val isApiKey: Boolean get() = kind == KIND_APIKEY

    val isCustom: Boolean get() = kind == KIND_CUSTOM

    val usesOpenAiProtocol: Boolean get() = isApiKey || isCustom

    val displayName: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: contact?.takeIf { it.isNotBlank() }
            ?: when (kind) {
                KIND_APIKEY -> "DeepSeek API Key"
                KIND_CUSTOM -> hostLabel(baseUrl)
                else -> "DeepSeek 账号"
            }

    val modeLabel: String get() = when (kind) {
        KIND_APIKEY -> "API Key"
        KIND_CUSTOM -> "自定义"
        else -> "账号登录"
    }

    val balanceLine: String
        get() = when {
            lastError != null -> lastError!!
            isCustom -> if (models.isEmpty()) "模型列表未获取" else "${models.size} 个模型"
            normalText.isBlank() && bonusText.isBlank() -> "额度未知"
            bonusText.isBlank() -> normalText
            else -> "$normalText  ·  赠送 $bonusText"
        }

    fun toJson(): JSONObject {
        val modelArray = JSONArray()
        for (model in models) modelArray.put(model)
        return JSONObject()
            .put("localId", localId)
            .put("name", name ?: "")
            .put("contact", contact ?: "")
            .put("avatarUrl", avatarUrl ?: "")
            .put("remoteId", remoteId ?: "")
            .put("kind", kind)
            .put("baseUrl", baseUrl)
            .put("models", modelArray)
            .put("selectedModel", selectedModel)
            .put("normalText", normalText)
            .put("bonusText", bonusText)
            .put("normalValue", normalValue)
            .put("bonusValue", bonusValue)
            .put("quotaExhausted", quotaExhausted)
            .put("lastUpdated", lastUpdated)
    }

    companion object {
        const val KIND_OAUTH = "oauth"
        const val KIND_APIKEY = "apikey"
        const val KIND_CUSTOM = "custom"

        fun fromJson(json: JSONObject, token: String): StoredAccount {
            val modelArray = json.optJSONArray("models")
            val models = mutableListOf<String>()
            if (modelArray != null) {
                for (i in 0 until modelArray.length()) {
                    val value = modelArray.optString(i, "")
                    if (value.isNotEmpty()) models.add(value)
                }
            }
            return StoredAccount(
                localId = json.optString("localId", ""),
                token = token,
                name = json.optString("name", "").ifEmpty { null },
                contact = json.optString("contact", "").ifEmpty { null },
                avatarUrl = json.optString("avatarUrl", "").ifEmpty { null },
                remoteId = json.optString("remoteId", "").ifEmpty { null },
                kind = json.optString("kind", KIND_OAUTH).ifEmpty { KIND_OAUTH },
                baseUrl = json.optString("baseUrl", ""),
                models = models,
                selectedModel = json.optString("selectedModel", ""),
                normalText = json.optString("normalText", ""),
                bonusText = json.optString("bonusText", ""),
                normalValue = json.optDouble("normalValue", 0.0),
                bonusValue = json.optDouble("bonusValue", 0.0),
                quotaExhausted = json.optBoolean("quotaExhausted", false),
                lastUpdated = json.optLong("lastUpdated", 0L)
            )
        }
    }
}

fun hostLabel(url: String): String {
    if (url.isBlank()) return "自定义服务"
    return runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
}

fun normalizeBaseUrl(input: String): String {
    var value = input.trim()
    if (value.isEmpty()) return ""
    if (!value.startsWith("http://") && !value.startsWith("https://")) {
        value = "https://$value"
    }
    value = value.trimEnd('/')
    for (suffix in listOf("/chat/completions", "/completions", "/models")) {
        if (value.endsWith(suffix)) value = value.removeSuffix(suffix).trimEnd('/')
    }
    return value
}

fun baseUrlCandidates(input: String): List<String> {
    val base = normalizeBaseUrl(input)
    if (base.isEmpty()) return emptyList()
    val out = mutableListOf(base)
    val path = runCatching { java.net.URI(base).path.orEmpty() }.getOrDefault("")
    if (path.isBlank() || path == "/") {
        out.add("$base/v1")
    }
    return out.distinct()
}

fun maskApiKey(key: String): String =
    if (key.length <= 10) key else key.take(6) + "…" + key.takeLast(4)

fun looksLikeApiKey(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.startsWith("sk-") && trimmed.length >= 20 && !trimmed.contains(' ')
}

fun formatAmount(value: Double): String {
    if (value == 0.0) return "0"
    return java.math.BigDecimal(value)
        .setScale(2, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
}

data class ChatMessage(
    val role: String,
    val text: String,
    val reasoning: String = "",
    val toolName: String = "",
    val toolInput: String = "",
    val isError: Boolean = false,
    val isRetrying: Boolean = false
)

data class Conversation(
    val id: String,
    var title: String,
    var updatedAt: Long,
    val messages: MutableList<ChatMessage> = mutableListOf()
)

data class ModelInfo(val id: String, val name: String, val description: String = "")

val BUILTIN_MODELS = listOf(
    ModelInfo("deepseek-flash", "DeepSeek-V4-Flash", "快速响应，适合日常对话与轻量任务"),
    ModelInfo("deepseek-v4-pro", "DeepSeek-V4-Pro", "更强推理与编码能力，适合复杂任务")
)

val APIKEY_MODELS = listOf(
    ModelInfo("deepseek-chat", "deepseek-chat", "官方 API Key 模式，对应 V3 通用对话模型"),
    ModelInfo("deepseek-reasoner", "deepseek-reasoner", "官方 API Key 模式，对应推理模型")
)

fun modelsFor(apiKeyMode: Boolean): List<ModelInfo> = if (apiKeyMode) APIKEY_MODELS else BUILTIN_MODELS

fun parseBalance(json: JSONObject): Balance {
    val normalArr = json.optJSONArray("normal_wallets") ?: JSONArray()
    val bonusArr = json.optJSONArray("bonus_wallets") ?: JSONArray()
    return Balance(walletsOf(normalArr), walletsOf(bonusArr))
}

private fun walletsOf(arr: JSONArray): List<Wallet> {
    val out = mutableListOf<Wallet>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out.add(Wallet(o.optString("currency", ""), o.optString("balance", "")))
    }
    return out
}

fun parseAccount(json: JSONObject): Account {
    val identity = json.optJSONObject("id_profile")
    val mobile = json.optString("mobile", "")
    val mobileNumber = json.optString("mobile_number", "")
    val email = json.optString("email", "")
    val contact = when {
        mobile.isNotEmpty() -> mobile
        mobileNumber.isNotEmpty() -> mobileNumber
        else -> email
    }
    return Account(
        id = json.optString("id", "").ifEmpty { null },
        name = identity?.optString("name", "")?.ifEmpty { null },
        contact = contact.ifEmpty { null },
        avatarUrl = identity?.optString("picture", "")?.ifEmpty { null }
    )
}