// Ensayo automático: llenar → dosificar sal → ajustar por conductividad → correr la duración pedida.
// Los enclavamientos (tanque vacío apaga la bomba, lleno cierra la válvula) viven acá y no se pueden desactivar.
#pragma once
#include "Config.h"
#include "Ajustes.h"
#include "Sensores.h"
#include "Actuadores.h"
#include "Enlace.h"
#include "Historial.h"

// Fases que ve la app (ev,paso): 1 llenado, 2 dosis, 3 ajuste, 4 ensayo.
enum class Sub : uint8_t { Reposo, Llenar, Dosificar, Asentar, Corregir, Correr };
const uint8_t FASE_IDX[]  = {0, 1, 2, 3, 3, 4};
const char*   FASE_WIRE[] = {"", "llenado", "dosis", "ajuste", "ensayo"};   // indexado por número de fase
const int     FASE_TOTAL  = 4;

bool  ensayoActivo = false;
Sub   sub = Sub::Reposo;
int   faseEmitida = 0;
float cfgConc = 5.0f;
uint32_t cfgDuracionS = 0, cfgMuestreoS = 60;
unsigned long tInicioMs = 0, tSubMs = 0;
uint32_t faseInicioS = 0, proximaMuestraS = 0;
float pedidoG = 0, salExistenteG = 0, tankMlInicio = 0;
int   correcciones = 0;

float tankMl = 0;                 // volumen estimado (ADR 0012): válvula × caudal, corregido por los reeds
unsigned long tVol = 0;

String f1(float v) { return isnan(v) ? String("NaN") : String(v, 1); }

uint32_t segActual() { return (millis() - tInicioMs) / 1000UL; }

void actualizarVolumen() {
  unsigned long ahora = millis();
  float dt = (ahora - tVol) / 1000.0f;
  tVol = ahora;
  if (valvulaAbierta) tankMl += aj.aguaMlS * dt;
  if (tankMl > aj.capMl) tankMl = aj.capMl;
  if (reedLleno.activa) tankMl = aj.capMl;   // los reeds recalibran el estimado
  if (reedVacio.activa) tankMl = 0;
}

// ---- Cierre del ensayo ----------------------------------------------------------------------------
void ensayoCerrar() {
  apagarTodo();
  ensayoActivo = false;
  sub = Sub::Reposo;
  historialFlush();
  prefs.putUInt("seq", seqGlobal);
  prefs.putUChar("run", 0);
}

void ensayoAbortar(const char *motivo) {
  if (!ensayoActivo) return;
  ensayoCerrar();
  evento(String("ev,abort,") + motivo);
  if (strcmp(motivo, "detener") != 0) sonar(2000, 1500);
}

void ensayoFinalizar() {
  ensayoCerrar();
  evento("ev,fin");
  sonar(1000, 3000);
}

// ---- Fases ----------------------------------------------------------------------------------------
void ensayoComenzar(Sub nueva) {
  sub = nueva;
  tSubMs = millis();
  int idx = FASE_IDX[(int)nueva];
  if (idx != faseEmitida) {
    faseEmitida = idx;
    faseInicioS = segActual();
    evento("ev,paso," + String(idx) + "," + String(FASE_TOTAL) + "," + FASE_WIRE[idx] + "," + String(faseInicioS));
  }
  switch (nueva) {
    case Sub::Llenar:
      tankMlInicio = tankMl;
      if (!reedLleno.activa) releValvula(true);
      break;
    case Sub::Dosificar: {
      releValvula(false);
      float g = DOSIS_INICIAL * gramosPara(cfgConc, aj.capMl) - salExistenteG;
      pedidoG = constrain(g, 0.0f, DOSIS_MAX_G);
      motorIniciar((uint32_t)(pedidoG * aj.salPasosG + 0.5f));
      break;
    }
    case Sub::Asentar:
      releMezcla(true);
      break;
    case Sub::Corregir: {
      float medida = concentracionPct();
      float g = gramosPara(cfgConc, tankMl) - gramosPara(medida, tankMl);
      pedidoG = constrain(g, 0.0f, DOSIS_MAX_G);
      motorIniciar((uint32_t)(pedidoG * aj.salPasosG + 0.5f));
      break;
    }
    case Sub::Correr:
      releMezcla(false);
      releBomba(true);
      break;
    default: break;
  }
}

void registrarMuestra(uint32_t seg) {
  Registro r;
  r.seq = ++seqGlobal;
  r.seg = seg;
  r.temp = temperatura;
  float p = concentracionPct();
  r.cond = p;
  r.vol = tankMl;
  historialAgregar(r);
  if (!sincro.activo) evento(lineaMuestra(r));
}

// Devuelve nullptr si arrancó, o el código de error en `codigo` y su texto en `mensaje`.
bool ensayoIniciar(float conc, uint32_t duracionS, uint32_t muestreoS, const char *&codigo, const char *&mensaje) {
  if (ensayoActivo) { codigo = "ocupado"; mensaje = "hay un ensayo en curso"; return false; }
  if (isnan(conc) || conc < CONC_MIN || conc > CONC_MAX) { codigo = "rango"; mensaje = "concentracion fuera de rango (0.1 a 25 %)"; return false; }
  if (duracionS < DURACION_MIN_S || duracionS > DURACION_MAX_S) { codigo = "rango"; mensaje = "duracion entre 60 s y 7 dias"; return false; }
  if (muestreoS < MUESTREO_MIN_S || muestreoS > MUESTREO_MAX_S) { codigo = "rango"; mensaje = "muestreo entre 5 y 3600 s"; return false; }
  if (aj.nCond < 2) { codigo = "sin_calibrar"; mensaje = "falta calibrar la conductividad (calibrar(cond,<pct>) con 2 o mas soluciones)"; return false; }
  if (!sensoresNivelOk()) { codigo = "sensores"; mensaje = "los dos reeds de nivel estan activos"; return false; }

  cfgConc = conc; cfgDuracionS = duracionS; cfgMuestreoS = muestreoS;
  // Sal que ya hay en el tanque (si lo hay): se estima con la conductividad antes de llenar.
  float p = concentracionPct();
  salExistenteG = (reedVacio.activa || isnan(p)) ? 0.0f : gramosPara(p, tankMl);

  ensayoId++;
  uint32_t seqInicial = seqGlobal + 1;
  prefs.putUInt("ensayo", ensayoId);
  prefs.putUChar("run", 1);          // si se reinicia con esto en 1, se avisa ev,abort,reinicio
  historialNuevoEnsayo(ensayoId, seqInicial);

  tInicioMs = millis();
  proximaMuestraS = muestreoS;
  faseEmitida = 0;
  correcciones = 0;
  ensayoActivo = true;
  responder("ok");
  evento("ev,ensayo," + String(ensayoId) + "," + String(seqInicial));
  ensayoComenzar(Sub::Llenar);
  return true;
}

void ensayoActualizar() {
  if (!ensayoActivo) return;

  uint32_t seg = segActual();
  while (seg >= proximaMuestraS) {
    registrarMuestra(proximaMuestraS);
    proximaMuestraS += cfgMuestreoS;
  }

  // Enclavamiento: sin agua no se mezcla ni se riega (protege la bomba).
  if (sub != Sub::Llenar && reedVacio.activa) { ensayoAbortar("tanque_vacio"); return; }
  if (!sensoresNivelOk()) { ensayoAbortar("sensores"); return; }

  switch (sub) {
    case Sub::Llenar: {
      float plazoMs = (aj.capMl / aj.aguaMlS) * 1500.0f + 20000.0f;   // 1,5× el tiempo esperado + 20 s
      if (reedLleno.activa) {
        releValvula(false);
        evento("ev,dosis,agua," + f1(aj.capMl - tankMlInicio) + "," + f1(tankMl - tankMlInicio) + ",lleno");
        ensayoComenzar(Sub::Dosificar);
      } else if (millis() - tSubMs > (unsigned long)plazoMs) {
        ensayoAbortar("llenado");   // la válvula no llenó: falta agua en el depósito o está trabada
      }
      break;
    }
    case Sub::Dosificar:
    case Sub::Corregir:
      if (!motor.activo) {
        evento("ev,dosis,sal," + f1(pedidoG) + "," + f1(pedidoG) + (sub == Sub::Corregir ? ",ajuste" : ",ok"));
        ensayoComenzar(Sub::Asentar);
      }
      break;
    case Sub::Asentar:
      if (millis() - tSubMs >= ASENTAR_MS) {
        float medida = concentracionPct();
        if (isnan(medida)) { ensayoAbortar("sensor"); break; }
        float dif = cfgConc - medida;
        if (fabsf(dif) <= TOLERANCIA_PCT) ensayoComenzar(Sub::Correr);
        else if (dif < 0) ensayoAbortar("conc_alta");               // no se puede quitar sal
        else if (correcciones >= MAX_CORRECCIONES) ensayoAbortar("no_converge");
        else if (gramosPara(cfgConc, tankMl) - gramosPara(medida, tankMl) < CORRECCION_MIN_G) ensayoComenzar(Sub::Correr);
        else { correcciones++; ensayoComenzar(Sub::Corregir); }
      }
      break;
    case Sub::Correr:
      if (millis() - tSubMs >= (unsigned long)cfgDuracionS * 1000UL) ensayoFinalizar();
      break;
    default: break;
  }
}

// ---- Dosificación manual (nivel(...)), solo sin ensayo -------------------------------------------
enum class Manual : uint8_t { Nada, Agua, Sal, Mezcla };
Manual manual = Manual::Nada;
float manualPedido = 0, manualInicio = 0;
unsigned long manualT = 0;

// ---- Operaciones atómicas: actuar(<actuador>,...) enciende una sola cosa, sin ensayo -------------
// Los relés se apagan solos a los DIRECTO_MAX_MS, la válvula al llegar el reed de lleno y `detener` apaga todo.
bool directoRele[3] = {false, false, false};          // 0 válvula, 1 mezclador, 2 bomba
unsigned long directoT[3] = {0, 0, 0};
bool ledForzado[2] = {false, false};                  // 0 verde, 1 rojo: mientras estén forzados no se calculan solos
bool directoAlguno() { return directoRele[0] || directoRele[1] || directoRele[2] || ledForzado[0] || ledForzado[1] || motor.activo; }
bool manualLibre() { return !ensayoActivo && manual == Manual::Nada && !sincro.activo && !directoAlguno(); }
const char *NOMBRE_RELE[3] = {"valvula", "mezclador", "bomba"};

void directoRele_set(int i, bool on) {
  directoRele[i] = on;
  directoT[i] = millis();
  if (i == 0) releValvula(on); else if (i == 1) releMezcla(on); else releBomba(on);
}

void directoActualizar() {
  for (int i = 0; i < 3; i++) {
    if (!directoRele[i]) continue;
    const char *motivo = nullptr;
    if (i == 0 && reedLleno.activa) motivo = "lleno";
    else if (i == 2 && reedVacio.activa) motivo = "vacio";
    else if (millis() - directoT[i] >= DIRECTO_MAX_MS) motivo = "tiempo";
    if (motivo) { directoRele_set(i, false); evento(String("ev,manual_fin,") + NOMBRE_RELE[i] + "," + motivo); }
  }
}

void directoApagar() {
  for (int i = 0; i < 3; i++) directoRele[i] = false;
  ledForzado[0] = ledForzado[1] = false;
}


void manualAgua(float ml, bool hastaLleno) {
  manualInicio = tankMl;
  manualPedido = hastaLleno ? aj.capMl - tankMl : ml;
  if (manualPedido < 0) manualPedido = 0;
  manual = Manual::Agua;
  if (!reedLleno.activa && manualPedido > 0) releValvula(true);
}

void manualSal(float g) {
  manualPedido = g;
  manual = Manual::Sal;
  motorIniciar((uint32_t)(g * aj.salPasosG + 0.5f));
}

void manualActualizar() {
  switch (manual) {
    case Manual::Agua: {
      bool lleno = reedLleno.activa;
      float agregado = tankMl - manualInicio;
      if (lleno || agregado >= manualPedido) {
        releValvula(false);
        evento("ev,dosis,agua," + f1(manualPedido) + "," + f1(agregado) + (lleno ? ",lleno" : ",ok"));
        manual = Manual::Nada;
      }
      break;
    }
    case Manual::Sal:
      if (!motor.activo) {
        evento("ev,dosis,sal," + f1(manualPedido) + "," + f1(manualPedido) + ",ok");
        releMezcla(true);
        manualT = millis();
        manual = Manual::Mezcla;
      }
      break;
    case Manual::Mezcla:
      if (millis() - manualT >= MEZCLA_MANUAL_MS) { releMezcla(false); manual = Manual::Nada; }
      break;
    default: break;
  }
}

void manualCancelar() { apagarTodo(); directoApagar(); manual = Manual::Nada; }

// LED verde cuando la solución está en su concentración, rojo si no (o si falta calibrar).
void actualizarLeds() {
  if (ledForzado[0] && ledForzado[1]) return;
  float p = concentracionPct();
  bool ok;
  if (ensayoActivo && (sub == Sub::Correr || sub == Sub::Asentar || sub == Sub::Corregir))
    ok = !isnan(p) && fabsf(p - cfgConc) <= TOLERANCIA_PCT * 2;
  else
    ok = !isnan(p) && p >= 0.5f;
  if (!ledForzado[0]) digitalWrite(PIN_LED_VERDE, ok ? HIGH : LOW);
  if (!ledForzado[1]) digitalWrite(PIN_LED_ROJO, ok ? LOW : HIGH);
}
