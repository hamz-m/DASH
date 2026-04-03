#include "BluetoothSerial.h"
#include <ESP32Servo.h>

BluetoothSerial SerialBT;
Servo myServo;

// 🔧 CONFIG
#define SERVO_PIN 13
#define CENTER_ANGLE 90
#define LEFT_ANGLE 180     
#define RIGHT_ANGLE 0

bool isConnected = false;

void setup() {
  Serial.begin(115200);

  SerialBT.begin("ESP32_NAVI");

  myServo.attach(SERVO_PIN);
  myServo.write(CENTER_ANGLE);  // start centered

  Serial.println(" ESP32 Ready");
  Serial.println(" Waiting for mobile app...");
}

void loop() {

  if (SerialBT.available()) {

    String command = SerialBT.readStringUntil('\n');
    command.trim();

    Serial.println(" Received: " + command);

    //  FIRST CONNECTION ACTION
    if (!isConnected) {
      isConnected = true;

      Serial.println(" CONNECTED TO MOBILE APP");

      // Sweep (demo motion)
      sweepServo();
    }

    //  HANDLE COMMANDS
    if (command == "LEFT") {
      turnLeft();
    }
    else if (command == "RIGHT") {
      turnRight();
    }
    else if (command == "STRAIGHT") {
      goStraight();
    }
    else if (command == "CONNECTED") {
      Serial.println(" App Connection Confirmed");
    }
  }
}


// ================= FUNCTIONS =================

//  Demo sweep
void sweepServo() {
  for (int i = 0; i <= 180; i += 5) {
    myServo.write(i);
    delay(10);
  }
  for (int i = 180; i >= 0; i -= 5) {
    myServo.write(i);
    delay(10);
  }
  myServo.write(CENTER_ANGLE);
}


//  LEFT
void turnLeft() {
  Serial.println("⬅ Turning LEFT");

  myServo.write(LEFT_ANGLE);
  delay(2000);
  myServo.write(CENTER_ANGLE);
}


//  RIGHT
void turnRight() {
  Serial.println(" Turning RIGHT");

  myServo.write(RIGHT_ANGLE);
  delay(2000);
  myServo.write(CENTER_ANGLE);
}


//  STRAIGHT
void goStraight() {
  Serial.println("⬆ STRAIGHT");

  myServo.write(CENTER_ANGLE);
}