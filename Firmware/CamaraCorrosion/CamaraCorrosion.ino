#include <OneWire.h>
#include <DallasTemperature.h>
#include <ESP32Servo.h>

// ======================= PINES =======================
const int PIN_TEMP        = 4;
const int PIN_COND        = 34;
const int PIN_REED_LLENO  = 15;
const int PIN_REED_VACIO  = 2;
const int PIN_LED_VERDE   = 12;
const int PIN_LED_ROJO    = 14;
const int PIN_BUZZER      = 13;
const int PIN_SERVO_AGUA  = 18;
const int PIN_SERVO_SAL   = 19;
const int PIN_RELE_MEZCLA = 21;
const int PIN_RELE_BOMBA  = 22;

// ======================= CONFIGURACIÓN =======================
const int  RELE_ON  = HIGH;   // Cambiar a LOW si el módulo relé es activo en bajo
const int  RELE_OFF = LOW;
const int  REED_ACTIVO = LOW; // Con INPUT_PULLUP, el contacto cerrado da LOW

const int  SERVO_CERRADO = 0;
const int  SERVO_ABIERTO = 90;

const int  UMBRAL_SAL_ON  = 1600;  // Por encima: hay salinidad (LED verde)
const int  UMBRAL_SAL_OFF = 1400;  // Por debajo: falta salinidad (LED rojo). Histéresis anti-parpadeo

const unsigned long DEBOUNCE_MS       = 30;
const unsigned long INTERVALO_COND_MS = 200;
const unsigned long INTERVALO_TEMP_MS = 1000;
const unsigned long MS_POR_UNIDAD     = 100;   // Tiempo de dosificación por unidad de "cantidad"
const int           CANTIDAD_MAX      = 100;
const unsigned long MEZCLA_MS         = 3000;
const unsigned long DURACION_MAX_S    = 86400UL * 7; // Tope de seguridad: 7 días

// ======================= OBJETOS =======================
OneWire oneWire(PIN_TEMP);
DallasTemperature sensors(&oneWire);
Servo servoAgua;
Servo servoSal;

// ======================= ESTADO =======================
struct Entrada {
  uint8_t pin;
  bool activa;
  bool lecturaPrev;
  unsigned long tCambio;
};
Entrada reedLleno = {PIN_REED_LLENO, false, false, 0};
Entrada reedVacio = {PIN_REED_VACIO, false, false, 0};

float temperatura = NAN;
int   conductividad = 0;
bool  salinidadOk = false;
unsigned long tCond = 0, tTemp = 0;

// Ensayo
bool ensayoActivo = false;
unsigned long tInicioEnsayo = 0;
unsigned long duracionEnsayoMs = 0;

// Dosificación (máquina de estados no bloqueante)
enum FaseSuministro { S_REPOSO, S_DOSIFICANDO, S_MEZCLANDO };
FaseSuministro faseSum = S_REPOSO;
unsigned long tFaseSum = 0, durFaseSum = 0;
bool sumEsAgua = true;

String bufferSerial;

// ======================= UTILIDADES =======================
void actualizarEntrada(Entrada &e) {
  bool lectura = (digitalRead(e.pin) == REED_ACTIVO);
  if (lectura != e.lecturaPrev) {
    e.lecturaPrev = lectura;
    e.tCambio = millis();
  }
  if (millis() - e.tCambio >= DEBOUNCE_MS) e.activa = lectura;
}

const char* textoNivel() {
  if (reedLleno.activa && reedVacio.activa) return "ERROR (ambos sensores activos)";
  if (reedLleno.activa) return "LLENO";
  if (reedVacio.activa) return "VACIO";
  return "INTERMEDIO";
}

bool esNumero(const String &s) {
  if (s.length() == 0) return false;
  for (unsigned int i = 0; i < s.length(); i++) {
    if (!isDigit(s[i])) return false;
  }
  return true;
}

Servo& servoDe(bool esAgua) { return esAgua ? servoAgua : servoSal; }

// ======================= SENSORES =======================
void actualizarSalinidad() {
  if (millis() - tCond < INTERVALO_COND_MS) return;
  tCond = millis();

  long suma = 0;
  for (int i = 0; i < 16; i++) suma += analogRead(PIN_COND);
  conductividad = suma / 16;

  if (conductividad > UMBRAL_SAL_ON)       salinidadOk = true;
  else if (conductividad < UMBRAL_SAL_OFF) salinidadOk = false;

  digitalWrite(PIN_LED_VERDE, salinidadOk ? HIGH : LOW);
  digitalWrite(PIN_LED_ROJO,  salinidadOk ? LOW : HIGH);
}

void actualizarTemperatura() {
  if (millis() - tTemp < INTERVALO_TEMP_MS) return;
  tTemp = millis();

  float t = sensors.getTempCByIndex(0);
  temperatura = (t == DEVICE_DISCONNECTED_C) ? NAN : t;
  sensors.requestTemperatures();  // No bloquea (setWaitForConversion(false))
}

// ======================= ENSAYO =======================
void iniciarEnsayo(unsigned long segundos) {
  if (segundos == 0 || segundos > DURACION_MAX_S) {
    Serial.println("Error: duracion invalida. Uso: inicio(segundos), ej: inicio(20)");
    return;
  }
  if (ensayoActivo) {
    Serial.println("Error: ya hay un ensayo en curso.");
    return;
  }
  if (faseSum != S_REPOSO) {
    Serial.println("Error: espere a que termine el suministro/mezcla.");
    return;
  }
  if (reedVacio.activa) {
    Serial.println("Error: tanque VACIO, no se puede iniciar (protege la bomba).");
    return;
  }
  if (!salinidadOk) {
    Serial.println("Aviso: conductividad baja, la solucion salina puede ser insuficiente.");
  }

  duracionEnsayoMs = segundos * 1000UL;
  tInicioEnsayo = millis();
  ensayoActivo = true;
  digitalWrite(PIN_RELE_BOMBA, RELE_ON);

  Serial.print("Ensayo iniciado. Duracion: ");
  Serial.print(segundos);
  Serial.println(" segundos.");
}

// hz = 0 -> sin sonido
void finalizarEnsayo(const char* motivo, int hz, int ms) {
  ensayoActivo = false;
  digitalWrite(PIN_RELE_BOMBA, RELE_OFF);
  if (hz > 0) tone(PIN_BUZZER, hz, ms);
  Serial.println(motivo);
}

void actualizarEnsayo() {
  if (!ensayoActivo) return;

  if (reedVacio.activa) {
    finalizarEnsayo("ALARMA: tanque vacio. Ensayo abortado.", 2000, 1500);
    return;
  }
  if (millis() - tInicioEnsayo >= duracionEnsayoMs) {
    finalizarEnsayo("Ensayo finalizado.", 1000, 3000);
  }
}

// ======================= SUMINISTRO (agua / sal) =======================
void iniciarSuministro(bool esAgua, int cantidad) {
  if (ensayoActivo) {
    Serial.println("Error: no se puede modificar la solucion durante el ensayo.");
    return;
  }
  if (faseSum != S_REPOSO) {
    Serial.println("Error: ya hay un suministro en curso.");
    return;
  }
  if (cantidad < 1 || cantidad > CANTIDAD_MAX) {
    Serial.print("Error: cantidad fuera de rango (1-");
    Serial.print(CANTIDAD_MAX);
    Serial.println(").");
    return;
  }
  if (esAgua && reedLleno.activa) {
    Serial.println("Error: tanque LLENO, no se agrega mas agua.");
    return;
  }

  sumEsAgua = esAgua;
  durFaseSum = (unsigned long)cantidad * MS_POR_UNIDAD;
  tFaseSum = millis();
  faseSum = S_DOSIFICANDO;
  servoDe(esAgua).write(SERVO_ABIERTO);

  Serial.print("Dosificando ");
  Serial.print(esAgua ? "agua" : "sal");
  Serial.print(" (cantidad ");
  Serial.print(cantidad);
  Serial.println(")...");
}

void pasarAMezcla() {
  servoDe(sumEsAgua).write(SERVO_CERRADO);
  digitalWrite(PIN_RELE_MEZCLA, RELE_ON);
  faseSum = S_MEZCLANDO;
  tFaseSum = millis();
  durFaseSum = MEZCLA_MS;
  Serial.println("Mezclando...");
}

void detenerSuministro() {
  servoAgua.write(SERVO_CERRADO);
  servoSal.write(SERVO_CERRADO);
  digitalWrite(PIN_RELE_MEZCLA, RELE_OFF);
  faseSum = S_REPOSO;
}

void actualizarSuministro() {
  switch (faseSum) {
    case S_DOSIFICANDO:
      if (sumEsAgua && reedLleno.activa) {
        Serial.println("Tanque lleno: se corta el suministro de agua.");
        pasarAMezcla();
      } else if (millis() - tFaseSum >= durFaseSum) {
        pasarAMezcla();
      }
      break;

    case S_MEZCLANDO:
      if (millis() - tFaseSum >= durFaseSum) {
        detenerSuministro();
        Serial.println("Suministro y mezcla completados.");
      }
      break;

    default:
      break;
  }
}

// ======================= COMANDOS =======================
void mostrarAyuda() {
  Serial.println("Comandos: inicio(segundos) | estado | nivel(agua|sal, cantidad) | detener | ayuda");
}

void mostrarEstado() {
  Serial.println("\n--- ESTADO DEL SISTEMA ---");
  Serial.print("Ensayo en curso: ");
  Serial.println(ensayoActivo ? "Si" : "No");

  if (ensayoActivo) {
    unsigned long transcurrido = millis() - tInicioEnsayo;
    unsigned long restante = (transcurrido < duracionEnsayoMs) ? (duracionEnsayoMs - transcurrido) / 1000 : 0;
    Serial.print("Tiempo restante: ");
    Serial.print(restante);
    Serial.println(" s");
  }

  Serial.print("Temperatura interna: ");
  if (isnan(temperatura)) Serial.println("sin lectura (revisar DS18B20)");
  else { Serial.print(temperatura, 1); Serial.println(" C"); }

  Serial.print("Conductividad (ADC): ");
  Serial.print(conductividad);
  Serial.println(salinidadOk ? "  -> salinidad OK" : "  -> salinidad BAJA");

  Serial.print("Nivel del tanque: ");
  Serial.println(textoNivel());

  Serial.print("Suministro: ");
  Serial.println(faseSum == S_REPOSO ? "en reposo" : (faseSum == S_DOSIFICANDO ? "dosificando" : "mezclando"));
  Serial.println("--------------------------\n");
}

void procesarComando(String cmd) {
  cmd.trim();
  cmd.toLowerCase();
  cmd.replace(" ", "");
  cmd.replace("\t", "");
  if (cmd.length() == 0) return;

  if (cmd.startsWith("inicio")) {
    String arg = cmd.substring(6);
    arg.replace("(", "");
    arg.replace(")", "");
    if (!esNumero(arg)) {
      Serial.println("Error: falta la duracion. Uso: inicio(segundos), ej: inicio(20)");
      return;
    }
    iniciarEnsayo(strtoul(arg.c_str(), nullptr, 10));

  } else if (cmd == "estado") {
    mostrarEstado();

  } else if (cmd.startsWith("nivel")) {
    String args = cmd.substring(5);
    args.replace("(", "");
    args.replace(")", "");

    String elemento = args;
    String cantStr = "10";  // cantidad por defecto si no se indica
    int coma = args.indexOf(',');
    if (coma >= 0) {
      elemento = args.substring(0, coma);
      cantStr = args.substring(coma + 1);
    }

    if (elemento != "agua" && elemento != "sal") {
      Serial.println("Error: elemento invalido. Uso: nivel(agua, 20) o nivel(sal, 5)");
      return;
    }
    if (!esNumero(cantStr)) {
      Serial.println("Error: cantidad invalida. Uso: nivel(agua, 20) o nivel(sal, 5)");
      return;
    }
    iniciarSuministro(elemento == "agua", cantStr.toInt());

  } else if (cmd == "detener") {
    bool algo = false;
    if (ensayoActivo) { finalizarEnsayo("Ensayo detenido por el usuario.", 0, 0); algo = true; }
    if (faseSum != S_REPOSO) { detenerSuministro(); Serial.println("Suministro detenido por el usuario."); algo = true; }
    if (!algo) Serial.println("No hay nada en curso.");

  } else if (cmd == "ayuda") {
    mostrarAyuda();

  } else {
    Serial.print("Comando no reconocido: ");
    Serial.println(cmd);
    mostrarAyuda();
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
    } else if (bufferSerial.length() < 64) {
      bufferSerial += c;
    }
  }
}

// ======================= SETUP / LOOP =======================
void setup() {
  Serial.begin(115200);

  // Salidas: primero nivel seguro, luego modo salida (evita pulsos al arrancar)
  digitalWrite(PIN_RELE_BOMBA, RELE_OFF);
  digitalWrite(PIN_RELE_MEZCLA, RELE_OFF);
  digitalWrite(PIN_LED_VERDE, LOW);
  digitalWrite(PIN_LED_ROJO, LOW);
  digitalWrite(PIN_BUZZER, LOW);
  pinMode(PIN_RELE_BOMBA, OUTPUT);
  pinMode(PIN_RELE_MEZCLA, OUTPUT);
  pinMode(PIN_LED_VERDE, OUTPUT);
  pinMode(PIN_LED_ROJO, OUTPUT);
  pinMode(PIN_BUZZER, OUTPUT);

  // Entradas
  pinMode(PIN_REED_LLENO, INPUT_PULLUP);
  pinMode(PIN_REED_VACIO, INPUT_PULLUP);
  reedLleno.lecturaPrev = reedLleno.activa = (digitalRead(PIN_REED_LLENO) == REED_ACTIVO);
  reedVacio.lecturaPrev = reedVacio.activa = (digitalRead(PIN_REED_VACIO) == REED_ACTIVO);

  // ADC
  analogReadResolution(12);
  analogSetPinAttenuation(PIN_COND, ADC_11db);

  // Servos
  ESP32PWM::allocateTimer(0);
  ESP32PWM::allocateTimer(1);
  servoAgua.setPeriodHertz(50);
  servoSal.setPeriodHertz(50);
  servoAgua.attach(PIN_SERVO_AGUA, 500, 2400);
  servoSal.attach(PIN_SERVO_SAL, 500, 2400);
  servoAgua.write(SERVO_CERRADO);
  servoSal.write(SERVO_CERRADO);

  // Temperatura (lectura asíncrona, no bloquea el loop)
  sensors.begin();
  sensors.setWaitForConversion(false);
  sensors.requestTemperatures();
  tTemp = millis();

  Serial.println("Camara de Corrosion iniciada.");
  mostrarAyuda();
}

void loop() {
  actualizarEntrada(reedLleno);
  actualizarEntrada(reedVacio);
  actualizarSalinidad();
  actualizarTemperatura();

  leerSerial();

  actualizarEnsayo();
  actualizarSuministro();
}