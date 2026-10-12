package com.deepseek.harness.api

import com.deepseek.harness.model.Balance
import com.deepseek.harness.model.Wallet
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiKeyClient(private val origin: String = PlatformClient.INFERENCE_ORIGIN) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun fetchBalance(apiKey: String): Balance {
        val request = Request.Builder()
            .url("$origin/user/balance")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()
        val text = try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 401 || response.code == 403) {
                    throw PlatformException("expired", "API Key 无效或已被撤销")
                }
                if (response.code !in 200..299) {
                    throw PlatformException("network", "余额查询失败：HTTP ${response.code}")
                }
                body
            }
        } catch (e: PlatformException) {
            throw e
        } catch (e: Exception) {
            throw PlatformException("network", "网络请求失败：${e.message}")
        }

        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw PlatformException("protocol", "余额响应解析失败")
        }

        val available = root.optBoolean("is_available", true)
        val infos = root.optJSONArray("balance_infos") ?: return Balance()
        val wallets = mutableListOf<Wallet>()
        for (i in 0 until infos.length()) {
            val info = infos.optJSONObject(i) ?: continue
            val currency = info.optString("currency", "CNY")
            val total = info.optString("total_balance", "")
            if (total.isEmpty()) continue
            wallets.add(Wallet(currency, total))
        }
        if (!available) {
            throw PlatformException("quota", "API Key 余额不足")
        }
        return Balance(normal = wallets, bonus = emptyList())
    }

    fun verify(apiKey: String): Balance = fetchBalance(apiKey)
}