package com.example.steamstream

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

data class Game(val appid: Long, val name: String, val installed: Boolean)
data class Status(val hostname: String, val steam: Boolean, val sunshine: Boolean, val mac: String)

/** All methods are blocking: call them from Dispatchers.IO. */
class HostApi(host: String, private val token: String) {
    private val base = host.trim().trimEnd('/').let {
        val withScheme = if (it.startsWith("http")) it else "http://$it"
        if (withScheme.substringAfter("://").contains(":")) withScheme else "$withScheme:8765"
    }

    private fun call(method: String, path: String, body: JSONObject? = null): String {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 4000
        c.readTimeout = 15000
        c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) error(if (code == 401) "Wrong token" else "HTTP $code")
        return text
    }

    fun status(): Status {
        val j = JSONObject(call("GET", "/status"))
        return Status(j.getString("hostname"), j.getBoolean("steam"), j.getBoolean("sunshine"), j.optString("mac"))
    }

    fun library(): List<Game> {
        val arr = JSONObject(call("GET", "/library")).getJSONArray("games")
        return (0 until arr.length()).map {
            val g = arr.getJSONObject(it)
            Game(g.getLong("appid"), g.getString("name"), g.getBoolean("installed"))
        }
    }

    private fun act(path: String, appid: Long) { call("POST", path, JSONObject().put("appid", appid)) }
    fun launch(appid: Long) = act("/launch", appid)
    fun install(appid: Long) = act("/install", appid)
    fun uninstall(appid: Long) = act("/uninstall", appid)
}

/** Wake-on-LAN magic packet. Works on the same LAN; see README for remote wake. */
fun sendWol(mac: String, broadcast: String = "255.255.255.255") {
    val m = mac.split(":", "-").map { it.toInt(16).toByte() }.toByteArray()
    require(m.size == 6) { "Bad MAC address" }
    val packet = ByteArray(6) { 0xFF.toByte() } + ByteArray(96) { m[it % 6] }
    DatagramSocket().use {
        it.broadcast = true
        it.send(DatagramPacket(packet, packet.size, InetAddress.getByName(broadcast), 9))
    }
}
