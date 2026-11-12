package ca.vehicle.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket

data class ElmConnectionResult(
    val success: Boolean,
    val message: String,
    val adapterInfo: String = "",
    val supportedPids: String = ""
)

object WifiElm327 {
    fun connectAndIdentify(context: Context): ElmConnectionResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
            ?: return ElmConnectionResult(false, "No active network. Connect the phone to the adapter Wi-Fi first.")
        val caps = cm.getNetworkCapabilities(network)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
            return ElmConnectionResult(false, "The active network is not Wi-Fi. Select the OBD adapter network in Android settings.")
        }

        val ports = listOf(35000, 23)
        var lastError = "No supported TCP port responded."
        for (port in ports) {
            try {
                network.socketFactory.createSocket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("192.168.0.10", port), 2500)
                    socket.soTimeout = 1800
                    val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val output = socket.getOutputStream()
                    val reset = exchange(input, output, "ATZ")
                    exchange(input, output, "ATE0")
                    exchange(input, output, "ATL0")
                    exchange(input, output, "ATS0")
                    exchange(input, output, "ATH0")
                    val identity = exchange(input, output, "ATI")
                    val pids = exchange(input, output, "0100")
                    val identityText = identity.ifBlank { reset }.take(240)
                    return ElmConnectionResult(
                        success = identityText.isNotBlank() || pids.isNotBlank(),
                        message = if (identityText.isNotBlank() || pids.isNotBlank())
                            "ELM-style adapter responded on TCP port $port."
                        else "TCP connected on port $port, but no ELM response was recognized.",
                        adapterInfo = identityText,
                        supportedPids = pids.take(240)
                    )
                }
            } catch (e: Exception) {
                lastError = "Port $port: ${e.message ?: "connection failed"}"
            }
        }
        return ElmConnectionResult(false, "Could not reach 192.168.0.10 on ports 35000 or 23. $lastError")
    }

    private fun exchange(reader: BufferedReader, output: OutputStream, command: String): String {
        output.write((command + "\r").toByteArray(Charsets.US_ASCII))
        output.flush()
        val result = StringBuilder()
        val deadline = System.currentTimeMillis() + 1400
        while (System.currentTimeMillis() < deadline) {
            try {
                if (!reader.ready()) {
                    Thread.sleep(40)
                    continue
                }
                val ch = reader.read()
                if (ch < 0) break
                if (ch == '>') break
                if (ch.toChar() != '\r' && ch.toChar() != '\n') result.append(ch.toChar())
            } catch (_: Exception) {
                break
            }
        }
        return result.toString().trim()
    }
}
