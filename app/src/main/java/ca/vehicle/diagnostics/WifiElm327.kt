package ca.vehicle.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

data class ElmConnectionResult(
    val success: Boolean,
    val message: String,
    val adapterInfo: String = "",
    val supportedPids: String = ""
)

data class ElmLiveDataResult(
    val success: Boolean,
    val message: String,
    val rpm: String = "—",
    val coolant: String = "—",
    val volts: String = "—"
)

data class ElmTroubleCodeResult(
    val success: Boolean,
    val message: String,
    val codes: List<String> = emptyList()
)

object WifiElm327 {
    private fun wifiNetwork(context: Context) =
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            .allNetworks.firstOrNull { candidate ->
                context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    .let { it as ConnectivityManager }
                    .getNetworkCapabilities(candidate)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }

    fun connectAndIdentify(context: Context): ElmConnectionResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return ElmConnectionResult(false, "No Wi-Fi network found. Connect to the OBD adapter Wi-Fi first.")
        var lastError = "No supported TCP port responded."
        for (port in listOf(35000, 23)) {
            try {
                network.socketFactory.createSocket().use { socket ->
                    socket.connect(InetSocketAddress("192.168.0.10", port), 2500)
                    socket.soTimeout = 1800
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val output = socket.getOutputStream()
                    val reset = exchange(reader, output, "ATZ")
                    exchange(reader, output, "ATE0")
                    exchange(reader, output, "ATL0")
                    exchange(reader, output, "ATS0")
                    exchange(reader, output, "ATH0")
                    exchange(reader, output, "ATSP0")
                    val identity = exchange(reader, output, "ATI")
                    val pids = exchange(reader, output, "0100")
                    val identityText = identity.ifBlank { reset }.take(240)
                    val ok = identityText.isNotBlank() || pids.contains("4100", true)
                    return ElmConnectionResult(
                        ok,
                        if (ok) "ELM-style adapter responded on TCP port $port." else "TCP connected, but no ELM response was recognized.",
                        identityText,
                        pids.take(240)
                    )
                }
            } catch (e: Exception) {
                lastError = "Port $port: ${e.message ?: "connection failed"}"
            }
        }
        return ElmConnectionResult(false, "Could not reach 192.168.0.10 on ports 35000 or 23. $lastError")
    }

    fun readLiveData(context: Context): ElmLiveDataResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return ElmLiveDataResult(false, "Connect to the adapter's Wi-Fi first.")
        return try {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress("192.168.0.10", 35000), 2500)
                socket.soTimeout = 1800
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val output = socket.getOutputStream()
                exchange(reader, output, "ATZ")
                exchange(reader, output, "ATE0")
                exchange(reader, output, "ATL0")
                exchange(reader, output, "ATS0")
                exchange(reader, output, "ATH0")
                exchange(reader, output, "ATSP0")

                val rpmResponse = exchange(reader, output, "010C")
                val coolantResponse = exchange(reader, output, "0105")
                val voltageResponse = exchange(reader, output, "0142")

                val rpmBytes = responseBytes(rpmResponse, "410C", 4)
                val coolantBytes = responseBytes(coolantResponse, "4105", 2)
                val voltageBytes = responseBytes(voltageResponse, "4142", 4)

                val rpm = rpmBytes?.let { ((it[0] * 256 + it[1]) / 4).toString() } ?: "—"
                val coolant = coolantBytes?.let { (it[0] - 40).toString() + " °C" } ?: "—"
                val volts = voltageBytes?.let { String.format("%.2f V", (it[0] * 256 + it[1]) / 1000.0) } ?: "—"
                val any = rpm != "—" || coolant != "—" || volts != "—"
                ElmLiveDataResult(
                    any,
                    if (any) "Live data read from the vehicle ECU." else "Adapter answered, but the ECU did not return these live-data values. Check ignition is ON and try again.",
                    rpm, coolant, volts
                )
            }
        } catch (e: Exception) {
            ElmLiveDataResult(false, "Live-data request failed: ${e.message ?: "connection error"}")
        }
    }


    // Mode 03 only reads stored emissions-related trouble codes. No clear/reset command is sent.
    fun readStoredCodes(context: Context): ElmTroubleCodeResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return ElmTroubleCodeResult(false, "Connect to the adapter's Wi-Fi first.")
        return try {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress("192.168.0.10", 35000), 2500)
                socket.soTimeout = 2500
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val output = socket.getOutputStream()
                exchange(reader, output, "ATZ")
                exchange(reader, output, "ATE0")
                exchange(reader, output, "ATL0")
                exchange(reader, output, "ATS0")
                exchange(reader, output, "ATH0")
                exchange(reader, output, "ATSP0")
                val response = exchange(reader, output, "03")
                val clean = response.uppercase().filter { it in "0123456789ABCDEF" }
                val start = clean.indexOf("43")
                if (start < 0) {
                    val noCodes = response.contains("NO DATA", true) || response.contains("43", true) && clean.length <= 2
                    ElmTroubleCodeResult(
                        noCodes,
                        if (noCodes) "The ECU reports no stored Mode 03 codes." else "No Mode 03 code response was recognized. Raw response: ${response.ifBlank { "(empty)" }}"
                    )
                } else {
                    val data = clean.substring(start + 2)
                    val found = mutableListOf<String>()
                    var i = 0
                    while (i + 4 <= data.length) {
                        val a = data.substring(i, i + 2).toIntOrNull(16) ?: break
                        val b = data.substring(i + 2, i + 4).toIntOrNull(16) ?: break
                        i += 4
                        if (a == 0 && b == 0) continue
                        found += decodeDtc(a, b)
                    }
                    ElmTroubleCodeResult(true, if (found.isEmpty()) "No stored codes were reported." else "${found.size} stored code(s) read. Nothing was cleared.", found)
                }
            }
        } catch (e: Exception) {
            ElmTroubleCodeResult(false, "Code read failed: ${e.message ?: "connection error"}")
        }
    }

    private fun decodeDtc(a: Int, b: Int): String {
        val system = when ((a shr 6) and 0x03) {
            0 -> "P"
            1 -> "C"
            2 -> "B"
            else -> "U"
        }
        val second = (a shr 4) and 0x03
        return system + second.toString() +
            ((a shr 0) and 0x0F).toString(16).uppercase() +
            ((b shr 4) and 0x0F).toString(16).uppercase() +
            (b and 0x0F).toString(16).uppercase()
    }

    private fun responseBytes(response: String, header: String, byteCount: Int): IntArray? {
        val clean = response.uppercase().filter { it in "0123456789ABCDEF" }
        val start = clean.indexOf(header)
        if (start < 0) return null
        val dataStart = start + header.length
        val needed = byteCount * 2
        if (clean.length < dataStart + needed) return null
        return (0 until byteCount).map { i ->
            clean.substring(dataStart + i * 2, dataStart + i * 2 + 2).toInt(16)
        }.toIntArray()
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
                if (ch == '>'.code) break
                if (ch.toChar() != '\r' && ch.toChar() != '\n') result.append(ch.toChar())
            } catch (_: Exception) {
                break
            }
        }
        return result.toString().trim()
    }
}
