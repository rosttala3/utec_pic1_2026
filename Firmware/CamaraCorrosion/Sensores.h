// Reeds (nivel), temperatura y conductividad.
#pragma once
#include <OneWire.h>
#include <DallasTemperature.h>
#include "Config.h"
#include "Ajustes.h"

OneWire oneWire(PIN_TEMP);
DallasTemperature sensorTemp(&oneWire);

struct Entrada {
  uint8_t pin;
  bool activa;
  bool lecturaPrev;
  unsigned long tCambio;
};
Entrada reedLleno = {PIN_REED_LLENO, false, false, 0};
Entrada reedVacio = {PIN_REED_VACIO, false, false, 0};

float temperatura = NAN;
float condMetrica = NAN;    // lectura de conductividad filtrada y compensada a 25 °C
unsigned long tCond = 0, tTemp = 0;

void actualizarEntrada(Entrada &e) {
  bool lectura = (digitalRead(e.pin) == REED_ACTIVO);
  if (lectura != e.lecturaPrev) {
    e.lecturaPrev = lectura;
    e.tCambio = millis();
  }
  if (millis() - e.tCambio >= DEBOUNCE_MS) e.activa = lectura;
}

const char* textoNivel() {
  if (reedLleno.activa && reedVacio.activa) return "ERROR";
  if (reedLleno.activa) return "lleno";
  if (reedVacio.activa) return "vacio";
  return "medio";
}

bool sensoresNivelOk() { return !(reedLleno.activa && reedVacio.activa); }

void actualizarTemperatura() {
  if (millis() - tTemp < INTERVALO_TEMP_MS) return;
  tTemp = millis();
  float t = sensorTemp.getTempCByIndex(0);
  temperatura = (t == DEVICE_DISCONNECTED_C) ? NAN : t;
  sensorTemp.requestTemperatures();  // no bloquea (setWaitForConversion(false))
}

// Lectura cruda de los electrodos, promediando n pares de pulsos. Sensor de 3 cables (ADR 0011):
//   electrodo A (D32) ── solución ── electrodo B (D34, ADC)
//   electrodo B resist (D33) ── resistencia R ── electrodo B
// El ADC lee directo en el electrodo B, o sea el punto entre la solución y R, que forman un divisor.
//   Fase 1: A=alto,  B resist=bajo  → v1 = 4095·R/(R+Rsol)
//   Fase 2: A=bajo,  B resist=alto  → lectura = 4095·Rsol/(R+Rsol) → v2 = 4095 − lectura = 4095·R/(R+Rsol)
// Se promedian las dos fases llevadas al mismo sentido. La corriente neta es cero (sin electrólisis) y entre
// muestras A y B resist quedan en bajo. v crece con la conductancia de la solución (con la sal).
// Se devuelve una magnitud proporcional a la conductancia: 1/Rsol ∝ v/(4095−v).
float leerMetricaCruda(int n) {
  float suma = 0;
  for (int i = 0; i < n; i++) {
    digitalWrite(PIN_COND_A, HIGH); digitalWrite(PIN_COND_B_RESIST, LOW);
    delayMicroseconds(150);
    int v1 = analogRead(PIN_COND_B);
    digitalWrite(PIN_COND_A, LOW); digitalWrite(PIN_COND_B_RESIST, HIGH);
    delayMicroseconds(150);
    int v2 = 4095 - analogRead(PIN_COND_B);
    digitalWrite(PIN_COND_B_RESIST, LOW);
    suma += (v1 + v2) / 2.0f;
  }
  float v = suma / n;
  if (v < 1) v = 1;
  if (v > 4094) v = 4094;
  return v / (4095.0f - v) * 1000.0f;
}

// Compensación por temperatura: la conductividad sube ~2 % por °C; se lleva todo a 25 °C.
float compensarTemperatura(float m) {
  if (isnan(temperatura)) return m;
  return m / (1.0f + COEF_TEMP * (temperatura - 25.0f));
}

void actualizarConductividad() {
  if (millis() - tCond < INTERVALO_COND_MS) return;
  tCond = millis();
  float m = compensarTemperatura(leerMetricaCruda(8));
  condMetrica = isnan(condMetrica) ? m : condMetrica + 0.15f * (m - condMetrica);
}

// Lectura fresca y promediada, para calibrar.
float condMetricaParaCalibrar() { return compensarTemperatura(leerMetricaCruda(64)); }

// Concentración en % de NaCl según la curva calibrada (NAN si falta calibrar).
float concentracionPct() { return adcAPorcentaje(condMetrica); }
