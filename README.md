# Maru Mobile

Maru Link Service Android helper app. Built with Capacitor.

## License

**GNU General Public License v3.0 (GPL-3.0)** - See [LICENSE](LICENSE) for full text.

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.

## Building

### Prerequisites

- Java 17+
- Android Studio
- Node.js 20+
- Android SDK (API 34)

### Setup

1. Clone the repo:
```bash
git clone https://github.com/JmDemisana/maru-mobile.git
cd maru-mobile
```

2. Install dependencies:
```bash
npm install
```

3. Build web assets from the main website repo:
```bash
# In maru-website:
npm run build:helper

# Copy to mobile assets:
cp -r dist/helper-web/* maru-mobile/android/app/src/main/assets/public/
```

Or use the Capacitor sync command:
```bash
npx cap sync android
```

### Building the APK

**Debug build:**
```bash
cd android
./gradlew :app:assembleDebug
```

The debug APK will be at: `android/app/build/outputs/apk/debug/app-debug.apk`

**Release build:**
```bash
cd android
./gradlew :app:assembleRelease
```

The release APK will be at: `android/app/build/outputs/apk/release/`

Note: Release builds require a signing configuration. Create a `signing.properties` file or add your signing config to `android/app/build.gradle`.

### Running on Device/Emulator

1. Connect your device via USB and enable USB debugging, or start an emulator in Android Studio.

2. Deploy and run:
```bash
cd android
./gradlew :app:installDebug
```

Or open the project in Android Studio and run from there.

## Project Structure

- `android/` - Android native project (Capacitor)
- `helper-web/` - Web assets loaded by the Android WebView
- `capacitor.config.ts` - Capacitor configuration

## Notes

- This app uses a WebView to display the Maru helper interface
- The stem model (2stems.tflite) is downloaded on-demand from GitHub releases and stored in the app's internal storage
- The app package name is `io.maru.helper`