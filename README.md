# AstroCam 🌌📱

A feature-packed, manual-control Android camera application built specifically for astrophotography, long exposures, and precise low-light capture using the modern Android Camera2 API.

## ✨ Features

- **Long Exposure & Astrophotography Support:** Take full control over shutter speed, ISO, and focus distance to capture stars, nightscapes, and low-light environments.
- **RAW (DNG) Capture Support:** Save uncompressed RAW image data alongside standard JPEGs for advanced post-processing and editing.
- **Manual Controls:** Fine-tune ISO, exposure time (shutter speed), and manual focus with intuitive on-screen sliders.
- **Tap-to-Focus & Metering:** Easily tap anywhere on the viewfinder to focus and adjust metering.
- **Timer & Burst Mode:** Customizable capture countdown timers and automated burst shooting modes.
- **Background & Foreground Services:** Persistent camera service integration supporting background operation and media button triggers (wired/Bluetooth shutter remotes).
- **Modern UI & Retractable Controls:** Sleek, distraction-free viewfinder with expandable control panels designed for night use.

## 🛠️ Tech Stack & Architecture

- **Language:** Kotlin
- **APIs & Frameworks:** 
  - Android Camera2 API (`CameraDevice`, `CameraCaptureSession`, `CaptureRequest`)
  - AndroidX Architecture Components & ViewBinding
  - MediaSessionCompat (Hardware shutter button support)
- **Build System:** Gradle (Kotlin DSL) with version catalogs (`libs.versions.toml`)

## 📦 Project Structure

```text
app/src/main/java/com/cusapps/astrocam/
├── CameraService.kt       # Foreground service & background capture handling
├── CameraUtils.kt         # Camera helper functions & calculations
├── MainActivity.kt        # Primary UI & Camera2 orchestration controller
├── PhotoCaptureHelper.kt  # JPEG & RAW DNG image capture logic
├── StorageUtils.kt        # File saving & MediaStore integration
└── TapToFocusHelper.kt    # Touch-to-focus & metering implementation
```

## 🚀 Getting Started

1. **Clone the repository:**
   ```bash
   git clone https://github.com/cusapps/AstroCam.git
   ```
2. **Open in Android Studio:**
   - Open Android Studio and select **Open an Existing Project**.
   - Navigate to the cloned `AstroCam` directory.
3. **Build and Run:**
   - Connect an Android device with camera support (API 24+ recommended).
   - Click **Run 'app'** or use Gradle via terminal:
     ```bash
     ./gradlew assembleDebug
     ```

## 📄 License

Distributed under the MIT License. See `LICENSE` for more information.
