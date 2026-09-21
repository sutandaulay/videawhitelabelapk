# Android Project (WebView wrapper)

## What you get
- Android Studio project ready to open.
- Splash screen with provided logo.
- Fullscreen mode enabled.
- Internet permission set.

## How to build APK (locally)
1. Install Android Studio (recommended) and JDK 17.
2. Open this folder as a project in Android Studio.
3. Let Gradle sync (it will download required SDK components).
4. Run `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
5. The generated APK will be in `app/build/outputs/apk/`.

## If you have no Android Studio
- You can use a CI (GitHub Actions) to build, or use a machine with Android SDK + Gradle.
- Building requires Android SDK components which are not available in this environment.

## Notes
- For Play Store upload, generate an **AAB** and sign it with your keystore.
- If the website enforces additional headers or auth, you may need to implement custom WebView client code.

