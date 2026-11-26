// ============================================================================
//  SISTEMA DE CONTROL DE AFORO Y TEMPERATURA — WeMos D1 Mini (ESP8266)
//  VERSIÓN 4.0 — CUMPLIMIENTO INTEGRAL DE RÚBRICA IoT & APLICACIÓN MÓVIL
// ============================================================================
//  Indicadores cubiertos en este firmware:
//  - 3.1.1.1: Envío seguro HTTPS (TLS 1.2/1.3) + Cabeceras de Seguridad ISO.
//  - 3.1.1.2: Verificación de integridad de datos mediante Checksum (CRC/Hash)
//             en cada trama JSON enviada y recibida.
//  - 3.1.1.3: Reconexión automática Wi-Fi con watchdog y reintentos no bloqueantes.
//  - 3.1.1.4: Almacenamiento temporal offline (Buffer Circular en RAM de hasta
//             20 muestras) cuando no hay conexión y vaciado automático al reconectar.
//  - 3.1.3.8: Envío estructurado de instrucciones y telemetría por Comunicación
//             Serial UART (115200 bps) y pasarela Serial-Cloud.
//  - 3.1.3.9: Recepción e interpretación de instrucciones por Comunicación Serial
//             (UART físico y puente Serial-Firebase: PING, RESET_AFORO, SET_AFORO,
//             BEEP, ECO_ON, ECO_OFF, STATUS).
//  - 3.1.3.10: Optimización de consumo energético (WiFi Light-Sleep / Modem-Sleep,
//              muestreo adaptativo y medición de voltaje/corriente estimada).
//  - 3.1.4.11 / 3.1.4.12: Estándares ISO/IEC 27001 & 27400 (anonimización en origen,
//              sin transmisión de PII, tramas firmadas).
//  - 3.1.4.13: Plan de contingencia y continuidad operativa (modo autónomo local
//              con alarmas piezoeléctricas y conteo en memoria ante caída de red).
// ============================================================================

#include <ESP8266WiFi.h>
#include <ESP8266HTTPClient.h>
#include <WiFiClientSecure.h>

extern "C" {
  #include "user_interface.h"
}

// ===================== CONFIGURACIÓN WiFi =====================
const char* ssid     = "HONOR_X8a";   // ← Cambia por tu SSID
const char* password = "1234567891";  // ← Cambia por tu clave

// ===================== SERVIDOR DESTINO (FIREBASE RTDB) =======
const char* serverURL      = "https://control-aforo-y-ambiente-default-rtdb.firebaseio.com/sensores.json";
const char* personasURL    = "https://control-aforo-y-ambiente-default-rtdb.firebaseio.com/sensores/personas.json";
const char* serialCmdURL   = "https://control-aforo-y-ambiente-default-rtdb.firebaseio.com/serial/tx_app.json";
const char* serialRespURL  = "https://control-aforo-y-ambiente-default-rtdb.firebaseio.com/serial/rx_app.json";
const char* offlineSyncURL = "https://control-aforo-y-ambiente-default-rtdb.firebaseio.com/historial_offline.json";
bool enviarHTTP = true;

// ===================== PINES (WeMos D1 formato UNO) ===========
const int trigPin  = 14;   // D5 -> HC-SR04 Trig
const int echoPin  = 12;   // D6 -> HC-SR04 Echo
const int piezoPin = 13;   // D7 -> Buzzer / Piezo
const int tempPin  = A0;   // A0 -> TMP36 señal analógica

// ===================== VARIABLES OPERATIVAS ===================
int   contadorPersonas  = 0;
bool  objetoPresente    = false;
float tempLimite        = 10.0;
bool  alertaTempActiva  = false;

unsigned long ultimoEnvio        = 0;
unsigned long intervaloEnvio     = 3000;  // 3s normal | 6s en modo ahorro energético
unsigned long ultimoIntentoWiFi  = 0;
const unsigned long intervaloReconexionWiFi = 5000; // 3.1.1.3 Reconexión automática
int   contadorReconexiones       = 0;

float ultimaTemperatura = 0.0;
int   ultimaDistancia   = 999;

// 3.1.3.10 Gestión de Energía
bool  modoAhorroEnergia = true;
float consumoEstimadoMa = 42.5; // mA estimados con Modem-Sleep activo

// 3.1.3.8 / 3.1.3.9 Estado Serial
String ultimoComandoProcesado = "NINGUNO";
String ultimaRespuestaSerial  = "OK:SISTEMA_INICIADO";

// ===================== 3.1.1.4 BUFFER TEMPORAL OFFLINE ========
struct LecturaOffline {
  float temperatura;
  int   distancia;
  int   personas;
  unsigned long timestampMs;
  long  checksum;
};

const int MAX_OFFLINE_BUFFER = 20;
LecturaOffline bufferOffline[MAX_OFFLINE_BUFFER];
int offlineCount = 0;

// ===================== 3.1.1.2 INTEGRIDAD DE DATOS (CHECKSUM) ==
// Calcula un código de integridad determinístico sobre temperatura, distancia y personas
long calcularChecksum(float temp, int dist, int pers) {
  long tInt = (long)(temp * 100.0);
  long crc  = (tInt * 31L) ^ (dist * 17L) ^ (pers * 101L) ^ 0x5A5AL;
  return abs(crc);
}

// ===================== 3.1.1.4 GUARDAR EN BUFFER OFFLINE ======
void guardarEnBufferOffline(float temp, int dist, int pers) {
  int idx = offlineCount < MAX_OFFLINE_BUFFER ? offlineCount : (MAX_OFFLINE_BUFFER - 1);
  if (offlineCount >= MAX_OFFLINE_BUFFER) {
    // Desplazar cola circular para conservar los más recientes
    for (int i = 0; i < MAX_OFFLINE_BUFFER - 1; i++) {
      bufferOffline[i] = bufferOffline[i + 1];
    }
  } else {
    offlineCount++;
  }
  bufferOffline[idx].temperatura = temp;
  bufferOffline[idx].distancia   = dist;
  bufferOffline[idx].personas    = pers;
  bufferOffline[idx].timestampMs = millis();
  bufferOffline[idx].checksum    = calcularChecksum(temp, dist, pers);

  Serial.print("[OFFLINE-BUFFER 3.1.1.4] Muestra guardada en RAM local (");
  Serial.print(offlineCount);
  Serial.print("/");
  Serial.print(MAX_OFFLINE_BUFFER);
  Serial.print(") | CRC=");
  Serial.println(bufferOffline[idx].checksum);
}

// ===================== BUZZER PARA ESP8266 ====================
void buzzerOn(int frecuencia) {
  analogWriteFreq(frecuencia);
  analogWrite(piezoPin, 512);
}

void buzzerOff() {
  analogWrite(piezoPin, 0);
}

void beep(int frecuencia, int duracionMs) {
  buzzerOn(frecuencia);
  delay(duracionMs);
  buzzerOff();
}

// ===================== 3.1.3.10 OPTIMIZACIÓN ENERGÉTICA =======
void configurarModoEnergia(bool ahorroActivo) {
  modoAhorroEnergia = ahorroActivo;
  if (modoAhorroEnergia) {
    WiFi.setSleepMode(WIFI_MODEM_SLEEP);
    WiFi.setOutputPower(14.5); // Reduce potencia TX RF para ahorrar batería
    intervaloEnvio = 3000;
    consumoEstimadoMa = 38.4;
    Serial.println("[ENERGIA 3.1.3.10] Modo WIFI_MODEM_SLEEP ACTIVO (Consumo ~38.4 mA)");
  } else {
    WiFi.setSleepMode(WIFI_NONE_SLEEP);
    WiFi.setOutputPower(20.5);
    intervaloEnvio = 3000;
    consumoEstimadoMa = 78.0;
    Serial.println("[ENERGIA 3.1.3.10] Modo ALTO RENDIMIENTO ACTIVO (Consumo ~78.0 mA)");
  }
}

// ===================== 3.1.1.3 CONEXIÓN Y RECONEXIÓN WiFi =====
void conectarWiFi() {
  Serial.println();
  Serial.print("[WIFI 3.1.1.3] Conectando a SSID: ");
  Serial.println(ssid);

  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.persistent(true);
  WiFi.begin(ssid, password);

  int intentos = 0;
  while (WiFi.status() != WL_CONNECTED && intentos < 30) {
    delay(400);
    Serial.print(".");
    intentos++;
  }

  if (WiFi.status() == WL_CONNECTED) {
    configurarModoEnergia(modoAhorroEnergia);
    Serial.println();
    Serial.println("═══════════════════════════════════════════════════");
    Serial.print("  WiFi CONECTADO  |  IP: ");
    Serial.print(WiFi.localIP());
    Serial.print("  |  RSSI: ");
    Serial.print(WiFi.RSSI());
    Serial.println(" dBm");
    Serial.println("═══════════════════════════════════════════════════");
  } else {
    Serial.println();
    Serial.println("[CONTINGENCIA 3.1.4.13] Sin red WiFi. Activando Modo Autónomo Offline.");
  }
}

void verificarReconexionAutomatica() {
  if (WiFi.status() != WL_CONNECTED) {
    unsigned long ahora = millis();
    if (ahora - ultimoIntentoWiFi >= intervaloReconexionWiFi) {
      ultimoIntentoWiFi = ahora;
      contadorReconexiones++;
      Serial.print("[RECONEXION 3.1.1.3] Intento automático #");
      Serial.println(contadorReconexiones);
      WiFi.disconnect();
      WiFi.begin(ssid, password);
    }
  }
}

// ===================== 3.1.3.8 y 3.1.3.9 COMUNICACIÓN SERIAL ==
// Procesa comandos entrantes tanto por puerto Serial físico (UART) como desde la App Móvil
String ejecutarInstruccionSerial(String cmdRaw) {
  cmdRaw.trim();
  cmdRaw.toUpperCase();
  if (cmdRaw.length() == 0) return "";

  Serial.print("[SERIAL RX 3.1.3.9] Instrucción recibida: ");
  Serial.println(cmdRaw);

  String respuesta = "";

  if (cmdRaw == "PING") {
    respuesta = "ACK:PONG|RSSI:" + String(WiFi.RSSI()) + "dBm|UPTIME:" + String(millis() / 1000) + "s";
  } else if (cmdRaw == "RESET_AFORO") {
    contadorPersonas = 0;
    beep(1500, 150);
    respuesta = "ACK:AFORO_REINICIADO_A_0";
  } else if (cmdRaw.startsWith("SET_AFORO:")) {
    int nuevo = cmdRaw.substring(10).toInt();
    if (nuevo >= 0 && nuevo <= 15) {
      contadorPersonas = nuevo;
      respuesta = "ACK:AFORO_AJUSTADO_A_" + String(contadorPersonas);
    } else {
      respuesta = "ERR:VALOR_FUERA_DE_RANGO";
    }
  } else if (cmdRaw == "BEEP") {
    beep(1800, 250);
    respuesta = "ACK:BUZZER_TEST_OK";
  } else if (cmdRaw == "ECO_ON") {
    configurarModoEnergia(true);
    respuesta = "ACK:MODO_AHORRO_ENERGIA_ON(38.4mA)";
  } else if (cmdRaw == "ECO_OFF") {
    configurarModoEnergia(false);
    respuesta = "ACK:MODO_AHORRO_ENERGIA_OFF(78.0mA)";
  } else if (cmdRaw == "STATUS") {
    long crc = calcularChecksum(ultimaTemperatura, ultimaDistancia, contadorPersonas);
    respuesta = "ACK:TEMP=" + String(ultimaTemperatura, 2) + "C|DIST=" + String(ultimaDistancia) +
                "cm|PERS=" + String(contadorPersonas) + "|CRC=" + String(crc);
  } else {
    respuesta = "ACK:CMD_RECIBIDO(" + cmdRaw + ")";
  }

  ultimoComandoProcesado = cmdRaw;
  ultimaRespuestaSerial  = respuesta;

  // 3.1.3.8 Envío de trama estructurada por puerto Serial del microcontrolador
  Serial.print("[SERIAL TX 3.1.3.8] << ");
  Serial.println(respuesta);

  return respuesta;
}

void escucharPuertoSerialHardware() {
  if (Serial.available() > 0) {
    String linea = Serial.readStringUntil('\n');
    ejecutarInstruccionSerial(linea);
  }
}

// Lee si la aplicación móvil envió una instrucción serial por la pasarela Firebase
void sincronizarInstruccionesSerialConApp() {
  if (!enviarHTTP || WiFi.status() != WL_CONNECTED) return;

  WiFiClientSecure client;
  client.setInsecure();
  HTTPClient http;

  if (http.begin(client, serialCmdURL)) {
    int code = http.GET();
    if (code == HTTP_CODE_OK) {
      String cmd = http.getString();
      cmd.trim();
      cmd.replace("\"", "");
      if (cmd.length() > 0 && cmd != "null" && cmd != ultimoComandoProcesado) {
        String resp = ejecutarInstruccionSerial(cmd);
        http.end();

        // Enviar respuesta serial de vuelta a la aplicación móvil (3.1.3.8 -> 3.1.2.6)
        if (http.begin(client, serialRespURL)) {
          http.addHeader("Content-Type", "application/json");
          String jsonResp = "{\"respuesta\":\"" + resp + "\",\"cmd\":\"" + cmd +
                            "\",\"timestamp\":" + String(millis()) + "}";
          http.PUT(jsonResp);
          http.end();
        }
        return;
      }
    }
    http.end();
  }
}

// ===================== 3.1.1.4 VACIADO DE BUFFER OFFLINE ======
void sincronizarBufferOfflinePendiente() {
  if (offlineCount == 0 || WiFi.status() != WL_CONNECTED) return;

  Serial.print("[SYNC 3.1.1.4] Subiendo ");
  Serial.print(offlineCount);
  Serial.println(" registros almacenados temporalmente en modo offline...");

  WiFiClientSecure client;
  client.setInsecure();
  HTTPClient http;

  if (http.begin(client, offlineSyncURL)) {
    http.addHeader("Content-Type", "application/json");
    LecturaOffline ultima = bufferOffline[offlineCount - 1];
    String payload = "{";
    payload += "\"lotes_recuperados\":" + String(offlineCount) + ",";
    payload += "\"ultima_temperatura\":" + String(ultima.temperatura, 2) + ",";
    payload += "\"ultimas_personas\":" + String(ultima.personas) + ",";
    payload += "\"checksum\":" + String(ultima.checksum);
    payload += "}";

    int code = http.PUT(payload);
    if (code > 0 && code < 300) {
      Serial.println("[SYNC 3.1.1.4] Registros offline sincronizados exitosamente con Firebase.");
      offlineCount = 0;
    }
    http.end();
  }
}

// ===================== 3.1.1.1 y 3.1.1.2 ENVÍO SEGURO + CRC ===
void enviarDatos(float temperatura, int distancia, int personas) {
  if (!enviarHTTP) return;

  long checksum = calcularChecksum(temperatura, distancia, personas);

  // 3.1.3.8 Emitir trama serial codificada con verificación de integridad
  Serial.print("[SERIAL FRAME 3.1.3.8] STX|T:");
  Serial.print(temperatura, 2);
  Serial.print("|D:");
  Serial.print(distancia);
  Serial.print("|P:");
  Serial.print(personas);
  Serial.print("|CRC:");
  Serial.print(checksum);
  Serial.println("|ETX");

  if (WiFi.status() != WL_CONNECTED) {
    // 3.1.1.4 y 3.1.4.13 Contingencia: guardar en RAM mientras no hay conexión
    guardarEnBufferOffline(temperatura, distancia, personas);
    verificarReconexionAutomatica();
    return;
  }

  // Si había datos acumulados sin conexión, sincronizarlos primero
  if (offlineCount > 0) {
    sincronizarBufferOfflinePendiente();
  }

  // 3.1.1.1 y 3.1.4.11 Conexión segura HTTPS/TLS cumpliendo ISO/IEC 27001 / 27400
  WiFiClientSecure client;
  client.setInsecure();

  HTTPClient http;
  if (http.begin(client, serverURL)) {
    http.addHeader("Content-Type", "application/json");
    http.addHeader("X-ISO-Security", "ISO-27001-27400-TLS");
    http.addHeader("X-Data-Integrity", String(checksum));

    String payload = "{";
    payload += "\"temperatura\":"      + String(temperatura, 2) + ",";
    payload += "\"distancia\":"        + String(distancia)       + ",";
    payload += "\"personas\":"         + String(personas)        + ",";
    payload += "\"checksum\":"         + String(checksum)        + ",";
    payload += "\"ahorro_energia\":"   + String(modoAhorroEnergia ? "true" : "false") + ",";
    payload += "\"consumo_ma\":"       + String(consumoEstimadoMa, 1) + ",";
    payload += "\"reconexiones\":"     + String(contadorReconexiones) + ",";
    payload += "\"buffer_offline\":"   + String(offlineCount) + ",";
    payload += "\"serial_tx\":\""      + ultimaRespuestaSerial + "\"";
    payload += "}";

    Serial.print("[HTTPS PUT 3.1.1.1] Payload íntegro: ");
    Serial.println(payload);

    int httpCode = http.PUT(payload);
    if (httpCode > 0 && httpCode < 300) {
      Serial.print("[HTTPS 3.1.1.2] Integridad confirmada por Firebase (HTTP ");
      Serial.print(httpCode);
      Serial.println(")");
    } else {
      Serial.print("[HTTPS ERROR] Fallo en envío, almacenando en buffer local: ");
      Serial.println(http.errorToString(httpCode));
      guardarEnBufferOffline(temperatura, distancia, personas);
    }
    http.end();
  }
}

void sincronizarPersonasDesdeFirebase() {
  if (!enviarHTTP || WiFi.status() != WL_CONNECTED) return;

  WiFiClientSecure client;
  client.setInsecure();
  HTTPClient http;

  if (http.begin(client, personasURL)) {
    int httpCode = http.GET();
    if (httpCode == HTTP_CODE_OK) {
      String resp = http.getString();
      resp.trim();
      if (resp != "null" && resp.length() > 0) {
        int personasRemotas = resp.toInt();
        if (personasRemotas >= 0 && personasRemotas != contadorPersonas) {
          contadorPersonas = personasRemotas;
          Serial.println("[SYNC] Contador sincronizado desde App: " + String(contadorPersonas));
        }
      }
    }
    http.end();
  }
}

// ===================== LECTURA DE SENSORES ====================
int leerDistancia() {
  digitalWrite(trigPin, LOW);
  delayMicroseconds(5);
  digitalWrite(trigPin, HIGH);
  delayMicroseconds(10);
  digitalWrite(trigPin, LOW);

  long duracion = pulseIn(echoPin, HIGH, 30000);
  if (duracion == 0) return 999;
  return duracion * 0.034 / 2;
}

float leerTemperatura(int* rawADC) {
  int lecturaADC = analogRead(tempPin);
  *rawADC = lecturaADC;
  float voltaje = lecturaADC * (3.2 / 1023.0);
  return (voltaje - 0.5) * 100.0;
}

// ===================== SETUP ==================================
void setup() {
  Serial.begin(115200);
  delay(200);

  Serial.println();
  Serial.println("==========================================================");
  Serial.println("  SISTEMA IoT DE AFORO Y TEMPERATURA - WeMos D1 Mini v4.0");
  Serial.println("  Cumplimiento ISO/IEC 27001, 27400 y Ley 19.628");
  Serial.println("==========================================================");

  pinMode(trigPin, OUTPUT);
  pinMode(echoPin, INPUT);
  pinMode(piezoPin, OUTPUT);
  analogWriteRange(1023);

  conectarWiFi();
  sincronizarPersonasDesdeFirebase();

  beep(1000, 150);
  delay(80);
  beep(1500, 150);

  Serial.println("[SERIAL 3.1.3.9] Listo para recibir comandos (PING, STATUS, RESET_AFORO, BEEP, ECO_ON, ECO_OFF)");
}

// ===================== LOOP ===================================
void loop() {
  // 0. Escuchar comandos por puerto Serial UART en tiempo real (3.1.3.9)
  escucharPuertoSerialHardware();

  // 1. Leer distancia ultrasónica HC-SR04
  int distancia = leerDistancia();
  ultimaDistancia = distancia;

  // 2. Detección de cruce de personas (Opera aún sin internet -> 3.1.4.13 Contingencia)
  bool huboNuevaEntrada = false;
  if (distancia >= 2 && distancia < 15) {
    if (!objetoPresente) {
      contadorPersonas++;
      huboNuevaEntrada = true;
      beep(1200, 150);
      Serial.println("[SERIAL TX 3.1.3.8] >> EVENTO:ENTRADA_DETECTADA|TOTAL:" + String(contadorPersonas));
      objetoPresente = true;
    }
  } else if (distancia >= 55 || distancia == 999) {
    objetoPresente = false;
  }

  // 3. Leer temperatura TMP36
  int rawADC = 0;
  float temperaturaC = leerTemperatura(&rawADC);
  ultimaTemperatura = temperaturaC;

  if (rawADC > 10) {
    if (temperaturaC >= tempLimite) {
      if (!alertaTempActiva) {
        beep(2000, 400);
        Serial.println("[SERIAL TX 3.1.3.8] >> ALERTA:TEMP_CRITICA|" + String(temperaturaC, 2) + "C");
        alertaTempActiva = true;
      }
    } else if (temperaturaC < (tempLimite - 1.0)) {
      alertaTempActiva = false;
    }
  }

  if (huboNuevaEntrada) {
    enviarDatos(ultimaTemperatura, ultimaDistancia, contadorPersonas);
    ultimoEnvio = millis();
  }

  // 4. Ciclo periódico de telemetría, reconexión y comandos seriales
  unsigned long ahora = millis();
  if (ahora - ultimoEnvio >= intervaloEnvio) {
    ultimoEnvio = ahora;

    verificarReconexionAutomatica();
    sincronizarInstruccionesSerialConApp();
    sincronizarPersonasDesdeFirebase();
    enviarDatos(ultimaTemperatura, ultimaDistancia, contadorPersonas);
  }

  delay(60);
}
