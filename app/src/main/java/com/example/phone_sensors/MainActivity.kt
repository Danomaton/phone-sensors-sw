package com.example.phone_sensors

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.app.PendingIntent
import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.unit.TextUnit
import com.example.phone_sensors.ui.theme.PhonesensorsTheme



val darkGreen    = Color(0xFF0B3229)
val neonLime     = Color(0xFFE1FF00)
val sandTone     = Color(0xFFEFEADC)
val forestGreen  = Color(0xFF2E473A)
val horizonBlack = Color(0xFF23259A)
val cobaltBlue   = Color(0xFF1C4B97)
val cloudWhite   = Color(0xFFF1F3F4)



class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhonesensorsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BoxGrid(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun BoxGrid(modifier: Modifier = Modifier) {
    val isPreview = LocalInspectionMode.current
    val context = LocalContext.current
    val usbManager = if (!isPreview) context.getSystemService(Context.USB_SERVICE) as UsbManager else null

    val ACTION_USB_PERMISSION = "com.example.phone_sensors.USB_PERMISSION"

    var isConnected by remember { mutableStateOf(false) }
    var i2cSpeedText by remember { mutableStateOf(if (isPreview) "I2C Speed: 100 kHz" else "-1") }

    fun checkDevice() {
        if (usbManager == null) return
        val devices = usbManager.deviceList.values
        val ft260 = devices.find { it.productName?.contains("FT260", ignoreCase = true) == true }
        
        if (ft260 == null) {
            isConnected = false
            i2cSpeedText = "-1"
        } else {
            if (usbManager.hasPermission(ft260)) {
                isConnected = true
                i2cSpeedText = getI2cSpeed(context)
            } else {
                isConnected = false // We consider it "Disconnected" for app logic if not permitted
                i2cSpeedText = "-1"
                val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
                usbManager.requestPermission(ft260, permissionIntent)
            }
        }
    }

    if (!isPreview && usbManager != null) {
        DisposableEffect(context) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    checkDevice()
                }
            }
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }

            checkDevice() // Initial check

            onDispose {
                context.unregisterReceiver(receiver)
            }
        }
    }

    Surface(
        color = forestGreen,
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val status = if (isConnected) "Connected" else "Disconnected"
            
            val altitude = 0.0f
            val temperature = 0.0f
            val humidity = 0.0f
            val pressure = 0.0f
            val airQuality = 0

            // 1. Status Box
            SensorTemplateBox {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    SensorText("Status: $status")
                    Spacer(modifier = Modifier.padding(4.dp))
                    SensorText(
                        text = if (isConnected) "✔" else "✘",
                        color = if (isConnected) Color.Green else Color.Red
                    )
                }
            }

            // 2-6. Sensor Boxes
            SensorTemplateBox { SensorText("Calc Altitude = $altitude m") }
            SensorTemplateBox { SensorText("Temperature = $temperature \u00B0C") }
            SensorTemplateBox { SensorText("Humidity = $humidity %") }
            SensorTemplateBox { SensorText("Pressure = $pressure mbar") }
            SensorTemplateBox { SensorText("Air Quality (VOC) = $airQuality") }

            // 7. I2C Speed Box
            SensorTemplateBox {
                SensorText(i2cSpeedText)
            }
        }
    }
}

@Composable
fun ColumnScope.SensorTemplateBox(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .weight(1f)
            .fillMaxWidth()
            .background(darkGreen, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun SensorText(
    text: String,
    color: Color = neonLime,
    fontSize: TextUnit = 24.sp
) {
    Text(
        text = text,
        color = color,
        textAlign = TextAlign.Center,
        fontSize = fontSize
    )
}

private fun getI2cSpeed(context: Context): String {
    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    val ft260 = usbManager.deviceList.values.find { it.productName?.contains("FT260", ignoreCase = true) == true }
    if (ft260 == null) return "-1"

    // Note: This requires USB permission to be granted. 
    val connection = usbManager.openDevice(ft260) 
    if (connection == null) {
        Log.e("FT260", "openDevice failed (no permission?)")
        return "-1"
    }
    
    val iface = ft260.getInterface(0)
    connection.claimInterface(iface, true)

    val report = ByteArray(64)
    // HID GET_REPORT (Feature): bmRequestType=0xA1, bRequest=0x01, wValue=0x03C0, wIndex=0
    val result = connection.controlTransfer(0xA1, 0x01, 0x03C0, 0, report, 64, 1000)
    
    connection.releaseInterface(iface)
    connection.close()

    if (result < 0) {
        Log.e("FT260", "controlTransfer failed: $result")
        return "-1"
    }

    return if (result >= 4 && report[0] == 0xC0.toByte()) {
        val speed = (report[2].toInt() and 0xFF) or ((report[3].toInt() and 0xFF) shl 8)
        "I2C Speed: $speed kHz"
    } else {
        Log.e("FT260", "Unexpected report: res=$result, id=${report[0].toInt() and 0xFF}")
        "-1"
    }
}

@Preview(showBackground = true)
@Composable
fun BoxGridPreview() {
    PhonesensorsTheme {
        BoxGrid()
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier, backgroundColor: Color = forestGreen ) {
    Surface(color = backgroundColor, modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(24.dp)) {
            Text(
                text = "Hi, my name is $name!",
                color = neonLime,
                textAlign = TextAlign.Center
            )
        }
    }
}
