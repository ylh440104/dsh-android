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
}

data class Account(
    val id: String? = null,
    val name: String? = null,
    val contact: String? = null,
    val avatarUrl: String? = null
)

data class ChatMessage(
    val role: String,
    val text: String,
    val reasoning: String = "",
    val toolName: String = "",
    val toolInput: String = "",
    val isError: Boolean = false
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