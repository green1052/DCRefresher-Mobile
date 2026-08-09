import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val props = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.green1052.dcrefresher"
    compileSdk {
        version = release(37)
    }

    signingConfigs {
        create("release") {
            storeFile = props["storeFile"]?.toString()?.let { file(it) }
            storePassword = props["storePassword"]?.toString()
            keyAlias = props["keyAlias"]?.toString()
            keyPassword = props["keyPassword"]?.toString()
        }
    }

    defaultConfig {
        applicationId = "com.green1052.dcrefresher"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.libxposed.service)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    compileOnly(libs.libxposed.api)
}