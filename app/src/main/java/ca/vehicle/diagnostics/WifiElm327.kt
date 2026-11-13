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

data class ElmVinResult(
    val success: Boolean,
    val message: String,
    val vin: String = ""
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
                socket.connect(InetSocketAddress("192.168.0.10", 35000), 3500)
                socket.soTimeout = 7000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val output = socket.getOutputStream()

                exchange(reader, output, "ATZ", 2500)
                exchange(reader, output, "ATE0")
                exchange(reader, output, "ATL0")
                exchange(reader, output, "ATS1")
                exchange(reader, output, "ATH0")
                exchange(reader, output, "ATSP0")
                exchange(reader, output, "ATCAF1")

                // Give the ECU time to wake and answer each live PID.
                val rpmResponse = exchange(reader, output, "010C", 5000)
                val coolantResponse = exchange(reader, output, "0105", 5000)
                val voltageResponse = exchange(reader, output, "0142", 5000)

                val rpmBytes = responseBytes(rpmResponse, "410C", 2)
                val coolantBytes = responseBytes(coolantResponse, "4105", 1)
                val voltageBytes = responseBytes(voltageResponse, "4142", 2)

                val rpm = rpmBytes?.let { ((it[0] * 256 + it[1]) / 4).toString() } ?: "—"
                val coolant = coolantBytes?.let { (it[0] - 40).toString() + " °C" } ?: "—"
                val volts = voltageBytes?.let { String.format(java.util.Locale.US, "%.2f V", (it[0] * 256 + it[1]) / 1000.0) } ?: "—"
                val any = rpm != "—" || coolant != "—" || volts != "—"
                val diagnostics = buildString {
                    append("RPM: ").append(rpmResponse.ifBlank { "no response" })
                    append(" | Coolant: ").append(coolantResponse.ifBlank { "no response" })
                    append(" | Voltage: ").append(voltageResponse.ifBlank { "no response" })
                }.take(500)
                ElmLiveDataResult(
                    any,
                    if (any) "Live data received. Raw ECU replies: $diagnostics"
                    else "No supported live values were decoded. Raw ECU replies: $diagnostics",
                    rpm, coolant, volts
                )
            }
        } catch (e: Exception) {
            ElmLiveDataResult(false, "Live-data request failed: ${e.message ?: "connection error"}")
        }
    }


    // Mode 09 PID 02 requests the vehicle identification number. Read-only.
    fun readVin(context: Context): ElmVinResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return ElmVinResult(false, "Connect to the adapter's Wi-Fi first.")
        return try {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress("192.168.0.10", 35000), 2500)
                socket.soTimeout = 3500
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val output = socket.getOutputStream()
                exchange(reader, output, "ATZ")
                exchange(reader, output, "ATE0")
                exchange(reader, output, "ATL0")
                exchange(reader, output, "ATS1")
                exchange(reader, output, "ATH0")
                exchange(reader, output, "ATSP0")
                exchange(reader, output, "ATCAF1")
                val response = exchange(reader, output, "0902", timeoutMs = 12000)
                val vin = extractVin(response)
                if (vin != null) ElmVinResult(true, "VIN received from the vehicle ECU.", vin)
                else ElmVinResult(false, "The ECU or adapter did not provide a readable VIN. Raw response: ${response.ifBlank { "(empty)" }}")
            }
        } catch (e: Exception) {
            ElmVinResult(false, "VIN request failed: ${e.message ?: "connection error"}")
        }
    }

    private fun extractVin(response: String): String? {
        // Some Wi-Fi ELM adapters print multi-frame CAN data as rows such as
        // "014 0: 49 02 01 32 ..." and "1: 52 ...". Extract byte-sized
        // hex tokens so row counters and adapter labels cannot contaminate the VIN.
        val byteTokens = Regex("(?i)(?<![A-F0-9])([A-F0-9]{2})(?![A-F0-9])")
            .findAll(response)
            .map { it.groupValues[1].uppercase() }
            .toList()

        fun findVin(bytes: List<String>): String? {
            val marker = bytes.indices.firstOrNull { i ->
                i + 2 < bytes.size && bytes[i] == "49" && bytes[i + 1] == "02"
            } ?: return null
            val payload = bytes.drop(marker + 3) // skip 49 02 and VIN record number 01
            val decoded = payload.mapNotNull { it.toIntOrNull(16)?.toChar() }.joinToString("")
            return Regex("[A-HJ-NPR-Z0-9]{17}").find(decoded)?.value
        }

        findVin(byteTokens)?.let { return it }

        // Fallback for adapters that return a compact, unspaced hex response.
        val compact = response.uppercase().filter { it in "0123456789ABCDEF" }
        val marker = compact.indexOf("4902")
        if (marker < 0) return null
        val payload = compact.substring(marker + 6)
        val decoded = payload.chunked(2).takeWhile { it.length == 2 }
            .mapNotNull { it.toIntOrNull(16)?.toChar() }.joinToString("")
        return Regex("[A-HJ-NPR-Z0-9]{17}").find(decoded)?.value
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

    private fun exchange(reader: BufferedReader, output: OutputStream, command: String, timeoutMs: Long = 1400): String {
        output.write((command + "\r").toByteArray(Charsets.US_ASCII))
        output.flush()
        val result = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
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
