package ca.vehicle.diagnostics

import android.os.Bundle
import androidx.activity.ComponentActivity
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

private val Ink = Color(0xFF101820)
private val Panel = Color(0xFF1B2933)
private val Accent = Color(0xFF63C7B2)
private val Muted = Color(0xFFAAB8C2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ChuckApp() }
    }
}

@Composable
private fun ChuckApp() {
    var complaint by remember { mutableStateOf("") }
    var investigation by remember { mutableStateOf("Tell me what the vehicle is doing. We'll work through the evidence before suggesting parts.") }
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
                Surface(color = Color(0xFF493A22), shape = RoundedCornerShape(20.dp)) {
                    Text("●  NOT CONNECTED", color = Color(0xFFFFD18A), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("VEHICLE", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("No vehicle identified", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Connect a Wi-Fi OBD adapter to begin. No readings are shown until data comes from the vehicle.", color = Muted, fontSize = 12.sp)
                    OutlinedButton(onClick = { investigation = "Wi-Fi adapter support is planned. Connect the tablet to your ELM327 adapter's Wi-Fi network when it is available." }, modifier = Modifier.fillMaxWidth()) {
                        Text("CONNECT VEHICLE")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("ENGINE RPM", "—", Modifier.weight(1f))
                MetricCard("COOLANT", "—", Modifier.weight(1f))
                MetricCard("SYSTEM VOLTS", "—", Modifier.weight(1f))
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallPanel("LIVE DATA", "Available after connection", Modifier.weight(1f))
                SmallPanel("FAULT CODES", "Read before clearing", Modifier.weight(1f))
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
