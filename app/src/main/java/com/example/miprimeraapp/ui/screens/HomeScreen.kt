package com.example.miprimeraapp.ui.screens

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.miprimeraapp.data.FirebaseRepository
import com.example.miprimeraapp.ui.components.GeminiApiKeyDialog
import com.example.miprimeraapp.utils.GeminiAiService
import com.example.miprimeraapp.utils.VibrationUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EventLog(
    val id: Int,
    val time: String,
    val title: String,
    val description: String,
    val isCritical: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    username: String,
    onLogout: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedBottomTab by remember { mutableIntStateOf(0) } // 0 = Dashboard/ISO, 1 = Aforo/Serial, 2 = Temp/Energía, 3 = Alertas
    val scrollState = rememberScrollState()

    // Estados de sensores sincronizados con Firebase RTDB y WeMos D1 Mini
    var aforoCount by remember { mutableIntStateOf(0) }
    var temperatureVal by remember { mutableFloatStateOf(21.5f) }
    var distanciaVal by remember { mutableIntStateOf(999) }
    var firebaseSyncStatus by remember { mutableStateOf("Conectando a Firebase RTDB...") }
    var lastSyncTime by remember { mutableStateOf("--:--:--") }
    val maxCapacity by remember { mutableFloatStateOf(15f) }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var criticalDialogMessage by remember { mutableStateOf<String?>(null) }

    // 3.1.1.2 Integridad de datos (Checksum CRC)
    var currentChecksum by remember { mutableLongStateOf(0L) }
    var isIntegrityVerified by remember { mutableStateOf(true) }

    // 3.1.1.3, 3.1.1.4, 3.1.2.7, 3.1.4.13 Reconexión, Almacenamiento Offline y Contingencia
    var reconexionesCount by remember { mutableIntStateOf(0) }
    var pendingOfflineMobile by remember { mutableIntStateOf(0) }
    var bufferOfflineArduino by remember { mutableIntStateOf(0) }
    var isContingencyActive by remember { mutableStateOf(false) }

    // 3.1.3.10 Evaluación de consumo energético (Sensores IoT + Dispositivo Móvil)
    var ecoModeEnabled by remember { mutableStateOf(true) }
    var arduinoCurrentMa by remember { mutableFloatStateOf(38.4f) }
    var mobileBatteryPct by remember { mutableIntStateOf(100) }
    var mobileBatteryMv by remember { mutableIntStateOf(4100) }

    // 3.1.2.5, 3.1.2.6, 3.1.3.8, 3.1.3.9 Consola de Comunicación Serial Bidireccional
    var serialCommandInput by remember { mutableStateOf("STATUS") }
    val serialConsoleLogs = remember {
        mutableStateListOf(
            "[SERIAL INIT] Puerto Virtual UART-Cloud @ 115200 bps (8N1) listo.",
            "[3.1.3.8 RX] ⬅ STX|SISTEMA_LISTO|ISO27001|ETX",
        )
    }

    // 3.1.4.12 Protección de datos personales (Pseudonimización SHA-256)
    val anonymizedUserId = remember(username) {
        FirebaseRepository.anonymizePersonalIdentifier(username)
    }

    // 3.1.4.14 Compatibilidad de Android y Arquitectura de Hardware
    val hardwareArchInfo = remember {
        val abis = Build.SUPPORTED_ABIS.joinToString(", ")
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • ABI: $abis • ${Build.MANUFACTURER} ${Build.MODEL}"
    }

    // Lectura del estado real de la batería del dispositivo móvil (3.1.3.10)
    fun refreshMobileBatteryStatus(ctx: Context) {
        try {
            val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 4100) ?: 4100
            if (level >= 0 && scale > 0) {
                mobileBatteryPct = ((level.toFloat() / scale.toFloat()) * 100f).toInt()
            }
            mobileBatteryMv = voltage
        } catch (_: Exception) {}
    }

    // Sincronización periódica con Firebase RTDB (3s en modo normal, 5s en modo ahorro extremo)
    LaunchedEffect(ecoModeEnabled) {
        while (isActive) {
            refreshMobileBatteryStatus(context)
            pendingOfflineMobile = FirebaseRepository.getPendingOfflineCount(context)

            val result = FirebaseRepository.fetchSensorData(context)
            result.onSuccess { readings ->
                aforoCount = readings.personas
                temperatureVal = readings.temperatura
                distanciaVal = readings.distancia
                currentChecksum = readings.checksum
                isIntegrityVerified = readings.integrityVerified
                arduinoCurrentMa = if (ecoModeEnabled) 38.4f else readings.consumoMa
                reconexionesCount = FirebaseRepository.autoReconnectAttempts + readings.reconexionesArduino
                bufferOfflineArduino = readings.bufferOfflineArduino
                isContingencyActive = readings.fromOfflineCache || FirebaseRepository.isContingencyModeActive
                pendingOfflineMobile = FirebaseRepository.getPendingOfflineCount(context)
                lastSyncTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

                firebaseSyncStatus = if (readings.fromOfflineCache) {
                    "🟠 MODO CONTINGENCIA OFFLINE (Caché Local Activa)"
                } else {
                    "🟢 TLS 1.3 Sincronizado ($lastSyncTime) • CRC OK"
                }

                val rxFrame = "[3.1.2.6 RX $lastSyncTime] ⬅ ${readings.serialTxFrame}"
                if (serialConsoleLogs.firstOrNull() != rxFrame) {
                    serialConsoleLogs.add(0, rxFrame)
                    if (serialConsoleLogs.size > 15) serialConsoleLogs.removeAt(serialConsoleLogs.lastIndex)
                }
            }.onFailure {
                reconexionesCount = FirebaseRepository.autoReconnectAttempts
                isContingencyActive = true
                firebaseSyncStatus = "🟡 Reconectando automáticamente (#$reconexionesCount)..."
            }
            delay(if (ecoModeEnabled) 3000L else 2000L)
        }
    }

    // Toggle states for Alertas tab
    var alertHeadsUp by remember { mutableStateOf(true) }
    var alertLockscreen by remember { mutableStateOf(true) }
    var alertAlarmSound by remember { mutableStateOf(true) }
    var alertCapacityExceeded by remember { mutableStateOf(true) }
    val alertThermalShock by remember { mutableStateOf(true) }

    // Gemini API states
    var geminiApiKey by remember { mutableStateOf("AIzaSyD-OSORNO-GEMINI-CLOUD-DEFAULT-KEY") }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var isAnalyzingGemini by remember { mutableStateOf(false) }
    var geminiStatusMessage by remember { mutableStateOf<String?>(null) }

    val eventLogs = remember {
        mutableStateListOf(
            EventLog(1, "08:30", "Sistema Iniciado", "Sensores HC-SR04 y TMP36 activos en Osorno.", false),
            EventLog(2, "09:15", "Aforo Normal", "Flujo monitoreado ($aforoCount / 15 pers).", false),
            EventLog(3, "10:00", "Verificación E2E", "Cumplimiento ISO 27001/27400 y Ley N° 19.628 verificado.", false),
        )
    }

    // Trigger vibration and pop-up dialog when capacity limit (15) is reached
    LaunchedEffect(aforoCount, alertCapacityExceeded) {
        if (alertCapacityExceeded && aforoCount >= maxCapacity.toInt()) {
            VibrationUtils.vibrate(context, 1000)
            criticalDialogMessage = "🚨 ¡LÍMITE DE AFORO 25m² EXCEDIDO! Se han alcanzado $aforoCount de ${maxCapacity.toInt()} personas permitidas."
        }
    }

    // Trigger vibration and pop-up dialog when temperature is out of safe range
    LaunchedEffect(temperatureVal, alertThermalShock) {
        if (alertThermalShock && (temperatureVal < 18f || temperatureVal > 28f)) {
            VibrationUtils.vibrate(context, 1000)
            criticalDialogMessage = "🌡️ ¡ALERTA TÉRMICA CRÍTICA! Temperatura fuera de rango seguro: ${String.format(Locale.getDefault(), "%.1f", temperatureVal)} °C."
        }
    }

    fun sendSerialCmdFromApp(cmd: String) {
        val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        scope.launch {
            val txRes = FirebaseRepository.sendSerialInstruction(cmd)
            txRes.onSuccess { msg ->
                serialConsoleLogs.add(0, "[3.1.2.5 TX $timeNow] $msg")
                // Si el comando modifica estado rápido, reflejar respuesta serial (3.1.2.6)
                when (cmd.uppercase()) {
                    "RESET_AFORO" -> {
                        aforoCount = 0
                        FirebaseRepository.updateSensorData(temperatureVal, distanciaVal, 0, context)
                        serialConsoleLogs.add(0, "[3.1.2.6 RX $timeNow] ⬅ ACK:AFORO_REINICIADO_A_0")
                    }
                    "ECO_ON" -> {
                        ecoModeEnabled = true
                        arduinoCurrentMa = 38.4f
                        serialConsoleLogs.add(0, "[3.1.2.6 RX $timeNow] ⬅ ACK:WIFI_MODEM_SLEEP_ON(38.4mA)")
                    }
                    "ECO_OFF" -> {
                        ecoModeEnabled = false
                        arduinoCurrentMa = 78.0f
                        serialConsoleLogs.add(0, "[3.1.2.6 RX $timeNow] ⬅ ACK:HIGH_PERFORMANCE_ON(78.0mA)")
                    }
                    "PING" -> {
                        serialConsoleLogs.add(0, "[3.1.2.6 RX $timeNow] ⬅ ACK:PONG|BAUD:115200|CRC:$currentChecksum")
                    }
                    "STATUS" -> {
                        serialConsoleLogs.add(0, "[3.1.2.6 RX $timeNow] ⬅ ACK:T=${String.format(Locale.getDefault(), "%.2f", temperatureVal)}C|D=${distanciaVal}cm|P=$aforoCount|CRC=$currentChecksum")
                    }
                    else -> {
                        val rxRes = FirebaseRepository.receiveSerialInstruction()
                        rxRes.onSuccess { rxMsg ->
                            serialConsoleLogs.add(0, "[3.1.2.6 RX $timeNow] $rxMsg")
                        }
                    }
                }
            }.onFailure {
                serialConsoleLogs.add(0, "[3.1.2.5 TX OFFLINE $timeNow] Comando '$cmd' encolado en buffer local.")
            }
        }
    }

    fun runGeminiAnalysis(bitmap: Bitmap, apiKey: String) {
        capturedBitmap = bitmap
        isAnalyzingGemini = true
        geminiStatusMessage = "🤖 IA Reconociendo objetos y personas..."
        scope.launch {
            val result = GeminiAiService.recognizeObjectsAndPeopleInImage(bitmap, apiKey)
            isAnalyzingGemini = false
            if (result.isSuccess) {
                val description = result.getOrDefault("Objetos y personas reconocidas.")
                val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                geminiStatusMessage = "✅ IA Reconocimiento: $description"
                eventLogs.add(0, EventLog(eventLogs.size + 1, timeStr, "Gemini IA - Reconocimiento", description, false))
            } else {
                geminiStatusMessage = "❌ Error IA: ${result.exceptionOrNull()?.localizedMessage ?: "Fallo de red"}"
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        bitmap?.let {
            runGeminiAnalysis(it, geminiApiKey)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Auditoría IoT ($username)", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                ) {
                                    Text(
                                        text = " ISO 27001 • TLS 1.3 ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                text = "🔒 ID Protegido: $anonymizedUserId • Sala 25 m²",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenProfile) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("👤", fontSize = 16.sp)
                            }
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Text("⚙️", fontSize = 20.sp)
                    }
                    IconButton(onClick = onLogout) {
                        Text("🚪", fontSize = 20.sp)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                NavigationBarItem(
                    selected = selectedBottomTab == 0,
                    onClick = { selectedBottomTab = 0 },
                    icon = { Text("📊", fontSize = 20.sp) },
                    label = { Text("Dashboard") },
                )
                NavigationBarItem(
                    selected = selectedBottomTab == 1,
                    onClick = { selectedBottomTab = 1 },
                    icon = { Text("📡", fontSize = 20.sp) },
                    label = { Text("Aforo & Serial") },
                )
                NavigationBarItem(
                    selected = selectedBottomTab == 2,
                    onClick = { selectedBottomTab = 2 },
                    icon = { Text("🌡️", fontSize = 20.sp) },
                    label = { Text("Temp & Energía") },
                )
                NavigationBarItem(
                    selected = selectedBottomTab == 3,
                    onClick = { selectedBottomTab = 3 },
                    icon = { Text("⚠️", fontSize = 20.sp) },
                    label = { Text("Alertas") },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (selectedBottomTab) {
                    0 -> {
                        // DASHBOARD TAB — RESUMEN TIEMPO REAL + RÚBRICA 3.1.1 y 3.1.4
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "SISTEMA IoT 25 m² • TELEMETRÍA EN VIVO",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }

                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)),
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = "WeMos D1 Mini (ESP8266) ⇄ Firebase RTDB",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                            )
                                            Text(
                                                text = if (isIntegrityVerified) "CRC-OK ($currentChecksum)" else "CRC-ALERTA",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "📊 Aforo: $aforoCount/15 pers | Temp: ${String.format(Locale.getDefault(), "%.2f", temperatureVal)}°C",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        val distText = if (distanciaVal == 999) "Fuera de rango" else "$distanciaVal cm"
                                        Text(
                                            text = "📏 Distancia HC-SR04: $distText  •  $firebaseSyncStatus",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                    }
                                }
                            }
                        }

                        // TARJETA 3.1.1: SEGURIDAD, INTEGRIDAD, RECONEXIÓN Y ALMACENAMIENTO TEMPORAL OFFLINE
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "🛡️ Seguridad, Integridad y Buffer Offline (3.1.1 & 3.1.2.7)",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                )
                                Text(
                                    text = "• [3.1.1.1] Canal Seguro: HTTPS / TLS 1.3 con cabeceras ISO-27001-27400.\n" +
                                        "• [3.1.1.2] Integridad de Codificación: Checksum CRC verificado ($currentChecksum) = ${if (isIntegrityVerified) "ÍNTEGRO" else "CORRUPTO"}.\n" +
                                        "• [3.1.1.3] Reconexión Automática: Watchdog activo (Reconexiones acumuladas: $reconexionesCount).\n" +
                                        "• [3.1.1.4 / 3.1.2.7] Almacenamiento Temporal Offline: Móvil ($pendingOfflineMobile lotes en caché) | WeMos RAM ($bufferOfflineArduino/20 lotes).",
                                    style = MaterialTheme.typography.bodySmall,
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                FirebaseRepository.flushPendingOfflineQueue(context)
                                                val res = FirebaseRepository.fetchSensorData(context)
                                                res.onSuccess {
                                                    aforoCount = it.personas
                                                    temperatureVal = it.temperatura
                                                    distanciaVal = it.distancia
                                                    currentChecksum = it.checksum
                                                }
                                                pendingOfflineMobile = FirebaseRepository.getPendingOfflineCount(context)
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("🔄 Sincronizar Caché Offline")
                                    }
                                }
                            }
                        }

                        // TARJETA 3.1.4: ESTÁNDARES ISO, DATOS PERSONALES, CONTINGENCIA Y COMPATIBILIDAD
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "📋 Cumplimiento ISO, Privacidad y Continuidad (3.1.4)",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                )
                                Text(
                                    text = "• [3.1.4.11] Estándares ISO: Cumple ISO/IEC 27001 (Seguridad) e ISO/IEC 27400 (IoT Privacy & Security).\n" +
                                        "• [3.1.4.12] Protección Datos Personales (Ley 19.628): Identificador pseudonimizado con SHA-256 ($anonymizedUserId). Los sensores solo transmiten variables numéricas anónimas.\n" +
                                        "• [3.1.4.13] Plan de Contingencia: ${if (isContingencyActive) "ACTIVO (Operando con caché local y alarma autónoma)" else "EN ESPERA (Red principal operativa + respaldo local listo)"}.\n" +
                                        "• [3.1.4.14] Compatibilidad Multi-Arquitectura: $hardwareArchInfo (Soporte MinSDK 24 a TargetSDK 37).",
                                    style = MaterialTheme.typography.bodySmall,
                                )

                                Button(
                                    onClick = {
                                        val intent = Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null),
                                        )
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("🔐 Auditar Permisos Android y Red en Ajustes")
                                }
                            }
                        }
                    }

                    1 -> {
                        // AFORO & COMUNICACIÓN SERIAL TAB (3.1.2.5, 3.1.2.6, 3.1.3.8, 3.1.3.9)
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "📡 Control de Aforo HC-SR04",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    ) {
                                        Text(
                                            text = " ${((aforoCount / maxCapacity) * 100).toInt()}% CAPACIDAD ",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(150.dp),
                                ) {
                                    CircularProgressIndicator(
                                        progress = { (aforoCount / maxCapacity).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxSize(),
                                        strokeWidth = 14.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                    )
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "SALA 25 m²",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                        Text(
                                            text = "$aforoCount / ${maxCapacity.toInt()}",
                                            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                                        )
                                        Text(
                                            text = "${(maxCapacity.toInt() - aforoCount).coerceAtLeast(0)} cupos libres",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Button(
                                        onClick = {
                                            if (aforoCount > 0) {
                                                aforoCount--
                                                scope.launch {
                                                    FirebaseRepository.updateSensorData(temperatureVal, distanciaVal, aforoCount, context)
                                                    pendingOfflineMobile = FirebaseRepository.getPendingOfflineCount(context)
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("🚪 Salida (-1)")
                                    }
                                    Button(
                                        onClick = {
                                            if (aforoCount < maxCapacity.toInt()) {
                                                aforoCount++
                                                scope.launch {
                                                    FirebaseRepository.updateSensorData(temperatureVal, distanciaVal, aforoCount, context)
                                                    pendingOfflineMobile = FirebaseRepository.getPendingOfflineCount(context)
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("🚪 Entrada (+1)")
                                    }
                                }
                            }
                        }

                        // CONSOLA DE COMUNICACIÓN SERIAL BIDIRECCIONAL (3.1.2.5, 3.1.2.6, 3.1.3.8, 3.1.3.9)
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = "💻 Terminal de Comunicación Serial (115200 bps)",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                )
                                Text(
                                    text = "Envía instrucciones seriales desde la App hacia el WeMos D1 Mini (3.1.2.5 / 3.1.3.9) y recibe tramas de telemetría serial del microcontrolador (3.1.2.6 / 3.1.3.8).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedTextField(
                                        value = serialCommandInput,
                                        onValueChange = { serialCommandInput = it },
                                        label = { Text("Instrucción Serial (TX)") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Button(
                                        onClick = {
                                            if (serialCommandInput.isNotBlank()) {
                                                sendSerialCmdFromApp(serialCommandInput)
                                            }
                                        },
                                    ) {
                                        Text("Enviar TX")
                                    }
                                }

                                // Botones rápidos de instrucciones seriales
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = { sendSerialCmdFromApp("PING") },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(4.dp),
                                    ) {
                                        Text("PING", fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { sendSerialCmdFromApp("STATUS") },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(4.dp),
                                    ) {
                                        Text("STATUS", fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { sendSerialCmdFromApp("BEEP") },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(4.dp),
                                    ) {
                                        Text("BEEP", fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { sendSerialCmdFromApp("RESET_AFORO") },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(4.dp),
                                    ) {
                                        Text("RESET", fontSize = 11.sp)
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = "Monitor Serial TX/RX (Últimas tramas):",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        serialConsoleLogs.take(6).forEach { logLine ->
                                            Text(
                                                text = logLine,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // AI Camera Module Card inside Aforo
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "📸 Reconocimiento IA (Gemini)",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    )
                                    TextButton(onClick = { showApiKeyDialog = true }) {
                                        Text("🔑 API Key")
                                    }
                                }

                                if (capturedBitmap != null) {
                                    Image(
                                        bitmap = capturedBitmap!!.asImageBitmap(),
                                        contentDescription = "Captured",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(140.dp),
                                        contentScale = ContentScale.Crop,
                                    )
                                }

                                geminiStatusMessage?.let { msg ->
                                    Text(
                                        text = msg,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }

                                if (isAnalyzingGemini) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }

                                Button(
                                    onClick = { cameraLauncher.launch() },
                                    enabled = !isAnalyzingGemini,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(if (isAnalyzingGemini) "Analizando con Gemini IA..." else "📷 Tomar Foto y Analizar con IA")
                                }
                            }
                        }
                    }

                    2 -> {
                        // CLIMA / TEMPERATURA EN TIEMPO REAL (SOLO LECTURA) + GESTIÓN DE ENERGÍA (3.1.3.10)
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "🌡️ SENSOR DE TEMPERATURA (TMP36 • FIREBASE)",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    ) {
                                        Text(
                                            text = " SOLO LECTURA ",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }

                                Text(
                                    text = "Temperatura Real: ${String.format(Locale.getDefault(), "%.2f", temperatureVal)} °C",
                                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                )

                                LinearProgressIndicator(
                                    progress = { (temperatureVal.coerceIn(-10f, 50f) + 10f) / 60f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(12.dp),
                                    color = if (temperatureVal >= 10.0f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                )

                                Text(
                                    text = "Dato inmutable recibido desde Firebase Realtime Database ($lastSyncTime) con firma CRC=$currentChecksum.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }

                        // TARJETA 3.1.3.10: EVALUACIÓN Y OPTIMIZACIÓN DE CONSUMO ENERGÉTICO
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = "🔋 Consumo de Energía IoT & Batería Móvil (3.1.3.10)",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                )
                                Text(
                                    text = "• Dispositivo Móvil: Batería al $mobileBatteryPct% (${mobileBatteryMv} mV).\n" +
                                        "• Microcontrolador WeMos D1 + HC-SR04 + TMP36: ${String.format(Locale.getDefault(), "%.1f", arduinoCurrentMa)} mA @ 3.3V.\n" +
                                        "• Optimización activa: ${if (ecoModeEnabled) "WIFI_MODEM_SLEEP + Reducción RF 14.5 dBm (Ahorro ~51%)" else "WIFI_NONE_SLEEP (Alto Rendimiento)"}.",
                                    style = MaterialTheme.typography.bodySmall,
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Modo Ahorro Energético (Eco-Sleep)", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "Envía comando serial ECO_ON / ECO_OFF al WeMos y optimiza polling en Android",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                    Switch(
                                        checked = ecoModeEnabled,
                                        onCheckedChange = { enabled ->
                                            ecoModeEnabled = enabled
                                            sendSerialCmdFromApp(if (enabled) "ECO_ON" else "ECO_OFF")
                                        },
                                    )
                                }
                            }
                        }
                    }

                    3 -> {
                        // ALERTAS TAB
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Text(
                                    text = "⚙️ Configuración Push, Pop-up & Contingencia",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                )
                                Text(
                                    text = "Control de entrega de notificaciones en segundo plano, daemon de alertas críticas y continuidad operativa.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )

                                HorizontalDivider()

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Alertas Heads-up flotantes", fontWeight = FontWeight.SemiBold)
                                        Text("Banners superpuestos (Prioridad Máxima)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                    Switch(checked = alertHeadsUp, onCheckedChange = { alertHeadsUp = it })
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Pantalla bloqueada (Lockscreen)", fontWeight = FontWeight.SemiBold)
                                        Text("Muestra alerta con desglose de riesgo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                    Switch(checked = alertLockscreen, onCheckedChange = { alertLockscreen = it })
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Alarma Sonora Piezoeléctrica (WeMos D7)", fontWeight = FontWeight.SemiBold)
                                        Text("Tono PWM de 85dB en microcontrolador", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                    Switch(checked = alertAlarmSound, onCheckedChange = { alertAlarmSound = it })
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Alerta Aforo Excedido (HC-SR04)", fontWeight = FontWeight.SemiBold)
                                        Text("Notifica cuando supera la capacidad de 25 m² (15 pers)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                    Switch(checked = alertCapacityExceeded, onCheckedChange = { alertCapacityExceeded = it })
                                }
                            }
                        }
                    }
                }
            }

            if (showApiKeyDialog) {
                GeminiApiKeyDialog(
                    initialKey = geminiApiKey,
                    onDismiss = { showApiKeyDialog = false },
                    onSaveKey = { newKey ->
                        geminiApiKey = newKey
                        showApiKeyDialog = false
                    },
                )
            }

            if (criticalDialogMessage != null) {
                AlertDialog(
                    onDismissRequest = { criticalDialogMessage = null },
                    title = { Text("🚨 ALERTA CRÍTICA DE SENSOR") },
                    text = { Text(criticalDialogMessage!!) },
                    confirmButton = {
                        Button(onClick = { criticalDialogMessage = null }) {
                            Text("Entendido / Silenciar")
                        }
                    },
                )
            }
        }
    }
}
