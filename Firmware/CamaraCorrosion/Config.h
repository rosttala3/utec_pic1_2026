// Pines y constantes de la cámara. Lo que se ajusta en banco (caudal, sal, volumen, conductividad)
// NO está acá: se calibra por comando y queda en NVS (ver Ajustes.h y README.md).
#pragma once
#include <Arduino.h>

// ======================= PINES (rótulos de la placa DevKit V1; Dn = GPIO n) =======================
const int PIN_TEMP         = 4;    // D4   DS18B20
const int PIN_COND_B = 34;   // D34  ADC de los electrodos (solo entrada)
const int PIN_COND_A       = 32;   // D32  electrodo A: salida de excitación (ADR 0011)
const int PIN_COND_B_RESIST = 33; // D33  electrodo B resist: salida de excitación, entra a la resistencia en serie con el 2.º electrodo
const int PIN_REED_LLENO   = 15;   // D15
const int PIN_REED_VACIO   = 2;    // D2
const int PIN_LED_VERDE    = 12;   // D12
const int PIN_LED_ROJO     = 14;   // D14
const int PIN_BUZZER       = 13;   // D13
const int PIN_RELE_VALVULA = 18;   // D18  relé 1: electroválvula 12 V (ADR 0007, antes servo de agua)
const int PIN_RELE_MEZCLA  = 21;   // D21  relé 2: motor mezclador
const int PIN_RELE_BOMBA   = 22;   // D22  relé 3: bomba de aspersión
// Sinfín: 28BYJ-48 con ULN2003 (ADR 0009). Los 4 pines van a IN1..IN4 del módulo; sin ENABLE ni DIR.
const int PIN_MOTOR_IN1    = 19;   // D19  PROVISORIO: confirmar contra el diagrama eléctrico
const int PIN_MOTOR_IN2    = 23;   // D23  PROVISORIO
const int PIN_MOTOR_IN3    = 25;   // D25  PROVISORIO
const int PIN_MOTOR_IN4    = 26;   // D26  PROVISORIO

// ======================= POLARIDADES =======================
const int  RELE_ON         = HIGH;  // Cambiar a LOW si el módulo relé es activo en bajo
const int  RELE_OFF        = LOW;
const int  REED_ACTIVO     = LOW;   // Con INPUT_PULLUP, el contacto cerrado da LOW

// ======================= VALORES POR DEFECTO DE LA CALIBRACIÓN =======================
// Son solo el punto de partida; los reales se miden y se guardan con calibrar(...).
const float CAP_ML_DEF       = 1200.0f;  // volumen entre vacío y lleno
const float AGUA_ML_S_DEF    = 20.0f;    // caudal de la electroválvula
const float SAL_PASOS_G_DEF  = 100.0f;   // pasos del motor por gramo de sal
const int   COND_MAX_PUNTOS  = 8;        // puntos de la curva de conductividad

// ======================= LÍMITES DEL ENSAYO =======================
const float    CONC_MIN        = 0.1f;     // % NaCl (la solubilidad ronda el 26 %)
const float    CONC_MAX        = 25.0f;
const uint32_t DURACION_MIN_S  = 60;
const uint32_t DURACION_MAX_S  = 7UL * 86400UL;   // tope de seguridad: 7 días
const uint32_t MUESTREO_MIN_S  = 5;
const uint32_t MUESTREO_MAX_S  = 3600;
const float    DOSIS_MAX_G     = 500.0f;

// ======================= AJUSTE DE LA CONCENTRACIÓN =======================
const float         DOSIS_INICIAL     = 0.9f;        // se dosifica el 90 % calculado y se afina midiendo
const unsigned long ASENTAR_MS        = 420000UL;    // mezcla antes de leer la conductividad
const float         TOLERANCIA_PCT    = 0.15f;       // ± % de NaCl respecto del objetivo
const int           MAX_CORRECCIONES  = 6;
const float         CORRECCION_MIN_G  = 0.5f;        // por debajo no vale la pena mover el sinfín
const float         COEF_TEMP         = 0.02f;       // la conductividad sube ~2 % por °C (ref. 25 °C)

// ======================= TIEMPOS =======================
const unsigned long DEBOUNCE_MS       = 30;
const unsigned long INTERVALO_COND_MS = 200;
const unsigned long INTERVALO_TEMP_MS = 1000;
const unsigned long MEZCLA_MANUAL_MS  = 3000;     // mezcla tras una dosis de sal manual
const unsigned long DIRECTO_MAX_MS    = 60000;     // operaciones manuales: un relé encendido se apaga solo a los 60 s
const uint32_t      SINFIN_MAX_PASOS  = 20000;     // tope por orden de actuar(sinfin,...)
const unsigned long US_POR_PASO       = 2000;     // medio paso cada 2 ms = 500 pasos/s (28BYJ-48: ~4096 medios pasos por vuelta)
const unsigned long INTERVALO_SYNC_MS = 15;       // una línea de historial cada 15 ms (BLE no aguanta más)

// ======================= ALMACENAMIENTO =======================
const int BUF_MUESTRAS = 10;   // se escribe a flash cada 10 muestras (ADR 0006)
const int ENSAYOS_GUARDADOS = 2;

// ======================= BLE (Nordic UART Service, ADR 0006) =======================
#define NOMBRE_BLE   "PIC1-ESP32"
#define UUID_NUS     "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define UUID_NUS_RX  "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"   // la app escribe
#define UUID_NUS_TX  "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"   // el firmware notifica
