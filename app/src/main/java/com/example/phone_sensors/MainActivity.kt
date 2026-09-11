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
import androidx.compose.runtime.LaunchedEffect
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
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.unit.TextUnit
import com.example.phone_sensors.ui.theme.PhonesensorsTheme
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.pow


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

    var altitude by remember { mutableStateOf(0.0f) }
    var temperature by remember { mutableStateOf(0.0f) }
    var humidity by remember { mutableStateOf(0.0f) }
    var pressure by remember { mutableStateOf(0.0f) }
    var airQuality by remember { mutableStateOf(0) }
    var vocIndex by remember { mutableStateOf(0) }

    val ms8607C = remember { LongArray(7) }
    var isCalibrated by remember { mutableStateOf(false) }
    val gasAlgo = remember { GasIndexAlgorithm() }

    LaunchedEffect(isConnected) {
        if (isConnected && usbManager != null) {
            // Reset calibration and algorithm states for the new board
            isCalibrated = false
            gasAlgo.reset()
            altitude = 0.0f
            temperature = 0.0f
            humidity = 0.0f
            pressure = 0.0f
            airQuality = 0
            vocIndex = 0
            
            val devices = usbManager.deviceList.values
            val ft260 = devices.find { it.productName?.contains("FT260", ignoreCase = true) == true }
            ft260?.let { device ->
                val connection = usbManager.openDevice(device)
                if (connection != null) {
                    val iface = device.getInterface(0)
                    connection.claimInterface(iface, true)

                    val endpoints = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
                    val epIn = endpoints.find { it.direction == UsbConstants.USB_DIR_IN }
                    val epOut = endpoints.find { it.direction == UsbConstants.USB_DIR_OUT }

                    if (epIn != null && epOut != null) {
                        // 1. Initial Calibration (Read PROM)
                        if (!isCalibrated) {
                            try {
                                writeI2C(connection, epOut, 0x76, byteArrayOf(0x1E.toByte()))
                                delay(20)
                                
                                for (i in 0..6) {
                                    writeI2C(connection, epOut, 0x76, byteArrayOf((0xA0 + i * 2).toByte()))
                                    val res = readI2C(connection, epIn, epOut, 0x76, 2)
                                    if (res != null) {
                                        ms8607C[i] = ((res[0].toInt() and 0xFF).toLong() shl 8) or (res[1].toInt() and 0xFF).toLong()
                                    }
                                }
                                isCalibrated = true
                            } catch (e: Exception) {
                                Log.e("FT260", "Calibration failed", e)
                            }
                        }

                        // 2. Polling Loop
                        while (isConnected) {
                            try {
                                // --- Pressure & Temperature (Address 0x76) ---
                                
                                // A. Convert Temperature (D2)
                                writeI2C(connection, epOut, 0x76, byteArrayOf(0x58.toByte())) // OSR 4096
                                delay(20)
                                writeI2C(connection, epOut, 0x76, byteArrayOf(0x00.toByte()))
                                val d2Data = readI2C(connection, epIn, epOut, 0x76, 3)
                                
                                // B. Convert Pressure (D1)
                                writeI2C(connection, epOut, 0x76, byteArrayOf(0x48.toByte())) // OSR 4096
                                delay(20)
                                writeI2C(connection, epOut, 0x76, byteArrayOf(0x00.toByte()))
                                val d1Data = readI2C(connection, epIn, epOut, 0x76, 3)

                                if (d2Data != null && d1Data != null) {
                                    val D2 = ((d2Data[0].toInt() and 0xFF).toLong() shl 16) or 
                                             ((d2Data[1].toInt() and 0xFF).toLong() shl 8) or 
                                             (d2Data[2].toInt() and 0xFF).toLong()
                                    
                                    val D1 = ((d1Data[0].toInt() and 0xFF).toLong() shl 16) or 
                                             ((d1Data[1].toInt() and 0xFF).toLong() shl 8) or 
                                             (d1Data[2].toInt() and 0xFF).toLong()

                                    // 1. Temperature Calculation
                                    val dT = D2 - ms8607C[5] * 256
                                    val tempCenti = 2000 + (dT * ms8607C[6]) / 8388608
                                    temperature = tempCenti.toFloat() / 100.0f

                                    // 2. Pressure Calculation
                                    var OFF = ms8607C[2] * 131072 + (ms8607C[4] * dT) / 64
                                    var SENS = ms8607C[1] * 65536 + (ms8607C[3] * dT) / 128

                                    // Second order compensation
                                    if (tempCenti < 2000) {
                                        val OFF2 = 61 * (tempCenti - 2000) * (tempCenti - 2000) / 16
                                        val SENS2 = 29 * (tempCenti - 2000) * (tempCenti - 2000) / 16
                                        OFF -= OFF2
                                        SENS -= SENS2
                                    }

                                    val pCenti = ((D1 * SENS) / 2097152 - OFF) / 32768
                                    pressure = pCenti.toFloat() / 100.0f

                                    // 3. Altitude Calculation
                                    altitude = 44330f * (1f - (pressure / 1013.25f).pow(1f / 5.255f))
                                }

                            // --- Humidity (Address 0x40) ---
                            writeI2C(connection, epOut, 0x40, byteArrayOf(0xF5.toByte())) // No hold
                            delay(30)
                            val d3Data = readI2C(connection, epIn, epOut, 0x40, 3)
                            if (d3Data != null) {
                                val D3 = ((d3Data[0].toInt() and 0xFF) shl 8) or (d3Data[1].toInt() and 0xFF)
                                humidity = -6.0f + 125.0f * (D3.toFloat() / 65536.0f)
                            }

                            // --- Air Quality SGP40 (Address 0x59) ---
                            val humTicks = (humidity * 65535 / 100).toInt().coerceIn(0, 65535)
                            val tempTicks = ((temperature + 45) * 65535 / 175).toInt().coerceIn(0, 65535)
                            
                            val sgpCmd = ByteArray(8)
                            sgpCmd[0] = 0x26.toByte()
                            sgpCmd[1] = 0x0F.toByte()
                            sgpCmd[2] = (humTicks shr 8).toByte()
                            sgpCmd[3] = (humTicks and 0xFF).toByte()
                            sgpCmd[4] = calculateCrc8(sgpCmd[2], sgpCmd[3])
                            sgpCmd[5] = (tempTicks shr 8).toByte()
                            sgpCmd[6] = (tempTicks and 0xFF).toByte()
                            sgpCmd[7] = calculateCrc8(sgpCmd[5], sgpCmd[6])

                            writeI2C(connection, epOut, 0x59, sgpCmd)
                            delay(30)
                            val sgpData = readI2C(connection, epIn, epOut, 0x59, 3)
                            if (sgpData != null) {
                                // Check CRC of returned raw signal
                                if (calculateCrc8(sgpData[0], sgpData[1]) == sgpData[2]) {
                                    val rawVoc = ((sgpData[0].toInt() and 0xFF) shl 8) or (sgpData[1].toInt() and 0xFF)
                                    airQuality = rawVoc
                                    vocIndex = gasAlgo.process(rawVoc)
                                }
                            }

                            } catch (e: Exception) {
                                Log.e("FT260", "Read error", e)
                            }
                            delay(1000)
                        }
                    }

                    connection.releaseInterface(iface)
                    connection.close()
                }
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
            SensorTemplateBox { SensorText("Calc Altitude = ${String.format(Locale.US, "%.1f", altitude)} m") }
            SensorTemplateBox { SensorText("Temperature = ${String.format(Locale.US, "%.2f", temperature)} \u00B0C") }
            SensorTemplateBox { SensorText("Humidity = ${String.format(Locale.US, "%.1f", humidity)} %") }
            SensorTemplateBox { SensorText("Pressure = ${String.format(Locale.US, "%.2f", pressure)} mbar") }
            SensorTemplateBox { SensorText("VOC Index = $vocIndex ($airQuality)") }

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

private fun writeI2C(connection: UsbDeviceConnection, endpoint: UsbEndpoint, address: Int, data: ByteArray): Boolean {
    val len = data.size.coerceAtMost(60)
    val reportId = (0xD0 + (len - 1) / 4).toByte()
    val report = ByteArray(64)
    report[0] = reportId
    report[1] = address.toByte()
    report[2] = 0x06 // START_STOP
    report[3] = len.toByte()
    System.arraycopy(data, 0, report, 4, len)
    return connection.bulkTransfer(endpoint, report, 64, 1000) >= 0
}

private fun readI2C(connection: UsbDeviceConnection, epIn: UsbEndpoint, epOut: UsbEndpoint, address: Int, length: Int): ByteArray? {
    val req = ByteArray(64)
    req[0] = 0xC2.toByte()
    req[1] = address.toByte()
    req[2] = 0x06 // START_STOP
    req[3] = (length and 0xFF).toByte()
    req[4] = ((length shr 8) and 0xFF).toByte()
    connection.bulkTransfer(epOut, req, 64, 1000)

    val res = ByteArray(64)
    val received = connection.bulkTransfer(epIn, res, 64, 1000)
    if (received > 0 && (res[0].toInt() and 0xFF) >= 0xD0) {
        val dataLen = res[1].toInt() and 0xFF
        return res.copyOfRange(2, 2 + dataLen)
    }
    return null
}

private fun calculateCrc8(b1: Byte, b2: Byte): Byte {
    var crc = 0xFF
    val data = byteArrayOf(b1, b2)
    for (i in 0..1) {
        crc = crc xor (data[i].toInt() and 0xFF)
        repeat(8) {
            if (crc and 0x80 != 0) {
                crc = (crc shl 1) xor 0x31
            } else {
                crc = crc shl 1
            }
        }
    }
    return (crc and 0xFF).toByte()
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
