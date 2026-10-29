plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.green1052.dcrefresher.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.green1052.dcrefresher.mobile"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // ponytail: debug key for release, add a signingConfig before publishing
            signingConfig = signingConfigs.getByName("debug")
            // The hooked app is an ARM app; x86 builds of DexKit only serve the emulator (debug builds, VerifyPatches).
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures.compose = true

    packaging.resources.merges += "META-INF/xposed/*"
    // Licence texts, library version stamps and Kotlin reflection metadata: nothing here reads them at run time.
    packaging.resources.excludes += listOf("META-INF/**/LICENSE*", "META-INF/*.version", "kotlin/**", "DebugProbesKt.bin")
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.dexkit)

    // The settings screen: Compose with Miuix (HyperOS-style components).
    implementation(platform(libs.compose.bom))
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.activity.compose)

    // Offline check of every patch against the real target APK, see VerifyPatches.
    // compileOnly isn't on the test classpath; the fake XposedInterface needs the API at run time.
    androidTestImplementation(libs.libxposed.api)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
