# 🚗 ADAS Map App

Lightweight **Advanced Driver Assistance System (ADAS)** mobile application developed in **Kotlin** for Android.
This app visualizes real-time vehicular data using **Cooperative Awareness Messages (CAMs)** over a VANET environment and displays it on an interactive map powered by OpenStreetMap.

---
## 🚧 Project Status

This project is currently under active development as part of a Computer Science and Telecommunications Engineering capstone (PIC) at Instituto Superior Técnico.

Features and documentation are evolving continuously.
---
## 📌 Overview

This project was developed as part of the final project (PIC) for the **Licenciatura em Engenharia de Telecomunicações e Informática (LETI)** at Instituto Superior Técnico (IST).

The goal is to implement a **lightweight ADAS system** that leverages **vehicular communication (VANETs)** to improve road awareness and assist drivers through real-time information.

The mobile app acts as the **visual and interactive layer** of the system.

---

## 🧠 System Architecture

The current system is centered around vehicular communication (V2V), where the Android device acts purely as a visualization interface connected to a host OBU:

```
     +-------------+      +-------------+      +-------------+
     |     RSU     | <--> |     OBU     | <--> |     OBU     |
     |             |      | (Vehicle A) |      | (Vehicle B) |
     +-------------+      +-------------+      +-------------+
                                |
                                | Ethernet
                                v
                       +---------------------+
                       |     Android App     |
                       |    (ADAS Display)   |
                       +---------------------+
```
---

### Components

* **OBU (On-Board Unit)**
  Installed in vehicles, responsible for generating and broadcasting CAM messages.
  Forwarding relevant data to the connected device


* **RSU (Road-Side Unit)**
  Infrastructure nodes that relay or broadcast vehicular data.

* **Android App (this repository)**
  Receives CAM data and provides:

  * Real-time visualization
  * Driver assistance insights
  * Map-based interaction
---

## 📡 Cooperative Awareness Messages (CAM)

CAMs are standardized messages defined in ETSI C-ITS used to share real-time information between ITS stations.

### Data includes:

* Vehicle ID
* GPS position (latitude, longitude)
* Speed
* Heading
* Timestamp

### 📦 Example (simplified)

```json
{
  "stationId": 12345,
  "latitude": 38.7376,
  "longitude": -9.3031,
  "speed": 13.5,
  "heading": 270
}
```
---

## 📱 Mobile Application

### ✨ Features

* 🗺️ Real-time vehicle visualization on map
* 🚗 Tracking nearby vehicles via CAM messages
* ⚠️ Basic ADAS warnings (e.g., proximity alerts)
* 🔄 Live updates from vehicular network
* 📍 GPS-based positioning

### 🗺️ Map Integration

This app uses **OpenStreetMap** as its mapping provider:

* Open-source and customizable
* Suitable for embedded and research applications

---

## ⚙️ Setup & Installation

### 📋 Requirements

* Android Studio
* Android device or emulator
* Network access to CAM data source

### 🚀 Steps

1. Clone the repository:

   ```bash
   git clone https://github.com/KyNixx/AdasMapApp.git
   ```

2. Open in Android Studio

3. Build and run on a device

---

## 🔌 Integration with VANET System

The app expects to receive CAM data via a network interface.

### 📡 Communication 

* Protocol: UDP
* Port: `5000`
* Format: JSON

### Data Flow

1. **OBU** generates its own CAM messages (*own CAMs*) containing the vehicle's state

2. The same OBU also receives CAM messages from nearby vehicles (*out CAMs*) via the VANET

3. The tablet is directly connected to the OBU via Ethernet, which forwards both message types to the Android app:

   * 🟢 Own CAMs (from the host vehicle)
   * 🔵 Out CAMs (from surrounding vehicles)

   The app then parses this data and maintains a local representation of:

   * The host vehicle
   * Nearby vehicles

4. All vehicles are **rendered in real-time on the map**, enabling situational awareness and ADAS features

   * Nearby vehicles

5. All vehicles are **rendered in real-time on the map**, enabling situational awareness and ADAS features


## Hardware Context (High-Level)

This project is designed to operate with real vehicular communication hardware:

* OBUs (On-Board Units)
* RSUs (Road-Side Units)
* ETSI C-ITS protocol stack
* Deployment at **IST Taguspark testbed**

The app itself is hardware-agnostic and only depends on receiving properly formatted CAM data.

---

## 📚 Project Context

* 🎓 Course: LETI – Instituto Superior Técnico
* 📍 Campus: IST Taguspark
* 🧪 Project: Final Project (PIC)
* 👥 Team: 2 students

Focus areas:

* Vehicular Networks (VANETs)
* Intelligent Transportation Systems (ITS)
* Mobile application development
* Real-world system integration

---

## 🚧 Future Improvements

* 🚨 Advanced collision detection algorithms
* 📊 Traffic analytics
* 🌐 Integration with cloud services
* 📡 Support for additional ITS message types (DENM, etc.)

---

## 🤝 Contributing

This repository is part of an academic project, but suggestions and improvements are welcome.

---

## 📄 License

This project is licensed under the terms of the MIT License (or your chosen license).

See the `LICENSE` file for more details.

---

## 🗺️ OpenStreetMap Attribution

This project uses data from OpenStreetMap.

© OpenStreetMap contributors

The map data is available under the Open Database License (ODbL):
https://opendatacommons.org/licenses/odbl/1-0/

You are free to use the data, provided that you give appropriate credit to OpenStreetMap and its contributors, and you share any derived database under the same license.

---

## 📷 Screenshots (optional)

*Add screenshots of the app here*

---

## 🧑‍💻 Authors

* Francisco Cardoso
* Diogo Folião

---

## ⭐ Acknowledgments

* Instituto Superior Técnico (IST)
* VANET / C-ITS research community
* OpenStreetMap contributors

---
