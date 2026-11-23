// Parser de líneas (UART y BLE) y respuestas. Sintaxis tolerante: minúsculas, sin espacios, "f(a,b)" ≡ "f,a,b".
#pragma once
#include "Config.h"
#include "Ajustes.h"
#include "Sensores.h"
#include "Actuadores.h"
#include "Enlace.h"
#include "Historial.h"
#include "Ensayo.h"

bool reinicioPendiente = false;   // el ESP32 arrancó con un ensayo a medias (corte de energía)

void responderErr(const char *codigo, const String &mensaje) { responder("err," + String(codigo) + "," + mensaje); }

bool aNumero(const String &s, float &out) {
  if (s.length() == 0) return false;
  char *fin = nullptr;
  float v = strtof(s.c_str(), &fin);
  if (fin == s.c_str() || *fin != '\0' || isnan(v)) return false;
  out = v;
  return true;
}

// ---- estado ---------------------------------------------------------------------------------------
String numeroJson(float v, int dec) { return isnan(v) ? String("null") : String(v, dec); }

String estadoJson() {
  String j = "{\"estado\":\"";
  j += ensayoActivo ? "corriendo" : "reposo";
  j += "\",\"ensayo\":" + String(ensayoId);
  j += ",\"seg\":" + String(ensayoActivo ? segActual() : 0);
  j += ",\"seq\":" + String(seqGlobal);
  j += ",\"temp\":" + numeroJson(temperatura, 1);
  j += ",\"cond\":" + numeroJson(concentracionPct(), 2);
  j += ",\"vol\":" + String(tankMl, 0);
  j += ",\"tanque\":\"" + String(reedVacio.activa ? "vacio" : (reedLleno.activa ? "lleno" : "medio")) + "\"";
  j += ",\"cap_ml\":" + String((int)aj.capMl);
  if (ensayoActivo) {
    j += ",\"fase\":\"" + String(FASE_WIRE[FASE_IDX[(int)sub]]) + "\"";
    j += ",\"fase_inicio\":" + String(faseInicioS);
    j += ",\"conc_obj\":" + String(cfgConc, 2);
    j += ",\"duracion_s\":" + String(cfgDuracionS);
  }
  j += String(",\"reed_lleno\":") + (reedLleno.activa ? 1 : 0) + ",\"reed_vacio\":" + (reedVacio.activa ? 1 : 0);
  j += String(",\"act\":{\"valvula\":") + (digitalRead(PIN_RELE_VALVULA) == RELE_ON ? 1 : 0);
  j += String(",\"mezclador\":") + (digitalRead(PIN_RELE_MEZCLA) == RELE_ON ? 1 : 0);
  j += String(",\"bomba\":") + (digitalRead(PIN_RELE_BOMBA) == RELE_ON ? 1 : 0);
  j += String(",\"sinfin\":") + (motor.activo ? 1 : 0);
  j += String(",\"led_verde\":") + (digitalRead(PIN_LED_VERDE) ? 1 : 0);
  j += String(",\"led_rojo\":") + (digitalRead(PIN_LED_ROJO) ? 1 : 0) + "}";
  // Los LED también se encienden solos (verde/rojo según la concentración): "forz" dice cuáles fuerza la app.
  j += String(",\"forz\":{\"led_verde\":") + (ledForzado[0] ? 1 : 0) + ",\"led_rojo\":" + (ledForzado[1] ? 1 : 0) + "}";
  j += ",\"cal\":{\"agua_ml_s\":" + String(aj.aguaMlS, 2);
  j += ",\"sal_pasos_g\":" + String(aj.salPasosG, 2);
  j += ",\"cond_puntos\":" + String(aj.nCond);
  j += ",\"cond_lectura\":" + numeroJson(condMetrica, 1) + "}}";
  return j;
}

void estadoLegible() {
  responder(String("Estado: ") + (ensayoActivo ? "corriendo" : "reposo"));
  if (ensayoActivo)
    responder("Fase: " + String(FASE_WIRE[FASE_IDX[(int)sub]]) + " · objetivo " + String(cfgConc, 2) + " % · " + String(cfgDuracionS) + " s");
  responder("Temperatura: " + f1(temperatura) + " C · Volumen: " + String(tankMl, 0) + " ml · Nivel: " + textoNivel());
  float p = concentracionPct();
  responder("Conductividad: lectura " + f1(condMetrica) + " · " + (isnan(p) ? String("sin calibrar") : String(p, 2) + " %"));
  responder("ok");
}

void ayuda() {
  responder("estado | estado,json | ensayo,<conc %>,<duracion s>,<muestreo s> | detener");
  responder("nivel(agua,<ml>) | nivel(agua,lleno) | nivel(sal,<g>) | nivel(sal,conc,<%>) | historial,<n>");
  responder("calibrar(agua,<ml/s>) | calibrar(sal,<pasos/g>) | calibrar(cap,<ml>) | calibrar(cond,<% NaCl>) | calibrar(cond,limpiar)");
  responder("actuar(valvula|mezclador|bomba,on|off) | actuar(sinfin,<pasos>|parar) | actuar(led_verde|led_rojo,on|off|auto) | actuar(buzzer,<hz>,<ms>)");
  responder("ok");
}

// ---- órdenes --------------------------------------------------------------------------------------
void cmdEnsayo(String *t, int n) {
  float conc = NAN, dur = NAN, mue = NAN;
  if (n < 3 || !aNumero(t[1], conc) || !aNumero(t[2], dur)) {
    return responderErr("sintaxis", "uso: ensayo,<conc %>,<duracion s>,<muestreo s>");
  }
  if (n >= 4) { if (!aNumero(t[3], mue)) return responderErr("sintaxis", "muestreo invalido"); } else mue = 60;
  if (dur < 0 || mue < 0 || dur > 4e9f || mue > 4e9f) return responderErr("rango", "duracion o muestreo invalidos");
  const char *codigo = nullptr, *mensaje = nullptr;
  if (directoAlguno()) return responderErr("ocupado", "hay actuadores en modo manual: apagalos con detener");
  if (!ensayoIniciar(conc, (uint32_t)dur, (uint32_t)mue, codigo, mensaje)) responderErr(codigo, mensaje);
}

void cmdNivel(String *t, int n) {
  if (!manualLibre()) return responderErr("ocupado", "hay un ensayo o una dosificacion en curso");
  float v = NAN;
  if (n >= 3 && t[1] == "agua" && t[2] == "lleno") {
    if (!sensoresNivelOk()) return responderErr("sensores", "los dos reeds de nivel estan activos");
    responder("ok"); manualAgua(0, true);
  } else if (n >= 3 && t[1] == "agua" && aNumero(t[2], v) && v > 0 && v <= aj.capMl) {
    if (!sensoresNivelOk()) return responderErr("sensores", "los dos reeds de nivel estan activos");
    responder("ok"); manualAgua(v, false);
  } else if (n >= 4 && t[1] == "sal" && t[2] == "conc" && aNumero(t[3], v) && v >= CONC_MIN && v <= CONC_MAX) {
    if (tankMl < 1) return responderErr("rango", "no hay agua en el tanque");
    responder("ok"); manualSal(constrain(gramosPara(v, tankMl), 0.0f, DOSIS_MAX_G));
  } else if (n >= 3 && t[1] == "sal" && aNumero(t[2], v) && v >= 0.5f && v <= DOSIS_MAX_G) {
    responder("ok"); manualSal(v);
  } else {
    responderErr("sintaxis", "uso: nivel(agua,<ml>) | nivel(agua,lleno) | nivel(sal,<g>) | nivel(sal,conc,<%>)");
  }
}

// actuar(<actuador>,<valor>): una operación atómica, solo sin ensayo ni dosificación (para probar el cableado y calibrar).
//   valvula|mezclador|bomba , on|off      sinfin , <pasos> (negativo = reversa) | parar
//   led_verde|led_rojo , on|off|auto      buzzer , <hz> [, <ms>]
void cmdActuar(String *t, int n) {
  if (n < 3) return responderErr("sintaxis", "uso: actuar(<valvula|mezclador|bomba|sinfin|led_verde|led_rojo|buzzer>,<valor>)");
  if (ensayoActivo || manual != Manual::Nada || sincro.activo) return responderErr("ocupado", "hay un ensayo o una dosificacion en curso");
  const String &a = t[1], &v = t[2];
  int rele = (a == "valvula") ? 0 : (a == "mezclador") ? 1 : (a == "bomba") ? 2 : -1;
  float x = NAN;
  if (rele >= 0) {
    if (v != "on" && v != "off") return responderErr("sintaxis", "valor: on u off");
    bool on = (v == "on");
    if (on && rele == 0 && !sensoresNivelOk()) return responderErr("sensores", "los dos reeds de nivel estan activos");
    if (on && rele == 0 && reedLleno.activa) return responderErr("lleno", "el tanque ya esta lleno");
    if (on && rele == 2 && reedVacio.activa) return responderErr("tanque_vacio", "la bomba no puede trabajar en seco");
    directoRele_set(rele, on);
    responder("ok");
  } else if (a == "sinfin") {
    if (v == "parar") { motorParar(); return responder("ok"); }
    if (!aNumero(v, x) || x == 0 || fabsf(x) > SINFIN_MAX_PASOS) return responderErr("rango", "pasos entre 1 y 20000 (negativo = reversa)");
    if (motor.activo) return responderErr("ocupado", "el sinfin ya esta girando");
    responder("ok");
    motorIniciar((uint32_t)fabsf(x), x > 0);
  } else if (a == "led_verde" || a == "led_rojo") {
    int i = (a == "led_rojo") ? 1 : 0;
    int pin = i ? PIN_LED_ROJO : PIN_LED_VERDE;
    if (v == "auto") ledForzado[i] = false;
    else if (v == "on" || v == "off") { ledForzado[i] = true; digitalWrite(pin, v == "on" ? HIGH : LOW); }
    else return responderErr("sintaxis", "valor: on, off o auto");
    responder("ok");
  } else if (a == "buzzer") {
    float ms = 300;
    if (!aNumero(v, x) || x < 100 || x > 8000) return responderErr("rango", "frecuencia entre 100 y 8000 Hz");
    if (n >= 4 && (!aNumero(t[3], ms) || ms < 10 || ms > 3000)) return responderErr("rango", "duracion entre 10 y 3000 ms");
    sonar((int)x, (int)ms);
    responder("ok");
  } else {
    responderErr("comando", "actuador desconocido (valvula, mezclador, bomba, sinfin, led_verde, led_rojo, buzzer)");
  }
}

void cmdCalibrar(String *t, int n) {
  if (ensayoActivo || manual != Manual::Nada) return responderErr("ocupado", "hay un ensayo o una dosificacion en curso");
  if (n < 3) return responderErr("sintaxis", "uso: calibrar(<agua|sal|cap|cond>,<valor>)");
  float v = NAN;
  if (t[1] == "cond" && t[2] == "limpiar") { condLimpiar(); return responder("ok"); }
  if (!aNumero(t[2], v) || v <= 0) return responderErr("rango", "valor de calibracion invalido");
  if (t[1] == "agua")      { aj.aguaMlS = v;   prefs.putFloat("agua", v); responder("ok"); }
  else if (t[1] == "sal")  { aj.salPasosG = v; prefs.putFloat("sal", v);  responder("ok"); }
  else if (t[1] == "cap")  { aj.capMl = v;     prefs.putFloat("cap", v);  responder("ok"); }
  else if (t[1] == "cond") {
    // Con los electrodos sumergidos en una solucion de v % de NaCl, se registra la lectura actual como un punto de la curva.
    if (v > CONC_MAX) return responderErr("rango", "concentracion invalida");
    const char *motivo = condAgregarPunto(condMetricaParaCalibrar(), v);
    if (motivo) responderErr("calibracion", motivo); else responder("ok");
  } else responderErr("comando", "magnitud desconocida (agua, sal, cap o cond)");
}

void procesarLinea(String cmd, Canal canal) {
  canalActual = canal;
  cmd.trim();
  cmd.toLowerCase();
  cmd.replace(" ", "");
  cmd.replace("\t", "");
  if (cmd.length() == 0) return;

  // Tras un corte de energía en pleno ensayo, el primer contacto de la app recibe el aviso (ADR 0006).
  if (reinicioPendiente && canal == CANAL_BLE) {
    reinicioPendiente = false;
    prefs.putUChar("run", 0);
    evento("ev,abort,reinicio");
  }

  cmd.replace("(", ",");
  cmd.replace(")", "");
  String t[6];
  int n = 0, ini = 0;
  while (n < 6) {
    int c = cmd.indexOf(',', ini);
    if (c < 0) { t[n++] = cmd.substring(ini); break; }
    t[n++] = cmd.substring(ini, c);
    ini = c + 1;
  }

  if (t[0] == "estado") {
    if (n >= 2 && t[1] == "json") responder(estadoJson()); else estadoLegible();
  } else if (t[0] == "ensayo") {
    cmdEnsayo(t, n);
  } else if (t[0] == "detener") {
    if (ensayoActivo) ensayoAbortar("detener");
    manualCancelar();
    responder("ok");
  } else if (t[0] == "nivel") {
    cmdNivel(t, n);
  } else if (t[0] == "calibrar") {
    cmdCalibrar(t, n);
  } else if (t[0] == "actuar") {
    cmdActuar(t, n);
  } else if (t[0] == "historial") {
    float despues = 0;
    if (n >= 2 && !aNumero(t[1], despues)) return responderErr("sintaxis", "uso: historial,<n>");
    if (despues < 0) despues = 0;
    if (!syncIniciar((uint32_t)despues, canal)) responderErr("ocupado", "ya hay una sincronizacion en curso");
    // sin "ok": la respuesta termina con ev,sync_fin
  } else if (t[0] == "ayuda") {
    ayuda();
  } else if (t[0] == "inicio") {
    responderErr("comando", "reemplazado por ensayo,<conc %>,<duracion s>,<muestreo s>");
  } else {
    responderErr("comando", "desconocido");
  }
}

String bufferSerial;

void leerSerial() {
  while (Serial.available() > 0) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') {
      if (bufferSerial.length() > 0) { procesarLinea(bufferSerial, CANAL_SERIE); bufferSerial = ""; }
    } else if (bufferSerial.length() < LINEA_MAX - 1) {
      bufferSerial += c;
    }
  }
}

void leerBle() {
  String linea;
  int guardia = 0;
  while (guardia++ < 3 && colaSacar(linea)) procesarLinea(linea, CANAL_BLE);
}
