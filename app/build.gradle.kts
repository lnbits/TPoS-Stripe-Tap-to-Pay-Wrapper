plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.lnbitstaptopay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.lnbitstaptopay"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        create("staging") {
            initWith(getByName("debug"))
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Java/Kotlin toolchain
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE*,NOTICE*}"
        }
    }
}

dependencies {
    // Google Play services base (eligibility dialogs, etc.)
    implementation("com.google.android.gms:play-services-base:18.4.0")

    // ZXing (QR)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.1")

    // TWA helper
    implementation("com.google.androidbrowserhelper:androidbrowserhelper:2.6.2")

    // Stripe Terminal (Tap to Pay)
    implementation("com.stripe:stripeterminal-taptopay:5.1.1")
    implementation("com.stripe:stripeterminal-core:5.1.1")

    // HTTP + JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    // AndroidX core/Activity
    implementation(libs.androidx.core.ktx)                 // you already have this alias
    implementation("androidx.activity:activity-ktx:1.9.2") // explicit to avoid missing catalog alias

    // Tests
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
