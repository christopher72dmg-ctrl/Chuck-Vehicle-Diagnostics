package ca.vehicle.diagnostics

import android.Manifest
import android.app.Activity
import androidx.activity.ComponentActivity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class VinScannerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_VIN = "ca.vehicle.diagnostics.EXTRA_VIN"
        private const val CAMERA_REQUEST = 7102
    }

    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var manualVin: EditText
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val barcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_PDF417,
                    Barcode.FORMAT_AZTEC
                ).build()
        )
    }
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF101820.toInt()
        window.navigationBarColor = 0xFF101820.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 14)
            setBackgroundColor(0xFF101820.toInt())
        }
        val heading = TextView(this).apply {
            text = "SCAN VEHICLE VIN"
            textSize = 20f
            setTextColor(0xFF63C7B2.toInt())
            gravity = Gravity.CENTER_VERTICAL
        }
        status = TextView(this).apply {
            text = "Aim at the 17-character VIN or its barcode / QR label."
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 8, 0, 10)
        }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        manualVin = EditText(this).apply {
            hint = "Or enter VIN manually"
            setSingleLine(true)
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF9EABB4.toInt())
            setBackgroundColor(0xFF1B2933.toInt())
        }
        val useButton = Button(this).apply {
            text = "CHECK AND USE VIN"
            setOnClickListener { acceptVin(manualVin.text.toString()) }
        }
        val cancel = Button(this).apply {
            text = "CANCEL"
            setOnClickListener { finish() }
        }
        root.addView(heading)
        root.addView(status)
        root.addView(previewView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(manualVin)
        root.addView(useButton)
        root.addView(cancel)
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy -> analyze(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                runOnUiThread { status.text = "Hold the VIN or label steady inside the camera view." }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Camera unavailable: ${e.message ?: "unknown error"}" }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        val media = proxy.image
        if (media == null) {
            busy.set(false)
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        val barcodeTask = barcodeScanner.process(image)
        val textTask = textRecognizer.process(image)
        com.google.android.gms.tasks.Tasks.whenAllComplete(listOf(barcodeTask, textTask))
            .addOnCompleteListener {
                try {
                    val barcodeVin = if (barcodeTask.isSuccessful) {
                        barcodeTask.result?.firstNotNullOfOrNull { code ->
                            code.rawValue?.let { VinTools.findCandidate(it) }
                        }
                    } else null
                    val textVin = if (textTask.isSuccessful) {
                        textTask.result?.text?.let { VinTools.findCandidate(it) }
                    } else null
                    val candidate = barcodeVin ?: textVin
                    if (candidate != null && VinTools.isValidVin(candidate)) {
                        runOnUiThread { acceptVin(candidate) }
                    } else if (candidate != null) {
                        runOnUiThread { status.text = "VIN-like text found, but its check digit is invalid. Try a clearer angle or enter it manually." }
                    }
                } finally {
                    busy.set(false)
                    proxy.close()
                }
            }
    }

    private fun acceptVin(raw: String) {
        val candidate = VinTools.findCandidate(raw) ?: raw.uppercase().filter { it.isLetterOrDigit() }
        if (!VinTools.isValidVin(candidate)) {
            status.text = "Please check the VIN. It must be 17 characters and pass its check-digit test."
            return
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_VIN, candidate))
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
            else status.text = "Camera permission was declined. You can still enter the VIN manually."
        }
    }

    override fun onDestroy() {
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Exception) { }
        try { barcodeScanner.close() } catch (_: Exception) { }
        try { textRecognizer.close() } catch (_: Exception) { }
        executor.shutdown()
        super.onDestroy()
    }
}

object VinTools {
    private const val ALLOWED = "ABCDEFGHJKLMNPRSTUVWXYZ0123456789"
    private val transliteration = mapOf(
        'A' to 1, 'B' to 2, 'C' to 3, 'D' to 4, 'E' to 5, 'F' to 6, 'G' to 7, 'H' to 8,
        'J' to 1, 'K' to 2, 'L' to 3, 'M' to 4, 'N' to 5, 'P' to 7, 'R' to 9,
        'S' to 2, 'T' to 3, 'U' to 4, 'V' to 5, 'W' to 6, 'X' to 7, 'Y' to 8, 'Z' to 9,
        '0' to 0, '1' to 1, '2' to 2, '3' to 3, '4' to 4, '5' to 5, '6' to 6, '7' to 7, '8' to 8, '9' to 9
    )
    private val weights = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)

    fun findCandidate(raw: String): String? {
        val upper = raw.uppercase()
        Regex("[A-HJ-NPR-Z0-9]{17}").find(upper)?.value?.let { return it }
        val compact = upper.filter { it.isLetterOrDigit() }
        return if (compact.length == 17 && compact.all { it in ALLOWED }) compact else null
    }

    fun isValidVin(vin: String): Boolean {
        if (vin.length != 17 || vin.any { it !in ALLOWED }) return false
        val sum = vin.indices.sumOf { index ->
            (transliteration[vin[index]] ?: return false) * weights[index]
        }
        val remainder = sum % 11
        val expected = if (remainder == 10) 'X' else ('0'.code + remainder).toChar()
        return vin[8] == expected
    }

    fun decodeOnline(vin: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValuesExtended/$vin?format=json").openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                requestMethod = "GET"
            }
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val result = JSONObject(body).optJSONArray("Results")?.optJSONObject(0) ?: return null
            fun value(key: String): String {
                val raw = result.optString(key, "").trim()
                return if (raw.isBlank() || raw.equals("Not Applicable", true) || raw.equals("0", true)) "" else raw
            }
            val year = value("ModelYear")
            val make = value("Make")
            val model = value("Model")
            if (year.isBlank() && make.isBlank() && model.isBlank()) return null
            val title = listOf(year, make, model).filter { it.isNotBlank() }.joinToString(" ")
            val engine = listOf(
                value("DisplacementL").takeIf { it.isNotBlank() }?.let { "$it L" } ?: "",
                value("EngineCylinders").takeIf { it.isNotBlank() }?.let { "$it-cylinder" } ?: "",
                value("EngineModel")
            ).filter { it.isNotBlank() }.joinToString(" ")
            val drive = value("DriveType")
            listOf(title, engine, drive).filter { it.isNotBlank() }.joinToString(" • ")
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun summary(vin: String): String {
        val yearCode = vin.getOrNull(9) ?: return "Vehicle details"
        val year = yearFor(yearCode)
        val wmi = vin.take(3)
        val make = when {
            wmi.startsWith("2D4") -> "Dodge"
            wmi.startsWith("2C3") -> "Chrysler"
            wmi.startsWith("1C4") || wmi.startsWith("2C4") -> "Chrysler / Dodge"
            wmi.startsWith("1G") || wmi.startsWith("2G") || wmi.startsWith("3G") -> "General Motors"
            wmi.startsWith("1FA") || wmi.startsWith("2FA") || wmi.startsWith("3FA") -> "Ford"
            else -> "Vehicle"
        }
        return "$year $make • Model details require full VIN database lookup"
    }

    private fun yearFor(code: Char): String {
        val map = mapOf(
            '1' to 2001, '2' to 2002, '3' to 2003, '4' to 2004, '5' to 2005,
            '6' to 2006, '7' to 2007, '8' to 2008, '9' to 2009,
            'A' to 2010, 'B' to 2011, 'C' to 2012, 'D' to 2013, 'E' to 2014,
            'F' to 2015, 'G' to 2016, 'H' to 2017, 'J' to 2018, 'K' to 2019,
            'L' to 2020, 'M' to 2021, 'N' to 2022, 'P' to 2023, 'R' to 2024,
            'S' to 2025, 'T' to 2026, 'V' to 2027, 'W' to 2028, 'X' to 2029, 'Y' to 2030
        )
        return map[code]?.toString() ?: "Unknown year"
    }
}
