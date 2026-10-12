package io.github.ravenor907.handheldforge.core

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Just enough HTTP/1.1 for download tests: GET/HEAD, Range, 404. Uses only java.net. */
class MiniHttp(private val files: Map<String, ByteArray>, private val hits: MutableList<String>) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                thread(isDaemon = true) {
                    sock.use { s ->
                        val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val request = reader.readLine() ?: return@use
                        val (method, path) = request.split(" ").let { it[0] to it[1] }
                        var range: Long? = null
                        while (true) {
                            val h = reader.readLine() ?: break
                            if (h.isEmpty()) break
                            if (h.startsWith("Range:", ignoreCase = true)) {
                                range = h.substringAfter("bytes=").substringBefore('-').trim().toLong()
                            }
                        }
                        val out = s.getOutputStream()
                        val body = files[path]
                        if (method == "GET") synchronized(hits) { hits.add(path) }
                        if (body == null) {
                            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            return@use
                        }
                        val start = (range ?: 0L).toInt()
                        val chunk = body.copyOfRange(start.coerceAtMost(body.size), body.size)
                        val status = if (range != null) "206 Partial Content" else "200 OK"
                        val len = if (method == "HEAD") body.size else chunk.size
                        out.write("HTTP/1.1 $status\r\nContent-Length: $len\r\nConnection: close\r\n\r\n".toByteArray())
                        if (method == "GET") {
                            try {
                                out.write(chunk)
                            } catch (_: Exception) {
                                // client hung up (cancel test)
                            }
                        }
                        out.flush()
                    }
                }
            }
        }
    }

    override fun close() = server.close()
}
