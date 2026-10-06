package com.deepseek.harness.data

import android.content.Context
import com.deepseek.harness.model.Account
import com.deepseek.harness.model.Balance
import com.deepseek.harness.model.StoredAccount
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AccountStore(context: Context) {

    private val prefs = context.getSharedPreferences("dsh_accounts", Context.MODE_PRIVATE)

    fun list(): List<StoredAccount> {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        val array = try {
            JSONArray(raw)
        } catch (e: Exception) {
            return emptyList()
        }
        val out = mutableListOf<StoredAccount>()
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val token = SecretBox.decrypt(entry.optString("token", "")) ?: continue
            val account = StoredAccount.fromJson(entry, token)
            if (account.localId.isEmpty()) continue
            out.add(account)
        }
        return out
    }

    fun save(accounts: List<StoredAccount>) {
        val array = JSONArray()
        for (account in accounts) {
            val json = account.toJson()
            json.put("token", SecretBox.encrypt(account.token))
            array.put(json)
        }
        prefs.edit().putString(KEY_ACCOUNTS, array.toString()).apply()
    }

    fun add(token: String, account: Account?): StoredAccount {
        val accounts = list().toMutableList()
        val existing = accounts.firstOrNull { it.token == token }
        if (existing != null) return existing
        val stored = StoredAccount(
            localId = UUID.randomUUID().toString(),
            token = token,
            name = account?.name,
            contact = account?.contact,
            avatarUrl = account?.avatarUrl,
            remoteId = account?.id
        )
        accounts.add(stored)
        save(accounts)
        return stored
    }

    fun remove(localId: String): List<StoredAccount> {
        val accounts = list().filterNot { it.localId == localId }
        save(accounts)
        return accounts
    }

    fun update(account: StoredAccount) {
        val accounts = list().toMutableList()
        val index = accounts.indexOfFirst { it.localId == account.localId }
        if (index >= 0) accounts[index] = account else accounts.add(account)
        save(accounts)
    }

    fun updateAll(accounts: List<StoredAccount>) {
        save(accounts)
    }

    fun activeId(): String? = prefs.getString(KEY_ACTIVE, null)

    fun setActive(localId: String?) {
        prefs.edit().apply {
            if (localId == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, localId)
        }.apply()
    }

    fun autoSwitch(): Boolean = prefs.getBoolean(KEY_AUTO_SWITCH, true)

    fun setAutoSwitch(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SWITCH, enabled).apply()
    }

    fun applyBalance(account: StoredAccount, balance: Balance) {
        account.normalText = balance.normalText()
        account.bonusText = balance.bonusText()
        account.normalValue = balance.normalTotal()
        account.bonusValue = balance.bonusTotal()
        account.quotaExhausted = balance.isExhausted()
        account.lastError = null
        account.lastUpdated = System.currentTimeMillis()
    }

    fun storedBalances(): JSONObject {
        val json = JSONObject()
        for (account in list()) {
            json.put(account.localId, account.toJson())
        }
        return json
    }

    companion object {
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_ACTIVE = "active_id"
        private const val KEY_AUTO_SWITCH = "auto_switch"
    }
}