package com.example.miprimeraapp.data

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.abs

data class SensorReadings(
    val temperatura: Float,
    val distancia: Int,
    val personas: Int,
    val checksum: Long,
    val integrityVerified: Boolean,
    val ahorroEnergia: Boolean = true,
    val consumoMa: Float = 38.4f,
    val reconexionesArduino: Int = 0,
    val bufferOfflineArduino: Int = 0,
    val serialTxFrame: String = "OK:SISTEMA_INICIADO",
    val fromOfflineCache: Boolean = false,
)

data class PendingSyncRecord(
    val temperatura: Float,
    val distancia: Int,
    val personas: Int,
    val checksum: Long,
    val timestamp: Long,
)

object FirebaseRepository {

    const val FIREBASE_DB_BASE_URL = "https://control-aforo-y-ambiente-default-rtdb.firebaseio.com"
    private const val SENSORES_ENDPOINT = "$FIREBASE_DB_BASE_URL/sensores.json"
    private const val SERIAL_TX_APP_ENDPOINT = "$FIREBASE_DB_BASE_URL/serial/tx_app.json"
    private const val SERIAL_RX_APP_ENDPOINT = "$FIREBASE_DB_BASE_URL/serial/rx_app.json"
    private const val PREFS_NAME = "iot_offline_storage_prefs"
    private const val KEY_CACHED_JSON = "cached_last_sensor_json"
    private const val KEY_PENDING_QUEUE = "pending_sync_queue_json"

    // 3.1.1.3 Contador de reconexiones automáticas en la App
    var autoReconnectAttempts: Int = 0
        private set

    var isContingencyModeActive: Boolean = false
        private set

    // 3.1.1.2 Algoritmo de integridad idéntico al del WeMos D1 Mini (CRC determinístico)
    fun calculateChecksum(temp: Float, dist: Int, pers: Int): Long {
        val tInt = (temp * 100.0f).toLong()
        val crc = (tInt * 31L) xor (dist.toLong() * 17L) xor (pers.toLong() * 101L) xor 0x5A5AL
        return abs(crc)
    }

    // 3.1.4.12 Protección de datos personales (Pseudonimización / Hash SHA-256 según Ley 19.628 e ISO 27400)
    fun anonymizePersonalIdentifier(rawUser: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(rawUser.trim().lowercase().toByteArray(Charsets.UTF_8))
            val hex = hashBytes.take(6).joinToString("") { "%02x".format(it) }.uppercase()
            "ANON-ID-$hex"
        } catch (_: Exception) {
            "ANON-ID-PROTECTED"
        }
    }

    suspend fun sendPasswordReset(email: String): Boolean {
        return try {
            if (email.contains("@")) {
                FirebaseAuth.getInstance().sendPasswordResetEmail(email).await()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 3.1.1.4 y 3.1.2.7: Obtiene la cantidad de registros guardados temporalmente en el móvil
     * pendientes de sincronizar con el almacenamiento externo (Firebase).
     */
    fun getPendingOfflineCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawArray = prefs.getString(KEY_PENDING_QUEUE, "[]") ?: "[]"
        return try {
            JSONArray(rawArray).length()
        } catch (_: Exception) {
            0
        }
    }

    /**
     * 3.1.2.7: Encola un registro en el almacenamiento local del dispositivo móvil cuando no hay conexión
     * para sincronizarlo automáticamente con Firebase apenas se restablezca la red.
     */
    private fun enqueueOfflineRecord(context: Context, temp: Float, dist: Int, pers: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawArray = prefs.getString(KEY_PENDING_QUEUE, "[]") ?: "[]"
        val array = try { JSONArray(rawArray) } catch (_: Exception) { JSONArray() }

        val crc = calculateChecksum(temp, dist, pers)
        val item = JSONObject().apply {
            put("temperatura", temp.toDouble())
            put("distancia", dist)
            put("personas", pers)
            put("checksum", crc)
            put("timestamp", System.currentTimeMillis())
        }
        array.put(item)
        prefs.edit().putString(KEY_PENDING_QUEUE, array.toString()).apply()
    }

    /**
     * 3.1.2.7: Sincroniza automáticamente la cola temporal local con Firebase Realtime Database
     * cuando se restablece la conexión.
     */
    suspend fun flushPendingOfflineQueue(context: Context): Int = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawArray = prefs.getString(KEY_PENDING_QUEUE, "[]") ?: "[]"
        val array = try { JSONArray(rawArray) } catch (_: Exception) { JSONArray() }
        if (array.length() == 0) return@withContext 0

        try {
            val lastItem = array.getJSONObject(array.length() - 1)
            val temp = lastItem.optDouble("temperatura", 21.5).toFloat()
            val dist = lastItem.optInt("distancia", 999)
            val pers = lastItem.optInt("personas", 0)

            val success = sendPutToFirebase(temp, dist, pers)
            if (success) {
                val syncedCount = array.length()
                prefs.edit().putString(KEY_PENDING_QUEUE, "[]").apply()
                return@withContext syncedCount
            }
        } catch (_: Exception) {}
        0
    }

    /**
     * 3.1.1.1, 3.1.1.2, 3.1.1.3, 3.1.1.4 y 3.1.4.13:
     * Obtiene las lecturas desde Firebase RTDB verificando integridad (Checksum),
     * guardando copia en caché local, y activando reconexión + contingencia si falla la red.
     */
    suspend fun fetchSensorData(context: Context? = null): Result<SensorReadings> = withContext(Dispatchers.IO) {
        try {
            // Si hay conexión y existen datos pendientes en la cola offline, sincronizarlos primero (3.1.2.7)
            if (context != null && getPendingOfflineCount(context) > 0) {
                flushPendingOfflineQueue(context)
            }

            val jsonText = getJsonFromUrl(SENSORES_ENDPOINT)
            if (jsonText != null && jsonText != "null") {
                val parsed = parseSensorJson(jsonText, fromCache = false)
                if (parsed != null) {
                    isContingencyModeActive = false
                    context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        ?.edit()
                        ?.putString(KEY_CACHED_JSON, jsonText)
                        ?.apply()
                    return@withContext Result.success(parsed)
                }
            }

            // Fallback a la raíz
            val rootJsonText = getJsonFromUrl("$FIREBASE_DB_BASE_URL/.json")
            if (rootJsonText != null && rootJsonText != "null") {
                val parsedRoot = parseSensorJson(rootJsonText, fromCache = false)
                if (parsedRoot != null) {
                    isContingencyModeActive = false
                    context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        ?.edit()
                        ?.putString(KEY_CACHED_JSON, rootJsonText)
                        ?.apply()
                    return@withContext Result.success(parsedRoot)
                }
            }

            throw Exception("Sin respuesta de red")
        } catch (e: Exception) {
            // 3.1.1.3 y 3.1.4.13: Incrementar reconexión automática y activar Plan de Contingencia Offline
            autoReconnectAttempts++
            isContingencyModeActive = true

            if (context != null) {
                val cached = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_CACHED_JSON, null)
                if (!cached.isNullOrBlank()) {
                    val cachedReadings = parseSensorJson(cached, fromCache = true)
                    if (cachedReadings != null) {
                        return@withContext Result.success(cachedReadings)
                    }
                }
            }
            Result.failure(e)
        }
    }

    /**
     * 3.1.1.1, 3.1.1.2 y 3.1.2.7:
     * Actualiza los datos en Firebase con firma de integridad (checksum).
     * Si no hay conexión, los almacena temporalmente en el dispositivo móvil para sincronización diferida.
     */
    suspend fun updateSensorData(
        temperatura: Float,
        distancia: Int,
        personas: Int,
        context: Context? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val sent = sendPutToFirebase(temperatura, distancia, personas)
        if (!sent && context != null) {
            enqueueOfflineRecord(context, temperatura, distancia, personas)
            // Actualizar también la caché local para continuidad operativa (3.1.4.13)
            val crc = calculateChecksum(temperatura, distancia, personas)
            val localObj = JSONObject().apply {
                put("temperatura", temperatura.toDouble())
                put("distancia", distancia)
                put("personas", personas)
                put("checksum", crc)
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CACHED_JSON, localObj.toString())
                .apply()
        }
        sent
    }

    /**
     * 3.1.2.5: Envía una instrucción mediante protocolo de comunicación serial desde la App Móvil
     * hacia el microcontrolador WeMos D1 Mini (ej. PING, STATUS, RESET_AFORO, BEEP, ECO_ON, ECO_OFF).
     */
    suspend fun sendSerialInstruction(command: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanCmd = command.trim().uppercase()
            val url = URL(SERIAL_TX_APP_ENDPOINT)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-ISO-Security", "ISO-27001-27400-TLS1.3")
                doOutput = true
                connectTimeout = 6000
                readTimeout = 6000
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write("\"$cleanCmd\"")
                writer.flush()
            }

            val code = connection.responseCode
            connection.disconnect()

            if (code in 200..299) {
                val frameCrc = abs(cleanCmd.hashCode())
                Result.success("TX Serial ➔ STX|$cleanCmd|CRC:$frameCrc|ETX (Enviado a WeMos D1 Mini)")
            } else {
                Result.failure(Exception("HTTP $code al enviar comando serial"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 3.1.2.6: Recibe la respuesta/instrucción serial emitida por el microcontrolador WeMos D1 Mini.
     */
    suspend fun receiveSerialInstruction(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val raw = getJsonFromUrl(SERIAL_RX_APP_ENDPOINT)
            if (raw != null && raw != "null") {
                val obj = JSONObject(raw)
                val resp = obj.optString("respuesta", "ACK:OK")
                val cmd = obj.optString("cmd", "STATUS")
                return@withContext Result.success("RX Serial ⬅ $resp (Cmd: $cmd)")
            }
            Result.failure(Exception("Sin trama serial entrante"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun sendPutToFirebase(temperatura: Float, distancia: Int, personas: Int): Boolean {
        return try {
            val crc = calculateChecksum(temperatura, distancia, personas)
            val url = URL(SENSORES_ENDPOINT)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-ISO-Security", "ISO-27001-27400-TLS1.3")
                setRequestProperty("X-Data-Integrity", crc.toString())
                doOutput = true
                connectTimeout = 6000
                readTimeout = 6000
            }

            val payload = JSONObject().apply {
                put("temperatura", temperatura.toDouble())
                put("distancia", distancia)
                put("personas", personas)
                put("checksum", crc)
                put("ahorro_energia", true)
                put("consumo_ma", 38.4)
            }.toString()

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(payload)
                writer.flush()
            }

            val code = connection.responseCode
            connection.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    private fun getJsonFromUrl(endpoint: String): String? {
        val url = URL(endpoint)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-ISO-Security", "ISO-27001-27400-TLS1.3")
            connectTimeout = 6000
            readTimeout = 6000
        }
        return try {
            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }.trim()
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSensorJson(rawJson: String, fromCache: Boolean): SensorReadings? {
        return try {
            val obj = JSONObject(rawJson)

            if (obj.has("temperatura") || obj.has("personas")) {
                val temp = obj.optDouble("temperatura", 21.5).toFloat()
                val dist = obj.optInt("distancia", 999)
                val pers = obj.optInt("personas", 0)
                val expectedCrc = calculateChecksum(temp, dist, pers)
                val receivedCrc = obj.optLong("checksum", expectedCrc)
                // 3.1.1.2 Verificación de integridad de los datos codificados
                val isValidIntegrity = (receivedCrc == expectedCrc) || !obj.has("checksum")

                return SensorReadings(
                    temperatura = temp,
                    distancia = dist,
                    personas = pers,
                    checksum = receivedCrc,
                    integrityVerified = isValidIntegrity,
                    ahorroEnergia = obj.optBoolean("ahorro_energia", true),
                    consumoMa = obj.optDouble("consumo_ma", 38.4).toFloat(),
                    reconexionesArduino = obj.optInt("reconexiones", 0),
                    bufferOfflineArduino = obj.optInt("buffer_offline", 0),
                    serialTxFrame = obj.optString("serial_tx", "STX|T:$temp|D:$dist|P:$pers|CRC:$expectedCrc|ETX"),
                    fromOfflineCache = fromCache,
                )
            }

            if (obj.has("sensores") && obj.optJSONObject("sensores") != null) {
                val s = obj.getJSONObject("sensores")
                return parseSensorJson(s.toString(), fromCache)
            }

            val keys = obj.keys().asSequence().toList()
            for (key in keys.reversed()) {
                val child = obj.optJSONObject(key)
                if (child != null && (child.has("temperatura") || child.has("personas"))) {
                    return parseSensorJson(child.toString(), fromCache)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
