// Válvula, mezclador, bomba, motor del sinfín, buzzer y LED.
#pragma once
#include "Config.h"

bool valvulaAbierta = false;

void releValvula(bool on) { digitalWrite(PIN_RELE_VALVULA, on ? RELE_ON : RELE_OFF); valvulaAbierta = on; }
void releMezcla(bool on)  { digitalWrite(PIN_RELE_MEZCLA,  on ? RELE_ON : RELE_OFF); }
void releBomba(bool on)   { digitalWrite(PIN_RELE_BOMBA,   on ? RELE_ON : RELE_OFF); }

// Sinfín: 28BYJ-48 con ULN2003, medio paso (8 fases). Genera los pasos sin bloquear el loop. Cuenta pasos, no tiempo:
// si el loop se atrasa, no pierde pasos. Un "paso" es un medio paso; el motor da ~4096 por vuelta del eje.
const uint8_t SECUENCIA[8][4] = {{1,0,0,0},{1,1,0,0},{0,1,0,0},{0,1,1,0},{0,0,1,0},{0,0,1,1},{0,0,0,1},{1,0,0,1}};
const int PINES_MOTOR[4] = {PIN_MOTOR_IN1, PIN_MOTOR_IN2, PIN_MOTOR_IN3, PIN_MOTOR_IN4};

struct Motor {
  bool activo = false;
  bool adelante = true;
  uint8_t fase = 0;
  uint32_t restantes = 0;
  unsigned long ultimoUs = 0;
};
Motor motor;

void motorBobinas(bool encendidas) {
  for (int i = 0; i < 4; i++) digitalWrite(PINES_MOTOR[i], encendidas ? SECUENCIA[motor.fase][i] : 0);
}

void motorParar() {
  motor.activo = false;
  motor.restantes = 0;
  motorBobinas(false);   // sin corriente de retención: el ULN2003 y el motor no se calientan
}

void motorIniciar(uint32_t pasos, bool adelante = true) {
  if (pasos == 0) { motorParar(); return; }
  motor.adelante = adelante;
  motor.restantes = pasos;
  motor.activo = true;
  motor.ultimoUs = micros();
}

void motorActualizar() {
  if (!motor.activo) return;
  int n = 0;
  while (motor.restantes > 0 && (micros() - motor.ultimoUs) >= US_POR_PASO && n < 4) {
    motor.fase = (motor.fase + (motor.adelante ? 1 : 7)) & 7;
    motorBobinas(true);
    motor.ultimoUs += US_POR_PASO;
    motor.restantes--;
    n++;
  }
  if (motor.restantes == 0) motorParar();
}

void sonar(int hz, int ms) { if (hz > 0) tone(PIN_BUZZER, hz, ms); }

// Todo apagado: lo que se hace al abortar, al terminar y al arrancar.
void apagarTodo() {
  releValvula(false);
  releMezcla(false);
  releBomba(false);
  motorParar();
}

void iniciarActuadores() {
  // Primero el nivel seguro y después el modo salida: evita pulsos al arrancar.
  digitalWrite(PIN_RELE_VALVULA, RELE_OFF);
  digitalWrite(PIN_RELE_MEZCLA, RELE_OFF);
  digitalWrite(PIN_RELE_BOMBA, RELE_OFF);
  for (int p : PINES_MOTOR) digitalWrite(p, LOW);
  digitalWrite(PIN_LED_VERDE, LOW);
  digitalWrite(PIN_LED_ROJO, LOW);
  digitalWrite(PIN_BUZZER, LOW);
  digitalWrite(PIN_COND_A, LOW);
  digitalWrite(PIN_COND_B_RESIST, LOW);
  int salidas[] = {PIN_RELE_VALVULA, PIN_RELE_MEZCLA, PIN_RELE_BOMBA, PIN_MOTOR_IN1, PIN_MOTOR_IN2, PIN_MOTOR_IN3,
                   PIN_MOTOR_IN4, PIN_LED_VERDE, PIN_LED_ROJO, PIN_BUZZER, PIN_COND_A, PIN_COND_B_RESIST};
  for (int p : salidas) pinMode(p, OUTPUT);
}
