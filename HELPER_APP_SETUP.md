# Android Helper App Setup

The website stays the main app. The Android install is a tiny helper that
holds native push access and links back to the site through `/helper`.

## 1. Add Firebase to the Android helper

Create or reuse a Firebase project, then register an Android app with this package name:

`io.maru.helper`

Download `google-services.json` and place it here:

`android/app/google-services.json`

## 2. Add Firebase server credentials

The auth API now knows how to send FCM HTTP v1 messages when these environment variables are present:

- `FIREBASE_PROJECT_ID`
- `FIREBASE_CLIENT_EMAIL`
- `FIREBASE_PRIVATE_KEY`

You can also provide the full service account JSON in:

- `FIREBASE_SERVICE_ACCOUNT_JSON`

If you use `FIREBASE_PRIVATE_KEY`, keep newline escapes as `\n`.

## 3. Optional: keep Elevation Last.fm helper alerts alive without an open browser

If you want the Elevation helper to keep receiving Last.fm track alerts even
when no Maru tab is open, schedule this endpoint with the same `CRON_SECRET`
used elsewhere in the app:

- `GET /api/subscription?route=elevation-lastfm-notifications`
- header: `Authorization: Bearer <CRON_SECRET>`

That route refreshes the current Last.fm track through the existing auth API
and lets the enabled Elevation helper devices receive the matching push alert.

The checked-in `vercel.json` is intentionally left alone in this repo, so wire
the schedule in your live hosting dashboard or external scheduler instead of
editing the local file here.

## 4. Build and sync the helper

From the repo root:

```powershell
npm run android:sync
cd android
.\gradlew.bat assembleDebug
```

Leave `GRADLE_USER_HOME` unset while you build. That keeps Gradle using its
normal user cache under your profile instead of filling this repo with tens of
thousands of generated cache files.

The Android project is also configured to put future Gradle build output under
your local profile instead of inside `android/app/build`.

After the debug build finishes, copy the APK here so the website download
button can serve it:

- from `%LOCALAPPDATA%\MaruHelperBuild\app\outputs\apk\debug\app-debug.apk`
- to `public/downloads/maru-helper-debug.apk`

## 5. Install and open the helper

The helper stays minimal and can keep its launcher icon hidden by default.
Open it from:

- the site page at `/helper`
- the custom deep link `maruhelper://helper`
- or show its launcher icon from `/helper` when you want notification watcher apps to see it

## 6. Link it from the website

Open `/helper` in the website, sign in if needed, then use `Connect Helper App`.
