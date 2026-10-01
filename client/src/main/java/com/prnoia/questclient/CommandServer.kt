package com.prnoia.questclient

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TCP-сервер команд. Каждое подключение — свой поток, сообщения — JSON-строки.
 * Подключения с localhost (ADB-туннель) доверенные, по сети нужен PIN.
 */
class CommandServer(private val handler: CommandHandler, private val pin: () -> String) {

    private class Client(val socket: Socket, val out: PrintWriter, @Volatile var authed: Boolean)

    private val clients = CopyOnWriteArrayList<Client>()
    private var server: ServerSocket? = null

    val clientCount: Int get() = clients.count { it.authed }

    fun start() {
        val s = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(Protocol.PORT))
        }
        server = s
        Thread({
            while (!s.isClosed) {
                val socket = runCatching { s.accept() }.getOrNull() ?: break
                Thread({ serve(socket) }, "cmd-client").apply { isDaemon = true }.start()
            }
        }, "cmd-server").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { server?.close() }
        clients.forEach { runCatching { it.socket.close() } }
        clients.clear()
    }

    /** Разослать событие всем авторизованным пультам. */
    fun broadcast(event: JSONObject) {
        val line = event.toString()
        clients.filter { it.authed }.forEach { c ->
            synchronized(c.out) {
                c.out.println(line)
                if (c.out.checkError()) clients.remove(c)
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.tcpNoDelay = true
        val out = PrintWriter(socket.getOutputStream().bufferedWriter(Charsets.UTF_8), true)
        val client = Client(socket, out, authed = socket.inetAddress.isLoopbackAddress)
        clients += client
        ClientLog.add("подключён пульт ${socket.inetAddress.hostAddress}")
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val req = runCatching { JSONObject(line) }.getOrNull()
                val res = when {
                    req == null -> JSONObject().put("ok", false).put("error", "Некорректный JSON")
                    req.optString("cmd") == "hello" -> hello(client, req)
                    !client.authed && req.optString("cmd") != "ping" ->
                        JSONObject().put("ok", false).put("error", "Нужна авторизация: hello с PIN")
                            .put("id", req.opt("id"))
                    else -> handler.handle(req)
                }
                synchronized(out) { out.println(res.toString()) }
            }
        } catch (_: Exception) {
        } finally {
            clients.remove(client)
            runCatching { socket.close() }
            ClientLog.add("пульт отключился")
        }
    }

    private fun hello(client: Client, req: JSONObject): JSONObject {
        if (!client.authed) client.authed = req.optString("pin") == pin()
        val res = JSONObject().put("ok", client.authed).put("version", Protocol.VERSION)
        if (!client.authed) res.put("error", "Неверный PIN")
        req.opt("id")?.let { res.put("id", it) }
        return res
    }
}
