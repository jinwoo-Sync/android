plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
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
    
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        animationsDisabled = true
    }

    buildTypes {
        debug {
            isDebuggable = true
            //applicationIdSuffix = ".debug"
            buildConfigField("boolean", "PERFETTO_TRACING_ENABLED", "true")
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
            buildConfigField("boolean", "PERFETTO_TRACING_ENABLED", "false")
        }

        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isDebuggable = true
            applicationIdSuffix = ""
            buildConfigField("boolean", "PERFETTO_TRACING_ENABLED", "true")
            matchingFallbacks.add("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java")
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

    // Google Play Services - Location & Maps
    implementation(libs.google.play.services.location)
    implementation("com.google.android.gms:play-services-maps:18.2.0")

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


    //  추가: UI 성능 최적화
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // 네트워킹
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-moshi:3.0.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.2")
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.2")

    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Apache Commons Math (GPS 필터링용)
    implementation("org.apache.commons:commons-math3:3.6.1")

    implementation(libs.androidx.foundation.jvmstubs)


    // 테스트 라이브러리
    testImplementation(libs.junit)
    testImplementation("org.mockito:mockito-core:5.5.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.20")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.fragment:fragment-testing:1.6.2")
    androidTestImplementation("androidx.benchmark:benchmark-junit4:1.2.2")
    androidTestImplementation("org.mockito:mockito-android:5.5.0")

    // Firebase BOM (benchmark 제외)
    "debugImplementation"(platform("com.google.firebase:firebase-bom:32.7.1"))
    "releaseImplementation"(platform("com.google.firebase:firebase-bom:32.7.1"))
    
    // Firebase Performance Monitoring (benchmark 제외)
    "debugImplementation"("com.google.firebase:firebase-perf")
    "releaseImplementation"("com.google.firebase:firebase-perf")
    
    // Firebase Crashlytics (benchmark 제외)
    "debugImplementation"("com.google.firebase:firebase-crashlytics")
    "releaseImplementation"("com.google.firebase:firebase-crashlytics")
    
    // Firebase Analytics (benchmark 제외)
    "debugImplementation"("com.google.firebase:firebase-analytics")
    "releaseImplementation"("com.google.firebase:firebase-analytics")

    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}