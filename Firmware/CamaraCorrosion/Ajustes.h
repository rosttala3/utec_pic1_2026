// Calibración y contadores persistentes (NVS). Todo lo medido en banco entra por calibrar(...).
#pragma once
#include <Preferences.h>
#include "Config.h"

struct Ajustes {
  float capMl     = CAP_ML_DEF;
  float aguaMlS   = AGUA_ML_S_DEF;
  float salPasosG = SAL_PASOS_G_DEF;
  uint8_t nCond   = 0;                       // puntos de la curva de conductividad
  float condAdc[COND_MAX_PUNTOS] = {0};      // lectura compensada a 25 °C, creciente
  float condPct[COND_MAX_PUNTOS] = {0};      // % de NaCl que le corresponde
};

Ajustes aj;
Preferences prefs;
uint32_t ensayoId = 0;    // último ensayo iniciado
uint32_t seqGlobal = 0;   // última muestra generada (creciente entre ensayos)

// g_sal = conc / (100 − conc) × ml_agua   (agua ≈ 1 g/ml)
float gramosPara(float concPct, float aguaMl) { return concPct / (100.0f - concPct) * aguaMl; }

void ajustesCargar() {
  prefs.begin("camara", false);
  aj.capMl     = prefs.getFloat("cap", CAP_ML_DEF);
  aj.aguaMlS   = prefs.getFloat("agua", AGUA_ML_S_DEF);
  aj.salPasosG = prefs.getFloat("sal", SAL_PASOS_G_DEF);
  aj.nCond     = prefs.getUChar("cond_n", 0);
  if (aj.nCond > COND_MAX_PUNTOS) aj.nCond = 0;
  if (aj.nCond > 0) {
    prefs.getBytes("cond_adc", aj.condAdc, sizeof(aj.condAdc));
    prefs.getBytes("cond_pct", aj.condPct, sizeof(aj.condPct));
  }
  ensayoId  = prefs.getUInt("ensayo", 0);
  seqGlobal = prefs.getUInt("seq", 0);
}

void ajustesGuardarCond() {
  prefs.putUChar("cond_n", aj.nCond);
  prefs.putBytes("cond_adc", aj.condAdc, sizeof(aj.condAdc));
  prefs.putBytes("cond_pct", aj.condPct, sizeof(aj.condPct));
}

// Lectura compensada → % de NaCl, por interpolación lineal entre los puntos calibrados
// (se extrapola con el primer o el último tramo). NAN si todavía no hay curva.
float adcAPorcentaje(float x) {
  if (aj.nCond < 2 || isnan(x)) return NAN;
  int i = 0;
  while (i < aj.nCond - 2 && x > aj.condAdc[i + 1]) i++;
  float x0 = aj.condAdc[i], x1 = aj.condAdc[i + 1];
  float y0 = aj.condPct[i], y1 = aj.condPct[i + 1];
  float p = y0 + (y1 - y0) * (x - x0) / (x1 - x0);
  return p < 0 ? 0 : p;
}

// Agrega (o reemplaza) un punto de la curva. La curva debe ser estrictamente creciente en ambos ejes.
// Devuelve nullptr si salió bien, o el motivo del rechazo.
const char* condAgregarPunto(float adc, float pct) {
  float a[COND_MAX_PUNTOS + 1], p[COND_MAX_PUNTOS + 1];
  int n = 0;
  bool reemplazado = false;
  for (int i = 0; i < aj.nCond; i++) {
    if (fabsf(aj.condPct[i] - pct) < 0.05f) { a[n] = adc; p[n] = pct; n++; reemplazado = true; }
    else { a[n] = aj.condAdc[i]; p[n] = aj.condPct[i]; n++; }
  }
  if (!reemplazado) {
    if (n >= COND_MAX_PUNTOS) return "tabla llena (usar calibrar(cond,limpiar))";
    a[n] = adc; p[n] = pct; n++;
  }
  for (int i = 1; i < n; i++) {           // orden por concentración
    for (int j = i; j > 0 && p[j] < p[j - 1]; j--) {
      float t = p[j]; p[j] = p[j - 1]; p[j - 1] = t;
      t = a[j]; a[j] = a[j - 1]; a[j - 1] = t;
    }
  }
  for (int i = 1; i < n; i++) {
    if (!(a[i] > a[i - 1])) return "la lectura no crece con la concentracion; revisar electrodos";
  }
  aj.nCond = n;
  for (int i = 0; i < n; i++) { aj.condAdc[i] = a[i]; aj.condPct[i] = p[i]; }
  ajustesGuardarCond();
  return nullptr;
}

void condLimpiar() { aj.nCond = 0; ajustesGuardarCond(); }
