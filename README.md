# Maru Mobile Bootstrap

Maru Link Service Android helper app. Built with Capacitor.

Bootstrap

- Prerequisites: Android Studio, Java 17+, Node.js 20+
- Build web assets from the main repo: `npm run build:helper`
- Sync Android project: `npx cap sync android`
- Copy web assets into this repo's assets directory as needed (android/app/src/main/assets/public/)
- Build APK (debug): `./android/gradlew :app:assembleDebug`
- Build APK (release): `./android/gradlew :app:assembleRelease` (requires signing)

- Optional: run the helper in an emulator or device via Android Studio/ADB
