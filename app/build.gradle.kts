plugins {
    id("com.android.application")
}

android {
    namespace = "dev.furihook"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.furihook"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "DETECTION_LOGS", providers.gradleProperty("furihookDetectionLogs").getOrElse("true"))
        }
        release {
            isMinifyEnabled = true
            buildConfigField("boolean", "DETECTION_LOGS", "false")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    compileOnly("io.github.libxposed:api:102.0.0")
}
