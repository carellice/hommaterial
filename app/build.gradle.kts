import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.hommaterial"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.hommaterial"
        minSdk = 26
        targetSdk = 36
        versionCode = 38
        versionName = "1.37"
    }

    val signingPropertiesFile = file(
        System.getenv("HOMMATERIAL_ANDROID_SIGNING_PROPERTIES")
            ?: "${System.getProperty("user.home")}/.android/hommaterial-signing/signing.properties"
    )
    val dedicatedSigning = signingPropertiesFile.isFile
    signingConfigs {
        getByName("debug") {
            // Reuse the original Mac key on Windows; never commit the keystore.
            System.getenv("HOMMATERIAL_ANDROID_KEYSTORE")?.let { storeFile = file(it) }
        }
        if (dedicatedSigning) {
            val credentials = Properties().apply {
                signingPropertiesFile.reader(Charsets.UTF_8).use { load(it) }
            }
            create("hommaterial") {
                storeFile = file(requireNotNull(credentials.getProperty("storeFile")))
                storePassword = requireNotNull(credentials.getProperty("storePassword"))
                keyAlias = requireNotNull(credentials.getProperty("keyAlias"))
                keyPassword = requireNotNull(credentials.getProperty("keyPassword"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Dedicated local key when configured; retain legacy debug signing on the Mac.
            signingConfig = signingConfigs.getByName(if (dedicatedSigning) "hommaterial" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    // The JSON of Android is only a stub on the JVM; the fake TV of the LG tests is a local socket.
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
