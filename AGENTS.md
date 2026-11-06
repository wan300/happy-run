# Android device delivery

- Install only final, signed Release APKs on connected devices. Never install Debug or instrumentation/test APKs unless the user explicitly changes this instruction.
- Keep the existing release signing identity so upgrades preserve installed data.
- Deliver a standalone APK that can also be forwarded and installed on other supported devices; do not depend on ADB, a development server, or device-specific split installation.
- Verify the APK signature, version, minimum Android version, and absence of the debuggable flag before installation. Install the same APK bytes that are delivered to the user.
- Local JVM tests may use either build variant; they do not authorize installing a test application.
