package ca.vehicle.diagnostics

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.max

class DeviceBenchActivity : Activity() {
    private lateinit var report: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(0xFF101820.toInt())
        }
        val title = TextView(this).apply {
            text = "CHUCK  /  DEVICE BENCH"
            textSize = 22f
            setTextColor(0xFF63C7B2.toInt())
        }
        val intro = TextView(this).apply {
            text = "Offline hardware check. Run this on your phone, then run it again on the tablet to compare."
            textSize = 14f
            setTextColor(0xFFAAB8C2.toInt())
            setPadding(0, 16, 0, 18)
        }
        runButton = Button(this).apply { text = "RUN BENCH TEST" }
        report = TextView(this).apply {
            text = "Ready. The test checks device details, RAM, a short CPU workload, thermal status and available storage."
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 20, 0, 20)
        }
        root.addView(title)
        root.addView(intro)
        root.addView(runButton)
        root.addView(report)
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        runButton.setOnClickListener {
            runButton.isEnabled = false
            runButton.text = "TEST RUNNING…"
            report.text = "Testing… keep Chuck open for a few seconds."
            thread(name = "ChuckDeviceBench") {
                val result = runTests()
                runOnUiThread {
                    report.text = result
                    runButton.isEnabled = true
                    runButton.text = "RUN AGAIN"
                }
            }
        }
    }

    private fun runTests(): String {
        val lines = mutableListOf<String>()
        lines += "DEVICE: ${Build.MANUFACTURER} ${Build.MODEL}"
        lines += "ANDROID: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        lines += "CPU CORES: ${Runtime.getRuntime().availableProcessors()}"
        lines += "CPU ABI: ${Build.SUPPORTED_ABIS.joinToString()}"
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mem)
            lines += "TOTAL RAM: ${mem.totalMem / 1024 / 1024} MB (kernel-reported)"
            lines += "AVAILABLE RAM: ${mem.availMem / 1024 / 1024} MB now"
            lines += "LOW-MEMORY WARNING: ${if (mem.lowMemory) "Yes" else "No"}"
            lines += "Virtual RAM/swap is not equivalent to physical RAM."
        } catch (_: Exception) { lines += "RAM details unavailable." }

        try {
            var x = 0x12345678
            var count = 0L
            val start = System.nanoTime()
            while (System.nanoTime() - start < 1_000_000_000L) {
                x = x xor (x shl 13)
                x = x xor (x ushr 17)
                x = x xor (x shl 5)
                count++
            }
            lines += "QUICK CPU SCORE: ${count / 1_000_000} million simple operations/sec"
            lines += "Rough comparison only, not a direct AI speed score."
        } catch (_: Exception) { lines += "CPU test unavailable." }

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val state = when (pm.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> "Normal"
                    PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                    PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                    PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                    PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                    PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
                    else -> "Unknown"
                }
                lines += "THERMAL STATUS: $state"
            } else lines += "THERMAL STATUS: Not exposed on this Android version"
        } catch (_: Exception) { lines += "THERMAL STATUS: Unavailable" }

        getExternalFilesDirs(null).forEachIndexed { index, dir ->
            if (dir != null) {
                val removable = try { Environment.isExternalStorageRemovable(dir) } catch (_: Exception) { index > 0 }
                val label = if (removable) "REMOVABLE STORAGE (SD card)" else if (index == 0) "INTERNAL STORAGE" else "EXTRA STORAGE"
                lines += "$label: ${formatSize(dir.totalSpace)} total / ${formatSize(dir.usableSpace)} free"
                val speed = storageTest(dir)
                lines += if (speed == null) "  Read/write test unavailable" else "  Temporary-file write ${speed.first} MB/s; read ${speed.second} MB/s"
            }
        }
        lines += ""
        lines += "NOTE: This is a hardware inventory, not an AI test. Next we'll run a real local model to measure actual response speed and memory use."
        return lines.joinToString("\n")
    }

    private fun storageTest(dir: File): Pair<Int, Int>? {
        val file = File(dir, "chuck_bench_temp.bin")
        return try {
            val block = ByteArray(1024 * 1024) { (it * 31).toByte() }
            val writeStart = System.nanoTime()
            FileOutputStream(file).use { out ->
                repeat(4) { out.write(block) }
                out.fd.sync()
            }
            val writeTime = max(0.001, (System.nanoTime() - writeStart) / 1_000_000_000.0)
            val readStart = System.nanoTime()
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (input.read(buffer) != -1) { }
            }
            val readTime = max(0.001, (System.nanoTime() - readStart) / 1_000_000_000.0)
            Pair((4 / writeTime).toInt(), (4 / readTime).toInt())
        } catch (_: Exception) {
            null
        } finally {
            try { file.delete() } catch (_: Exception) { }
        }
    }

    private fun formatSize(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) String.format("%.1f GB", gb) else "${bytes / (1024 * 1024)} MB"
    }
}
