// Prueba de Bluetooth (BLE): activar/desactivar el parpadeo del LED integrado
// desde la app Android (carpeta AndroidApp).
//
// Servicio BLE:
//   - Característica "parpadeo" (lectura / escritura / notificación), 1 byte:
//       0 = parpadeo apagado, 1 = parpadeo encendido
//   La app escribe el valor y el ESP32 notifica el estado actual.
//
// También acepta por Serial (115200): "on", "off", "estado".

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ======================= PINES =======================
#ifdef LED_BUILTIN
const int PIN_LED = LED_BUILTIN;
#else
const int PIN_LED = 2;  // LED integrado en la mayoría de los ESP32 DevKit
#endif

// ======================= CONFIGURACIÓN =======================
// Deben coincidir con AndroidApp/app/src/main/java/uy/edu/utec/pic1/BleConstants.kt
#define NOMBRE_BLE            "PIC1-ESP32"
#define UUID_SERVICIO         "821aef38-c20d-4e30-92a7-90cdfa14b52b"
#define UUID_CARACT_PARPADEO  "2cf676d5-bece-4323-b1ba-5502e0bd2870"

const unsigned long INTERVALO_PARPADEO_MS = 500;

// ======================= ESTADO =======================
BLECharacteristic *caractParpadeo = nullptr;
bool parpadeoActivo = false;
bool ledEncendido = false;
bool clienteConectado = false;
unsigned long tParpadeo = 0;

String bufferSerial;

// ======================= PARPADEO =======================
void notificarEstado() {
  if (caractParpadeo == nullptr) return;
  uint8_t valor = parpadeoActivo ? 1 : 0;
  caractParpadeo->setValue(&valor, 1);
  if (clienteConectado) caractParpadeo->notify();
}

void setParpadeo(bool activo) {
  parpadeoActivo = activo;
  if (!activo) {
    ledEncendido = false;
    digitalWrite(PIN_LED, LOW);
  }
  tParpadeo = millis();
  notificarEstado();

  Serial.print("Parpadeo: ");
  Serial.println(activo ? "ENCENDIDO" : "APAGADO");
}

void actualizarParpadeo() {
  if (!parpadeoActivo) return;
  if (millis() - tParpadeo < INTERVALO_PARPADEO_MS) return;
  tParpadeo = millis();

  ledEncendido = !ledEncendido;
  digitalWrite(PIN_LED, ledEncendido ? HIGH : LOW);
}

// ======================= CALLBACKS BLE =======================
class ServidorCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *servidor) override {
    clienteConectado = true;
    Serial.println("Cliente BLE conectado.");
  }

  void onDisconnect(BLEServer *servidor) override {
    clienteConectado = false;
    Serial.println("Cliente BLE desconectado. Publicitando de nuevo...");
    BLEDevice::startAdvertising();  // Permite reconectar sin reiniciar el ESP32
  }
};

class ParpadeoCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *caract) override {
    // getData()/getLength() funcionan igual en el core 2.x y 3.x del ESP32
    if (caract->getLength() < 1) return;
    setParpadeo(caract->getData()[0] != 0);
  }
};

void iniciarBLE() {
  BLEDevice::init(NOMBRE_BLE);
  BLEServer *servidor = BLEDevice::createServer();
  servidor->setCallbacks(new ServidorCallbacks());

  BLEService *servicio = servidor->createService(UUID_SERVICIO);
  caractParpadeo = servicio->createCharacteristic(
    UUID_CARACT_PARPADEO,
    BLECharacteristic::PROPERTY_READ |
    BLECharacteristic::PROPERTY_WRITE |
    BLECharacteristic::PROPERTY_NOTIFY
  );
  caractParpadeo->addDescriptor(new BLE2902());  // Necesario para que la app se suscriba a notificaciones
  caractParpadeo->setCallbacks(new ParpadeoCallbacks());
  notificarEstado();
  servicio->start();

  // Se publicita el UUID del servicio para que la app filtre el escaneo por él
  BLEAdvertising *publicidad = BLEDevice::getAdvertising();
  publicidad->addServiceUUID(UUID_SERVICIO);
  publicidad->setScanResponse(true);
  BLEDevice::startAdvertising();
}

// ======================= COMANDOS SERIAL =======================
void procesarComando(String cmd) {
  cmd.trim();
  cmd.toLowerCase();
  if (cmd.length() == 0) return;

  if (cmd == "on") {
    setParpadeo(true);
  } else if (cmd == "off") {
    setParpadeo(false);
  } else if (cmd == "estado") {
    Serial.print("Parpadeo: ");
    Serial.print(parpadeoActivo ? "ENCENDIDO" : "APAGADO");
    Serial.print(" | Cliente BLE: ");
    Serial.println(clienteConectado ? "conectado" : "sin conexion");
  } else {
    Serial.println("Comandos: on | off | estado");
  }
}

void leerSerial() {
  while (Serial.available() > 0) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') {
      if (bufferSerial.length() > 0) {
        procesarComando(bufferSerial);
        bufferSerial = "";
      }
    } else if (bufferSerial.length() < 32) {
      bufferSerial += c;
    }
  }
}

// ======================= SETUP / LOOP =======================
void setup() {
  Serial.begin(115200);

  digitalWrite(PIN_LED, LOW);
  pinMode(PIN_LED, OUTPUT);

  iniciarBLE();

  Serial.print("TestBluetooth iniciado. Nombre BLE: ");
  Serial.println(NOMBRE_BLE);
  Serial.println("Comandos: on | off | estado");
}

void loop() {
  leerSerial();
  actualizarParpadeo();
}
