package ca.vehicle.diagnostics

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.concurrent.thread

private val Ink = Color(0xFF101820)
private val Panel = Color(0xFF1B2933)
private val Accent = Color(0xFF63C7B2)
private val Muted = Color(0xFFAAB8C2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ChuckApp(this) }
    }
}

@Composable
private fun ChuckApp(context: Context) {
    var complaint by remember { mutableStateOf("") }
    var investigation by remember { mutableStateOf("Tell me what the vehicle is doing. We'll work through the evidence before suggesting parts.") }
    var adapterStatus by remember { mutableStateOf("NOT TESTED") }
    var adapterDetails by remember { mutableStateOf("Connect to the adapter's Wi-Fi, then run the read-only connection test.") }
    var adapterBusy by remember { mutableStateOf(false) }
    var adapterConnected by remember { mutableStateOf(false) }
    var liveBusy by remember { mutableStateOf(false) }
    var liveStatus by remember { mutableStateOf("Live readings have not been requested yet.") }
    var rpmValue by remember { mutableStateOf("—") }
    var coolantValue by remember { mutableStateOf("—") }
    var voltsValue by remember { mutableStateOf("—") }
    var codeBusy by remember { mutableStateOf(false) }
    var codeStatus by remember { mutableStateOf("Stored trouble codes have not been read.") }
    var codeResultOk by remember { mutableStateOf(false) }
    var storedCodes by remember { mutableStateOf(emptyList<String>()) }
    var vinBusy by remember { mutableStateOf(false) }
    var vinStatus by remember { mutableStateOf("Vehicle VIN has not been requested.") }
    val prefs = remember { context.getSharedPreferences("chuck_vehicle", Context.MODE_PRIVATE) }
    var vinValue by remember { mutableStateOf(prefs.getString("vin", "") ?: "") }
    var vinSource by remember { mutableStateOf(if (vinValue.isBlank()) "" else "SAVED • RECHECK WHEN CONNECTED") }
    var vehicleSummary by remember { mutableStateOf(if (vinValue.isBlank()) "" else VinTools.summary(vinValue)) }
    var showInfoDialog by remember { mutableStateOf(false) }
    fun lookupVehicleDetails(vin: String) {
        vehicleSummary = VinTools.summary(vin)
        thread(name = "ChuckVinLookup") {
            val decoded = VinTools.decodeOnline(vin)
            if (!decoded.isNullOrBlank()) Handler(Looper.getMainLooper()).post {
                if (vinValue == vin) vehicleSummary = decoded
            }
        }
    }
    val scannerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val scanned = result.data?.getStringExtra(VinScannerActivity.EXTRA_VIN).orEmpty()
            if (VinTools.isValidVin(scanned)) {
                vinValue = scanned
                vinSource = "SCANNED • VERIFIED"
                prefs.edit().putString("vin", scanned).apply()
                lookupVehicleDetails(scanned)
            }
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(
        background = Ink, surface = Panel, primary = Accent,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Column(
            Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CHUCK", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("A better way to find the cause.", color = Muted, fontSize = 13.sp)
                }
                IconButton(onClick = { showInfoDialog = true }) {
                    Text("ⓘ", color = Accent, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    enabled = adapterConnected && !vinBusy,
                    onClick = {
                        vinBusy = true
                        vinStatus = "Requesting VIN from the vehicle ECU…"
                        thread(name = "ChuckReadVin") {
                            val result = WifiElm327.readVin(context)
                            Handler(Looper.getMainLooper()).post {
                                vinBusy = false
                                if (VinTools.isValidVin(result.vin)) {
                                    vinValue = result.vin
                                    vinSource = "ECU • VERIFIED THIS SESSION"
                                    prefs.edit().putString("vin", result.vin).apply()
                                    lookupVehicleDetails(result.vin)
                                }
                                vinStatus = result.message
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) { Text(if (vinBusy) "READING…" else "READ VIN", fontSize = 11.sp) }
            }
            Surface(color = if (adapterConnected) Color(0xFF173D35) else Color(0xFF493A22), shape = RoundedCornerShape(20.dp)) {
                Text(if (adapterConnected) "●  ADAPTER RESPONDING" else "●  $adapterStatus", color = if (adapterConnected) Accent else Color(0xFFFFD18A), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
            }
            if (showInfoDialog) {
                AlertDialog(
                    onDismissRequest = { showInfoDialog = false },
                    title = { Text("About Chuck") },
                    text = { Text("Chuck reads vehicle information and diagnostic data. VIN reading, live data and fault-code checks are read-only. Chuck does not erase trouble codes or change vehicle settings.") },
                    confirmButton = { TextButton(onClick = { showInfoDialog = false }) { Text("CLOSE") } }
                )
            }
            if (vinValue.isNotBlank()) {
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("VEHICLE IDENTIFICATION", color = Accent, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Text(vinSource, color = Muted, fontSize = 9.sp)
                        }
                        Text(vehicleSummary.ifBlank { VinTools.summary(vinValue) }, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(vinValue, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("VIN", vinValue))
                            }, modifier = Modifier.weight(1f)) { Text("COPY VIN") }
                            Button(onClick = {
                                val all = (vehicleSummary.ifBlank { VinTools.summary(vinValue) }) + "\nVIN: " + vinValue
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("Vehicle details", all))
                            }, modifier = Modifier.weight(1f)) { Text("COPY ALL") }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { scannerLauncher.launch(Intent(context, VinScannerActivity::class.java)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("SCAN VIN / BARCODE / QR") }
            Button(onClick = { context.startActivity(Intent(context, DeviceBenchActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) { Text("RUN DEVICE BENCH TEST") }
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("VEHICLE CONNECTION", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("Wi-Fi ELM327 adapter", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Target: 192.168.0.10  •  Ports: 35000 / 23", color = Muted, fontSize = 12.sp)
                    Button(
                        enabled = !adapterBusy,
                        onClick = {
                            adapterBusy = true
                            adapterStatus = "TESTING"
                            adapterDetails = "Checking the Wi-Fi adapter and testing ELM communication…"
                            thread(name = "ChuckElmProbe") {
                                val result = WifiElm327.connectAndIdentify(context)
                                Handler(Looper.getMainLooper()).post {
                                    adapterBusy = false
                                    adapterConnected = result.success
                                    adapterStatus = if (result.success) "CONNECTED" else "NO RESPONSE"
                                    adapterDetails = buildString {
                                        append(result.message)
                                        if (result.adapterInfo.isNotBlank()) append("\nAdapter: ${result.adapterInfo}")
                                        if (result.supportedPids.isNotBlank()) append("\nSupported PID response: ${result.supportedPids}")
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (adapterBusy) "TESTING ADAPTER…" else "TEST WI-FI ADAPTER") }
                    Text(adapterDetails, color = if (adapterConnected) Accent else Muted, fontSize = 12.sp)
                    Text("Read-only test. No codes are erased and no vehicle settings are changed.", color = Muted, fontSize = 11.sp)
                    Button(
                        enabled = adapterConnected && !liveBusy,
                        onClick = {
                            liveBusy = true
                            liveStatus = "Requesting live values from the vehicle ECU…"
                            thread(name = "ChuckLiveData") {
                                val result = WifiElm327.readLiveData(context)
                                Handler(Looper.getMainLooper()).post {
                                    liveBusy = false
                                    rpmValue = result.rpm
                                    coolantValue = result.coolant
                                    voltsValue = result.volts
                                    liveStatus = result.message
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (liveBusy) "READING LIVE DATA…" else "READ LIVE DATA") }
                    Text(liveStatus, color = Muted, fontSize = 11.sp)
                    OutlinedButton(
                        enabled = adapterConnected && !codeBusy,
                        onClick = {
                            codeBusy = true
                            codeResultOk = false
                            storedCodes = emptyList()
                            codeStatus = "STARTING • Connecting to adapter and requesting ECU Mode 03 response…"
                            thread(name = "ChuckReadCodes") {
                                val result = try {
                                    WifiElm327.readStoredCodes(context)
                                } catch (e: Exception) {
                                    ElmTroubleCodeResult(false, "SCAN ERROR • ${e.message ?: "Unexpected communication error"}")
                                }
                                Handler(Looper.getMainLooper()).post {
                                    codeBusy = false
                                    codeResultOk = result.success
                                    storedCodes = result.codes
                                    codeStatus = (if (result.success) "COMPLETE • " else "FAILED • ") + result.message
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (codeBusy) "SCANNING ECU…" else "READ STORED TROUBLE CODES") }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            color = when {
                                codeBusy -> Color(0xFF493A22)
                                codeResultOk -> Color(0xFF173D35)
                                codeStatus.startsWith("FAILED") -> Color(0xFF512B2B)
                                else -> Color(0xFF293640)
                            },
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text(
                                when {
                                    codeBusy -> "●  SCAN IN PROGRESS"
                                    codeResultOk -> "●  ECU RESPONDED"
                                    codeStatus.startsWith("FAILED") -> "●  SCAN FAILED"
                                    else -> "●  WAITING"
                                },
                                color = when {
                                    codeBusy -> Color(0xFFFFD18A)
                                    codeResultOk -> Accent
                                    codeStatus.startsWith("FAILED") -> Color(0xFFFF9999)
                                    else -> Muted
                                },
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                        if (codeBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Accent,
                                strokeWidth = 2.dp
                            )
                        }
                    }
                    if (codeBusy) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Accent,
                            trackColor = Color(0xFF34434D)
                        )
                    }
                    Text(codeStatus, color = if (codeResultOk) Accent else Muted, fontSize = 11.sp)
                    Text("Read-only: this reads Mode 03 codes. It cannot erase codes.", color = Muted, fontSize = 11.sp)
                    Text(vinStatus, color = Muted, fontSize = 11.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("ENGINE RPM", rpmValue, Modifier.weight(1f))
                MetricCard("COOLANT", coolantValue, Modifier.weight(1f))
                MetricCard("SYSTEM VOLTS", voltsValue, Modifier.weight(1f))
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("WHAT'S THE SYMPTOM?", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    OutlinedTextField(
                        value = complaint, onValueChange = { complaint = it },
                        modifier = Modifier.fillMaxWidth(), minLines = 3,
                        placeholder = { Text("Describe what happens, when it happens, and anything you've noticed…") }
                    )
                    Button(onClick = {
                        investigation = if (complaint.isBlank()) "Start with the symptom: what does the vehicle do, and under what conditions?" else
                            "Complaint recorded: $complaint\n\nNext step: establish when it happens and what changes it. No parts will be recommended until we have a reason."
                    }, modifier = Modifier.fillMaxWidth()) { Text("START INVESTIGATION") }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("CURRENT INVESTIGATION", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(investigation, color = Color.White, fontSize = 14.sp)
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("STORED FAULT CODES", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(codeStatus, color = Muted, fontSize = 12.sp)
                    if (storedCodes.isNotEmpty()) {
                        storedCodes.forEach { code ->
                            Text(code, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallPanel("CASE HISTORY", "Keep what works", Modifier.weight(1f))
                SmallPanel("RESEARCH", "Docs and real-world cases", Modifier.weight(1f))
            }
            Text("Experience is data, even when it isn't proof.", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SmallPanel(title: String, detail: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, color = Accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text(detail, color = Muted, fontSize = 11.sp)
        }
    }
}
