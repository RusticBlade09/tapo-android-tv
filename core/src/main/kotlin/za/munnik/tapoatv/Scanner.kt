package za.munnik.tapoatv

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Finds Tapo cameras: anything on our /24 whose port 8800 answers POST /stream with a Digest challenge. */
object Scanner {
    fun myIp(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress

    fun scan(): List<String> {
        val base = myIp()?.substringBeforeLast('.') ?: return emptyList()
        val pool = Executors.newFixedThreadPool(48)
        val jobs = (1..254).map { i -> "$base.$i" }.map { ip -> ip to pool.submit<Boolean> { isTapo(ip) } }
        pool.shutdown()
        pool.awaitTermination(20, TimeUnit.SECONDS)
        return jobs.filter { runCatching { it.second.get() }.getOrDefault(false) }.map { it.first }
    }

    private fun isTapo(ip: String): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, 8800), 400)
            s.soTimeout = 3000 // a camera that is busy streaming can be slow to answer
            s.getOutputStream().write("POST /stream HTTP/1.1\r\nHost: $ip:8800\r\n\r\n".toByteArray())
            val reply = ByteArray(512).let { b -> String(b, 0, maxOf(0, s.getInputStream().read(b))) }
            reply.contains(" 401") && reply.contains("Digest", ignoreCase = true)
        }
    } catch (e: Exception) {
        false
    }
}
