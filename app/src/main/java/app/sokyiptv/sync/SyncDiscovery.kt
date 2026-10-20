package app.sokyiptv.sync

import android.os.Build
import app.sokyiptv.pairing.PairingHttp
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Optional LAN-only UDP device discovery. Metadata only; NEVER sends sync history or PIN.
 * Sharing requires the six-digit pairing code even for discovered peers.
 * Routers with Wi-Fi/client isolation may block broadcast, so manual IP remains available.
 */
object SyncDiscovery {
    const val PORT = 38692
    private const val PROBE = "SOKY_SYNC_DISCOVER_V1"
    private const val REPLY = "SOKY_SYNC_HERE_V1"

    data class Peer(val name: String, val address: String)

    fun listen(isRunning: () -> Boolean, currentAddress: () -> String): DatagramSocket? {
        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                soTimeout = 1200
            }
        }.getOrNull() ?: return null
        Thread({
            val bytes = ByteArray(256)
            while (isRunning() && !socket.isClosed) {
                try {
                    val packet = DatagramPacket(bytes, bytes.size)
                    socket.receive(packet)
                    if (!PairingHttp.isPrivateAddress(packet.address.hostAddress)) continue
                    if (String(packet.data, packet.offset, packet.length, Charsets.UTF_8) != PROBE) continue
                    val device = "${Build.MANUFACTURER} ${Build.MODEL}".replace('|', ' ').take(48)
                    val payload = "$REPLY|$device|${currentAddress()}".toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(payload, payload.size, packet.address, packet.port))
                } catch (_: SocketTimeoutException) {
                    // Check isRunning again.
                } catch (_: Exception) {
                    if (!isRunning()) break
                }
            }
            socket.close()
        }, "soky-sync-discovery").apply { isDaemon = true }.start()
        return socket
    }

    suspend fun scan(): List<Peer> = withContext(Dispatchers.IO) {
        val peers = linkedMapOf<String, Peer>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 600
            val dests = linkedSetOf<InetAddress>()
            dests += InetAddress.getByName("255.255.255.255")
            runCatching {
                val nics = NetworkInterface.getNetworkInterfaces()
                while (nics.hasMoreElements()) {
                    val nic = nics.nextElement()
                    if (!nic.isUp || nic.isLoopback) continue
                    for (itf in nic.interfaceAddresses) {
                        val target = itf.broadcast
                        if (target is Inet4Address) dests += target
                    }
                }
            }
            val request = PROBE.toByteArray(Charsets.UTF_8)
            repeat(3) {
                dests.forEach { ip ->
                    runCatching { socket.send(DatagramPacket(request, request.size, ip, PORT)) }
                }
                val until = System.currentTimeMillis() + 700L
                while (System.currentTimeMillis() < until) {
                    try {
                        val data = ByteArray(256)
                        val packet = DatagramPacket(data, data.size)
                        socket.receive(packet)
                        if (!PairingHttp.isPrivateAddress(packet.address.hostAddress)) continue
                        val parts = String(packet.data, 0, packet.length, Charsets.UTF_8).split('|', limit = 3)
                        if (parts.size != 3 || parts[0] != REPLY) continue
                        val address = parts[2]
                        if (!address.matches(Regex("[0-9.]+:[0-9]{1,5}"))) continue
                        val ip = address.substringBefore(':')
                        if (ip != packet.address.hostAddress || !PairingHttp.isPrivateAddress(ip)) continue
                        peers[address] = Peer(parts[1].take(48), address)
                    } catch (_: SocketTimeoutException) { break }
                    catch (_: Exception) { break }
                }
            }
        }
        peers.values.toList()
    }
}
