# UWB Asset Management System

An Android application for Ultra-Wideband (UWB) Real-Time Location System (RTLS) evaluation and asset tracking. This app connects to DWM1001 UWB tags via Bluetooth Low Energy (BLE) and displays real-time 3D position data with Kalman filtering.

## Overview

This application serves as an evaluation tool for UWB-based real-time localization systems. It connects to a single DWM1001 UWB tag via BLE and provides:

- 3D position coordinates (X, Y, Z)
- Kalman-filtered position data for noise reduction
- Raw and filtered accelerometer data
- Device orientation (Yaw, Pitch, Roll)
- Compass direction
- Data recording capabilities for analysis

## Features

### Implemented Features

| Feature | Status | Description |
|---------|--------|-------------|
| BLE Connection | ✅ Implemented | Connect to DWM1001 tag via Bluetooth Low Energy |
| Position Display | ✅ Implemented | Real-time X, Y, Z coordinates in meters |
| Kalman Filtering | ✅ Implemented | 9-state Kalman filter for position smoothing |
| IMU Integration | ✅ Implemented | Accelerometer and orientation sensors |
| Compass | ✅ Implemented | Direction based on device heading |
| Data Recording | ✅ Implemented | Record position data at fixed positions or during movement |
| Dynamic Z-Filter | ✅ Implemented | Adaptive filtering for height changes (sitting/standing) |

### Partial / Planned Features

| Feature | Status | Notes |
|---------|--------|-------|
| Multi-tag Support | ❌ Not implemented | Currently connects to single hardcoded tag only |
| Map Visualization | ❌ Not implemented | No indoor map display in current version |
| Network Configuration | ❌ Not implemented | UWB network must be pre-configured separately |
| Background Service | ❌ Not implemented | App must be in foreground |
| Push Notifications | ❌ Not implemented | No notification system |

## Current App Flow

```
1. Launch App
       │
       ▼
2. Grant Bluetooth Permissions (BLE scan/connect)
       │
       ▼
3. App Auto-Scans for DWM1001 Tag (hardcoded MAC)
       │
       ▼
4. Tag Connected ──► "Connect" button → "Start" button
       │
       ▼
5. User clicks "Start"
       │
       ▼
       ├─► Option A: Regular Data Transfer
       │         └── Displays live position, acceleration, orientation
       │
       └─► Option B: Recording Mode
                 ├── Fixed Position Recording (with X,Y,Z input, direction, time period)
                 └── Movement Recording (continuous logging)
```

## Project Structure

```
app/src/main/
├── java/com/tuangiavu/uwbassetmanagement/
│   ├── model/                 # Data layer
│   │   ├── BluetoothService.kt      # BLE connection to DWM1001
│   │   ├── ModelImpl.kt             # Model implementation
│   │   ├── BluetoothCallbacks.kt   # BLE callback interfaces
│   │   └── Observable.kt            # Observer pattern
│   │
│   ├── presenter/             # Business logic
│   │   ├── PresenterImpl.kt         # Main presenter
│   │   ├── BluetoothBroadcastReceiver.kt
│   │   ├── positioning/
│   │   │   ├── PositioningImpl.kt   # Position calculation
│   │   │   ├── KalmanFilterImpl.kt # Kalman filter (9-state)
│   │   │   ├── ByteArrayToLocationDataConverter.kt
│   │   │   ├── IMU.kt              # Accelerometer/Orientation
│   │   │   └── LocationData.kt
│   │   └── recording/
│   │       ├── RecordingImpl.kt     # Data recording logic
│   │       ├── FileController.kt    # File I/O
│   │       └── Timer.kt
│   │
│   ├── view/                  # UI layer
│   │   ├── ViewImpl.kt              # Main Activity
│   │   ├── MainScreenContract.kt    # MVP interface
│   │   └── RecordingFixedPositionDialog.kt
│   │
│   └── utils/
│       ├── StringUtil.kt
│       └── CompassUtil.kt
│
├── res/
│   ├── layout/
│   │   ├── view.xml                # Main UI layout
│   │   └── recording_fixed_position_dialog.xml
│   └── values/
│       ├── strings.xml
│       ├── colors.xml
│       └── styles.xml
│
└── AndroidManifest.xml
```

## Hardware / UWB Context

### Supported Hardware

- **UWB Tag**: DWM1001 Dev Kit (Decawave)
- **Anchors**: Minimum 3 DWM1001 devices configured as anchors
- **Tested Device**: Xiaomi Redmi Note 10 (Android 11)

### Requirements

- Android 10 (API 29) or higher
- Bluetooth 5.0 or higher
- Device with BLE support
- Pre-configured UWB network (minimum 3 anchors with known positions)

### Limitations

> **Important**: This app does **NOT** configure the UWB network. The DWM1001 devices must be pre-configured using:
- Decawave's RTLS Manager software
- UART shell mode via USB connection

For network configuration, refer to Decawave DWM1001 documentation.

## Requirements

### Development Requirements

- Android Studio (latest stable)
- JDK 21
- Gradle 8.x
- Android SDK 34 (compileSdk)
- Minimum SDK 29 (Android 10)

### Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Kotlin | 2.2.10 | Language |
| AndroidX Core | 1.13.1 | Android compatibility |
| EJML | 0.41 | Matrix operations for Kalman filter |
| Kotlin Coroutines | 1.8.1 | Async operations |
| AndroidX AppCompat | 1.7.0 | UI components |
| ConstraintLayout | 2.1.4 | Layout system |

## Installation / Build

### Clone and Import

```bash
# Clone the repository
git clone https://github.com/TuanNghiaV/UWB-Management-Android-App.git
cd UWB-Management-Android-App
```

### Build with Gradle

```bash
# Using gradlew (Linux/macOS)
./gradlew assembleDebug

# Using gradlew.bat (Windows)
gradlew.bat assembleDebug
```

### Install on Device

```bash
# Install debug APK
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Configure Tag MAC Address

In `BluetoothService.kt`, update the target tag MAC address:

```kotlin
private const val TAG_MAC = "C5:72:82:1D:6F:59"  // Change to your tag's MAC
```

## Usage

### Initial Setup

1. Ensure your UWB network is configured with at least 3 anchors
2. Update the `TAG_MAC` constant in `BluetoothService.kt` with your DWM1001 tag's MAC address
3. Build and install the app on your Android device
4. Grant Bluetooth permissions when prompted

### Connecting to Tag

1. Launch the app
2. Grant Bluetooth permissions (BLUETOOTH_SCAN, BLUETOOTH_CONNECT)
3. The app will automatically scan for and connect to the configured tag
4. Once connected, the "Start" button becomes available

### Data Modes

**Regular Mode**: Displays live position and sensor data
- Raw UWB position (X, Y, Z)
- Kalman-filtered position
- Accelerometer values (X, Y, Z) with color coding
- Orientation (Yaw, Pitch, Roll)
- Compass direction

**Recording Mode**:
- **Fixed Position**: Record data at a specific location for a set duration
- **Movement**: Continuous recording during movement

### Data Storage

Recorded data is saved to:
```
/Android/data/maxbauer.uwbrtls.tool/files/Documents/
```

## Limitations

- **Single Tag**: Only connects to one tag at a time (hardcoded MAC)
- **No Network Config**: Cannot configure UWB anchors through the app
- **No Multi-User**: Designed for single-device evaluation
- **No Map**: Position displayed as numeric coordinates only
- **Foreground Only**: App must be in foreground to maintain connection
- **No Persistence**: Recorded data not synced to cloud

## Development Status

| Component | Status |
|-----------|--------|
| BLE Connection | Stable |
| Position Display | Stable |
| Kalman Filter | Stable |
| IMU Sensors | Stable |
| Data Recording | Stable |
| Recording Evaluation Scripts | Available in Measurements/ folder |

### Measurement Evaluation Scripts

The `Measurements/` directory contains Python scripts for data analysis:

- `all_measurements_evaluation.py` - Batch evaluation of multiple measurements
- `fixed_point_measurements_evaluation.py` - Single file analysis
- `measurements_plot_movement.py` - Visualize movement data
- `dilution_of_precision_evaluation.py` - DOP mapping

## Roadmap

Possible future enhancements (not currently implemented):

- [ ] Multi-tag support with device list UI
- [ ] Indoor map visualization
- [ ] Network configuration through app
- [ ] Background service for continuous tracking
- [ ] Cloud data sync
- [ ] Push notifications for zone alerts



*This app was developed as an evaluation tool for UWB-based localization systems. For production use, consider additional features like multi-tag support, network management, and data persistence based on your specific requirements.*
