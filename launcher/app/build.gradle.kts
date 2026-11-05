plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.riven.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.riven.tv"
        minSdk = 26          // Android TV 8 (Oreo)+
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Debug key so you can sideload the release build without a keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }

    // lite: no accessibility service, so Google Play Protect lets it install from a pen drive.
    // full: adds the Home-button accessibility service (installed over ADB by riven.sh).
    flavorDimensions += "edition"
    productFlavors {
        create("lite") { dimension = "edition"; buildConfigField("boolean", "HOME_SERVICE", "false") }
        create("full") { dimension = "edition"; buildConfigField("boolean", "HOME_SERVICE", "true") }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.palette:palette-ktx:1.0.0")          // dominant colour of each app's art
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")  // Compose inside the screensaver
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
}
