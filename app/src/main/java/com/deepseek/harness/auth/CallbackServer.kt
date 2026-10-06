package com.deepseek.harness.auth

import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CallbackServer {

    private var serverSocket: ServerSocket? = null
    private var port: Int = 0
    private val latch = CountDownLatch(1)
    @Volatile private var resultCode: String? = null
    @Volatile private var resultState: String? = null
    @Volatile private var failure: String? = null

    fun start(): String {
        val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        Thread {
            try {
                socket.accept().use { client -> handle(client) }
            } catch (e: Exception) {
                if (failure == null) failure = "回调监听失败：${e.message}"
                latch.countDown()
            }
        }.apply { isDaemon = true }.start()
        return "http://127.0.0.1:$port/oauth/callback"
    }

    private fun handle(client: Socket) {
        val requestLine = client.getInputStream().bufferedReader().readLine().orEmpty()
        val target = requestLine.split(" ").getOrNull(1).orEmpty()
        val query = target.substringAfter("?", "")
        val params = parseQuery(query)
        val code = params["code"]
        val state = params["state"]
        if (code.isNullOrEmpty() || state.isNullOrEmpty()) {
            failure = "未收到授权码"
            respond(client, 400, "登录失败，请返回应用重试。")
        } else {
            resultCode = code
            resultState = state
            respond(client, 200, "登录成功，请返回 DeepSeek Harness。")
        }
        latch.countDown()
    }

    private fun respond(client: Socket, status: Int, message: String) {
        runCatching {
            val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8))
            val body = "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<title>$message</title><body style=\"font-family:sans-serif;padding:32px\"><p>$message</p></body></html>"
            writer.write("HTTP/1.1 $status ${if (status == 200) "OK" else "Bad Request"}\r\n")
            writer.write("content-type: text/html; charset=utf-8\r\n")
            writer.write("cache-control: no-store\r\n")
            writer.write("content-length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
            writer.write("connection: close\r\n\r\n")
            writer.write(body)
            writer.flush()
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            val idx = pair.indexOf("=")
            if (idx < 1) continue
            val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
            val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            if (!out.containsKey(key)) out[key] = value
        }
        return out
    }

    fun await(timeoutMs: Long): Pair<String, String>? {
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            failure = "登录超时，请重试"
            return null
        }
        val code = resultCode ?: return null
        val state = resultState ?: return null
        return code to state
    }

    fun error(): String? = failure

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}