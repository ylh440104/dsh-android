package com.deepseek.harness.data

import android.content.Context
import com.deepseek.harness.model.ChatMessage
import com.deepseek.harness.model.Conversation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ConversationStore(context: Context) {

    private val dir = File(context.filesDir, "conversations").apply { mkdirs() }

    fun list(): List<Conversation> {
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        val out = mutableListOf<Conversation>()
        for (file in files) {
            runCatching { read(file) }.getOrNull()?.let { out.add(it) }
        }
        return out.sortedByDescending { it.updatedAt }
    }

    fun create(): Conversation {
        val now = System.currentTimeMillis()
        val conversation = Conversation(UUID.randomUUID().toString(), "新会话", now)
        save(conversation)
        return conversation
    }

    fun load(id: String): Conversation? {
        val file = File(dir, "$id.json")
        if (!file.exists()) return null
        return runCatching { read(file) }.getOrNull()
    }

    fun save(conversation: Conversation) {
        val root = JSONObject()
            .put("id", conversation.id)
            .put("title", conversation.title)
            .put("updatedAt", conversation.updatedAt)
        val arr = JSONArray()
        for (m in conversation.messages) {
            arr.put(JSONObject()
                .put("role", m.role)
                .put("text", m.text)
                .put("reasoning", m.reasoning)
                .put("toolName", m.toolName)
                .put("toolInput", m.toolInput)
                .put("isError", m.isError))
        }
        root.put("messages", arr)
        File(dir, "${conversation.id}.json").writeText(root.toString())
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    private fun read(file: File): Conversation {
        val root = JSONObject(file.readText())
        val conversation = Conversation(
            root.optString("id", file.nameWithoutExtension),
            root.optString("title", "新会话"),
            root.optLong("updatedAt", System.currentTimeMillis())
        )
        val arr = root.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            conversation.messages.add(ChatMessage(
                o.optString("role", "user"),
                o.optString("text", ""),
                o.optString("reasoning", ""),
                o.optString("toolName", ""),
                o.optString("toolInput", ""),
                o.optBoolean("isError", false)
            ))
        }
        return conversation
    }
}