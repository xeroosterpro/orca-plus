package com.wholphinplus.sources.welcome

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Emby servers on this network. Wholphin's search only asks for Jellyfin ("who is JellyfinServer?");
 * Emby answers the same broadcast on UDP 7359 when asked "who is EmbyServer?", with its address,
 * id and name as JSON. A few seconds, nothing kept.
 */
internal object EmbyDiscovery {
    data class Found(
        val id: String,
        val name: String,
        val address: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun find(waitMs: Int = 2_500): List<Found> =
        withContext(Dispatchers.IO) {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 400
                    val ask = "who is EmbyServer?".toByteArray()
                    socket.send(DatagramPacket(ask, ask.size, InetAddress.getByName("255.255.255.255"), 7359))
                    val found = LinkedHashMap<String, Found>()
                    val until = System.currentTimeMillis() + waitMs
                    val buffer = ByteArray(4096)
                    while (System.currentTimeMillis() < until) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        parse(String(packet.data, 0, packet.length))?.let { found.putIfAbsent(it.id.ifBlank { it.address }, it) }
                    }
                    found.values.toList()
                }
            }.getOrDefault(emptyList())
        }

    /** One server's answer: {"Address":"http://10.0.0.30:8096","Id":"…","Name":"…"}. */
    internal fun parse(text: String): Found? =
        runCatching {
            val o = json.parseToJsonElement(text) as JsonObject
            val address = o["Address"]?.jsonPrimitive?.content.orEmpty()
            if (address.isBlank()) return null
            Found(o["Id"]?.jsonPrimitive?.content.orEmpty(), o["Name"]?.jsonPrimitive?.content.orEmpty(), address)
        }.getOrNull()
}
