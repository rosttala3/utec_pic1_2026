// Transportes: UART (Serial) y BLE Nordic UART Service. Un solo formato de líneas para los dos (ADR 0006).
// Las respuestas a una orden vuelven por el canal que la envió; los eventos salen por los dos.
#pragma once
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include "Config.h"

enum Canal : uint8_t { CANAL_SERIE, CANAL_BLE };

BLECharacteristic *nusTx = nullptr;
volatile bool clienteBle = false;
volatile uint16_t mtuBle = 23;
Canal canalActual = CANAL_SERIE;

// Cola de líneas recibidas por BLE (el callback corre en otra tarea).
const int COLA_N = 6;
const int LINEA_MAX = 96;
char colaBle[COLA_N][LINEA_MAX];
volatile uint8_t colaCabeza = 0, colaCola = 0;
portMUX_TYPE muxCola = portMUX_INITIALIZER_UNLOCKED;

void colaEmpujar(const String &s) {
  portENTER_CRITICAL(&muxCola);
  uint8_t sig = (colaCabeza + 1) % COLA_N;
  if (sig != colaCola) {              // si está llena se descarta la línea
    strncpy(colaBle[colaCabeza], s.c_str(), LINEA_MAX - 1);
    colaBle[colaCabeza][LINEA_MAX - 1] = 0;
    colaCabeza = sig;
  }
  portEXIT_CRITICAL(&muxCola);
}

bool colaSacar(String &out) {
  bool hay = false;
  portENTER_CRITICAL(&muxCola);
  if (colaCola != colaCabeza) {
    out = String(colaBle[colaCola]);
    colaCola = (colaCola + 1) % COLA_N;
    hay = true;
  }
  portEXIT_CRITICAL(&muxCola);
  return hay;
}

// Una línea por notificación o varias si no entra en la MTU negociada (la app reensambla por '\n').
void bleEnviar(const String &linea) {
  if (!clienteBle || nusTx == nullptr) return;
  String s = linea + "\n";
  size_t maxPayload = mtuBle > 3 ? mtuBle - 3 : 20;
  if (maxPayload > 180) maxPayload = 180;
  for (size_t i = 0; i < s.length(); i += maxPayload) {
    size_t n = s.length() - i;
    if (n > maxPayload) n = maxPayload;
    nusTx->setValue((uint8_t *)s.c_str() + i, n);
    nusTx->notify();
    delay(3);                          // deja respirar a la pila BLE
  }
}

void enviarEn(Canal c, const String &linea) {
  if (c == CANAL_BLE) bleEnviar(linea);
  else Serial.println(linea);
}

void responder(const String &linea) { enviarEn(canalActual, linea); }

void evento(const String &linea) {
  Serial.println(linea);
  bleEnviar(linea);
}

class ServidorCb : public BLEServerCallbacks {
  void onConnect(BLEServer *, esp_ble_gatts_cb_param_t *) override {
    clienteBle = true;
    mtuBle = 23;
  }
  void onDisconnect(BLEServer *, esp_ble_gatts_cb_param_t *) override {
    clienteBle = false;
    BLEDevice::startAdvertising();     // permite reconectar sin reiniciar el ESP32
  }
  void onMtuChanged(BLEServer *, esp_ble_gatts_cb_param_t *param) override {
    mtuBle = param->mtu.mtu;
  }
};

class RxCb : public BLECharacteristicCallbacks {
  String acumulado;
  void onWrite(BLECharacteristic *c) override {
    const uint8_t *d = c->getData();
    size_t n = c->getLength();
    for (size_t i = 0; i < n; i++) {
      char ch = (char)d[i];
      if (ch == '\n' || ch == '\r') {
        if (acumulado.length() > 0) { colaEmpujar(acumulado); acumulado = ""; }
      } else if (acumulado.length() < LINEA_MAX - 1) {
        acumulado += ch;
      }
    }
  }
};

void iniciarBLE() {
  BLEDevice::init(NOMBRE_BLE);
  BLEDevice::setMTU(185);
  BLEServer *servidor = BLEDevice::createServer();
  servidor->setCallbacks(new ServidorCb());

  BLEService *servicio = servidor->createService(UUID_NUS);
  nusTx = servicio->createCharacteristic(UUID_NUS_TX, BLECharacteristic::PROPERTY_NOTIFY);
  nusTx->addDescriptor(new BLE2902());   // necesario para que la app se suscriba
  BLECharacteristic *rx = servicio->createCharacteristic(
    UUID_NUS_RX, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  rx->setCallbacks(new RxCb());
  servicio->start();

  BLEAdvertising *publicidad = BLEDevice::getAdvertising();
  publicidad->addServiceUUID(UUID_NUS);   // la app filtra el escaneo por este UUID
  publicidad->setScanResponse(true);
  BLEDevice::startAdvertising();
}
