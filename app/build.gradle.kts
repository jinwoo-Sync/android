plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services") // Firebase 플러그인
    id("com.google.firebase.crashlytics") // 이 줄 추가!
}

android {
    signingConfigs {
        create("release") {
            keyPassword = "000000"
            storeFile = file("/home/jinwoo/Desktop/안드로이드인증키/my-release-key.jks")
            storePassword = "000000"
            keyAlias = "key0"
        }
    }

    namespace = "com.example.myapplication"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // signingConfig 제거 - buildTypes에서 개별 관리
    }

    buildTypes {
        debug {
            isDebuggable = true
            //applicationIdSuffix = ".debug"
            // debug는 기본 debug signing 사용
        }

        release {
            isMinifyEnabled = true // Production에서는 true 권장
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release") // 여기서만 적용
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true // LeakCanary BuildConfig 접근용
    }

    packaging {
        resources {
            pickFirsts += setOf(
                "**/libc++_shared.so",
                "**/libjsc.so"
            )
        }
    }
}

dependencies {
    // AndroidX 및 기본 라이브러리
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // 코루틴
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Google Play Services - Location
    implementation(libs.google.play.services.location)

    // TensorFlow Lite
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.support)
    implementation(libs.tensorflow.lite.gpu.delegate.plugin)
    implementation(libs.tensorflow.lite.gpu.api)
    implementation(libs.tensorflow.lite.api)
    implementation(libs.tensorflow.lite.gpu)
    implementation(libs.tensorflow.lite.select.tf.ops)
    implementation(libs.tensorflow.lite.metadata)

    // 기존 라이브러리에서 EmojiCompat 제외
    implementation("androidx.appcompat:appcompat") {
        exclude(group = "androidx.emoji2")
    }
    implementation("com.google.android.material:material") {
        exclude(group = "androidx.emoji2")
    }

    // 네트워킹
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.9.0")
    implementation(libs.androidx.foundation.jvmstubs)


    // 테스트 라이브러리
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Firebase BOM
    implementation(platform("com.google.firebase:firebase-bom:32.7.1"))
    // Firebase Performance Monitoring
    implementation("com.google.firebase:firebase-perf")
    // Firebase Crashlytics
    implementation("com.google.firebase:firebase-crashlytics")
    // Firebase Analytics
    implementation("com.google.firebase:firebase-analytics")

    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}