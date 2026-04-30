# Maru Link Service

Android helper app for the Maru ecosystem. Provides native Android integration for notifications, audio streaming, and device communication.

## Downloads

Get the latest APK from the [Releases page](https://github.com/JmDemisana/maru-mobile/releases).

## Features

- **Notification Handling** - Receives and displays push notifications from Maru services
- **Marucast Control** - Audio streaming and karaoke functionality
- **Native Bridge** - Provides Android-specific features to web applets
- **Last.fm Integration** - Scrobbles and now-playing updates

## Building Locally

### Prerequisites
- Android Studio / Android SDK
- Java 17+
- Node.js 20+

### Setup

1. **Build web assets** (from main maru-website repo):
   ```bash
   npm run build:helper
   npx cap sync android
   ```

2. **Copy web assets** to this repo's `android/app/src/main/assets/public/`

3. **Build APK**:
   ```bash
   # Debug build
   ./android/gradlew :app:assembleDebug

   # Release build (requires signing config)
   ./android/gradlew :app:assembleRelease
   ```

## CI/CD

GitHub Actions automatically builds the APK when a git tag is pushed:

```bash
git tag v1.5.42
git push origin v1.5.42
```

## License

Copyright © 2026 Maru. All rights reserved.