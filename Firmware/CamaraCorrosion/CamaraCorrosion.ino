// Cámara de corrosión salina — firmware (ESP32).
//
// La app pide un ensayo con una sola orden, `ensayo,<conc %>,<duracion s>,<muestreo s>`, y la cámara
// se encarga de todo: llena el tanque, dosifica la sal, la ajusta midiendo la conductividad hasta la
// concentración pedida y corre la duración indicada, guardando muestras aunque no haya teléfono.
//
// Protocolo de líneas (UART 115200 y BLE Nordic UART): ver docs/protocolo-simulador.md y ADR 0006.
// Calibración en banco (caudal, sal, volumen, conductividad): ver README.md de esta carpeta.
//
// Módulos (cada .h se incluye una sola vez, desde acá):
//   Config.h      pines y constantes          Ajustes.h    calibración y contadores en NVS
//   Sensores.h    reeds, temperatura, cond.   Actuadores.h válvula, mezcla, bomba, sinfín
//   Enlace.h      UART + BLE (NUS)            Historial.h  muestras en LittleFS y sincronización
//   Ensayo.h      máquina de estados          Comandos.h   parser de líneas

#include "Config.h"
#include "Ajustes.h"
#include "Sensores.h"
#include "Actuadores.h"
#include "Enlace.h"
#include "Historial.h"
#include "Ensayo.h"
#include "Comandos.h"

void setup() {
  Serial.begin(115200);

  iniciarActuadores();                 // todo apagado antes que nada

  pinMode(PIN_REED_LLENO, INPUT_PULLUP);
  pinMode(PIN_REED_VACIO, INPUT_PULLUP);
  reedLleno.lecturaPrev = reedLleno.activa = (digitalRead(PIN_REED_LLENO) == REED_ACTIVO);
  reedVacio.lecturaPrev = reedVacio.activa = (digitalRead(PIN_REED_VACIO) == REED_ACTIVO);

  analogReadResolution(12);
  analogSetPinAttenuation(PIN_COND_B, ADC_11db);

  sensorTemp.begin();
  sensorTemp.setWaitForConversion(false);   // lectura asíncrona: no bloquea el loop
  sensorTemp.requestTemperatures();
  tTemp = millis();

  ajustesCargar();
  historialIniciar();
  tankMl = reedLleno.activa ? aj.capMl : 0;  // sin volumen medido, se parte de un extremo conocido
  tVol = millis();

  // Si se apagó en medio de un ensayo, no retoma solo: avisa a la app cuando se conecte.
  if (prefs.getUChar("run", 0) == 1) {
    reinicioPendiente = true;
    Serial.println("ev,abort,reinicio");
  }

  iniciarBLE();

  Serial.print("Camara de Corrosion iniciada. BLE: ");
  Serial.println(NOMBRE_BLE);
  if (aj.nCond < 2) Serial.println("Aviso: falta calibrar la conductividad (ver README.md).");
}

void loop() {
  actualizarEntrada(reedLleno);
  actualizarEntrada(reedVacio);
  actualizarTemperatura();
  actualizarConductividad();
  actualizarVolumen();

  leerSerial();
  leerBle();

  ensayoActualizar();
  manualActualizar();
  directoActualizar();
  motorActualizar();
  syncActualizar(ensayoActivo);
  actualizarLeds();
}
