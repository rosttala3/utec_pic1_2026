// Muestras en LittleFS (un archivo por ensayo) y sincronización con la app (ADR 0004 / 0006).
// Archivo e<id>.bin = cabecera {id, seqInicial} + registros de 20 bytes. Se conservan los dos últimos ensayos.
// Las muestras se acumulan en RAM y se escriben juntas cada BUF_MUESTRAS (menos desgaste de flash).
#pragma once
#include <LittleFS.h>
#include "Config.h"
#include "Ajustes.h"
#include "Enlace.h"

struct __attribute__((packed)) Registro {
  uint32_t seq;
  uint32_t seg;
  float temp;
  float cond;
  float vol;
};
struct __attribute__((packed)) CabeceraArchivo {
  uint32_t id;
  uint32_t seqInicial;
};

Registro bufReg[BUF_MUESTRAS];
uint8_t nBuf = 0;
uint32_t idArchivoActual = 0;
bool fsListo = false;

String rutaEnsayo(uint32_t id) { return "/e" + String(id) + ".bin"; }

// Ids de los ensayos guardados, de menor a mayor.
void historialListarIds(uint32_t *ids, int &n, int maxN) {
  n = 0;
  if (!fsListo) return;
  File raiz = LittleFS.open("/");
  if (!raiz) return;
  File f = raiz.openNextFile();
  while (f) {
    String nombre = String(f.name());
    int barra = nombre.lastIndexOf('/');
    if (barra >= 0) nombre = nombre.substring(barra + 1);
    if (nombre.startsWith("e") && nombre.endsWith(".bin")) {
      uint32_t id = (uint32_t)nombre.substring(1, nombre.length() - 4).toInt();
      if (id > 0 && n < maxN) {
        int j = n++;
        while (j > 0 && ids[j - 1] > id) { ids[j] = ids[j - 1]; j--; }
        ids[j] = id;
      }
    }
    f = raiz.openNextFile();
  }
  raiz.close();
}

// Última secuencia escrita en un archivo (0 si no tiene registros).
uint32_t historialUltimoSeq(uint32_t id) {
  File f = LittleFS.open(rutaEnsayo(id), FILE_READ);
  if (!f) return 0;
  size_t cuerpo = f.size() > sizeof(CabeceraArchivo) ? f.size() - sizeof(CabeceraArchivo) : 0;
  size_t cant = cuerpo / sizeof(Registro);
  uint32_t seq = 0;
  if (cant > 0) {
    f.seek(sizeof(CabeceraArchivo) + (cant - 1) * sizeof(Registro));
    Registro r;
    if (f.read((uint8_t *)&r, sizeof(r)) == sizeof(r)) seq = r.seq;
  }
  f.close();
  return seq;
}

void historialIniciar() {
  fsListo = LittleFS.begin(true);   // formatea si es la primera vez
  if (!fsListo) return;
  uint32_t ids[8]; int n;
  historialListarIds(ids, n, 8);
  if (n > 0) {
    idArchivoActual = ids[n - 1];
    // Tras un corte puede haber muestras en el archivo que NVS no llegó a contar.
    uint32_t ultimo = historialUltimoSeq(idArchivoActual);
    if (ultimo > seqGlobal) seqGlobal = ultimo;
    if (idArchivoActual > ensayoId) ensayoId = idArchivoActual;
  }
}

void historialFlush() {
  if (nBuf == 0 || !fsListo || idArchivoActual == 0) { nBuf = 0; return; }
  File f = LittleFS.open(rutaEnsayo(idArchivoActual), FILE_APPEND);
  if (f) {
    f.write((uint8_t *)bufReg, nBuf * sizeof(Registro));
    f.close();
  }
  nBuf = 0;
}

void historialNuevoEnsayo(uint32_t id, uint32_t seqInicial) {
  historialFlush();
  idArchivoActual = id;
  if (!fsListo) return;
  File f = LittleFS.open(rutaEnsayo(id), FILE_WRITE);
  if (f) {
    CabeceraArchivo h = {id, seqInicial};
    f.write((uint8_t *)&h, sizeof(h));
    f.close();
  }
  // Se conservan solo los dos últimos ensayos.
  uint32_t ids[8]; int n;
  historialListarIds(ids, n, 8);
  for (int i = 0; i < n - ENSAYOS_GUARDADOS; i++) LittleFS.remove(rutaEnsayo(ids[i]));
}

void historialAgregar(const Registro &r) {
  bufReg[nBuf++] = r;
  if (nBuf >= BUF_MUESTRAS) historialFlush();
}

// `t,<seq>,<seg>,<temp>,<cond>,<vol>`  — NaN se escribe "NaN", que la app sabe leer.
String lineaMuestra(const Registro &r) {
  char b[96];
  char t[16], c[16], v[16];
  if (isnan(r.temp)) strcpy(t, "NaN"); else snprintf(t, sizeof(t), "%.1f", r.temp);
  if (isnan(r.cond)) strcpy(c, "NaN"); else snprintf(c, sizeof(c), "%.2f", r.cond);
  if (isnan(r.vol)) strcpy(v, "NaN"); else snprintf(v, sizeof(v), "%.1f", r.vol);
  snprintf(b, sizeof(b), "t,%lu,%lu,%s,%s,%s", (unsigned long)r.seq, (unsigned long)r.seg, t, c, v);
  return String(b);
}

// ---- Sincronización: historial,<n> reenvía todo lo posterior a n y termina con ev,sync_fin ----------
// Es de solo lectura y no bloquea: una línea cada INTERVALO_SYNC_MS. Mientras dura, las muestras nuevas se
// guardan pero no salen en vivo, así no se mezclan con las de un ensayo anterior; la sincronización las
// alcanza antes de terminar.
struct Sync {
  bool activo = false;
  Canal canal = CANAL_SERIE;
  uint32_t despues = 0;
  uint32_t ids[8];
  int n = 0;
  int idx = 0;
  size_t offset = 0;      // posición dentro del archivo actual
  bool marcaEnviada = false;
  unsigned long tUlt = 0;
};
Sync sincro;

bool syncIniciar(uint32_t despues, Canal canal) {
  if (sincro.activo) return false;
  historialFlush();
  sincro.despues = despues; sincro.canal = canal;
  historialListarIds(sincro.ids, sincro.n, 8);
  sincro.idx = 0; sincro.offset = sizeof(CabeceraArchivo); sincro.marcaEnviada = false;
  sincro.tUlt = 0;
  sincro.activo = true;
  return true;
}

void syncActualizar(bool ensayoCorriendo) {
  if (!sincro.activo) return;
  if (millis() - sincro.tUlt < INTERVALO_SYNC_MS) return;
  sincro.tUlt = millis();

  for (int guardia = 0; guardia < 4; guardia++) {   // como mucho unos pocos archivos por vuelta
    if (sincro.idx >= sincro.n) {
      enviarEn(sincro.canal, "ev,sync_fin");
      sincro.activo = false;
      return;
    }
    uint32_t id = sincro.ids[sincro.idx];
    File f = LittleFS.open(rutaEnsayo(id), FILE_READ);
    bool enviada = false, eof = true;
    if (f) {
      CabeceraArchivo h = {0, 0};
      f.read((uint8_t *)&h, sizeof(h));
      f.seek(sincro.offset);
      Registro r;
      for (int k = 0; k < 400; k++) {              // salta rápido lo que la app ya tiene
        if (f.read((uint8_t *)&r, sizeof(r)) != sizeof(r)) { eof = true; break; }
        eof = false;
        sincro.offset += sizeof(r);
        if (r.seq <= sincro.despues) continue;
        if (!sincro.marcaEnviada) {
          enviarEn(sincro.canal, "ev,ensayo," + String(id) + "," + String(h.seqInicial));
          sincro.marcaEnviada = true;
        }
        enviarEn(sincro.canal, lineaMuestra(r));
        enviada = true;
        break;
      }
      f.close();
    }
    if (enviada) return;
    if (eof) {
      // Fin de este archivo. Si es el del ensayo en curso, puede haber muestras todavía en RAM.
      if (ensayoCorriendo && id == idArchivoActual && nBuf > 0) { historialFlush(); continue; }
      sincro.idx++; sincro.offset = sizeof(CabeceraArchivo); sincro.marcaEnviada = false;
    }
  }
}
