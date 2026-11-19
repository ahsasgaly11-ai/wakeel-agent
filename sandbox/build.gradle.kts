// A tiny stand-in for "some other app", installed only on the CI emulator so the agent can be tested
// end to end (tapping, typing, lists, switches, a read-only wallet) against screens that never change.
// It is not part of the Wakeel APK.
plugins {
    id("com.android.application")
}

android {
    namespace = "com.wakeel.sandbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wakeel.sandbox"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
