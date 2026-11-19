plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 版本号集中在这里：每次改动上调 patch（0.7.31 → 0.7.32），构建产出的文件名自动带上它。
val appVersionCode = providers.gradleProperty("appVersionCode").orElse("49").get().toInt()
val appVersionName = providers.gradleProperty("appVersionName").orElse("0.7.38").get()

android {
    namespace = "com.hongguo.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.hongguo.android"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("appVersionCode").orElse("42").get().toInt()
        versionName = providers.gradleProperty("appVersionName").orElse("0.7.31").get()
        buildConfigField("String", "UPDATE_REPOSITORY", "\"OOIUa/hongguo-android\"")
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"https://github.com/OOIUa/hongguo-android/releases/latest/download/update.json\"")
        testInstrumentationRunner = "com.hongguo.android.HongguoTestRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }

    // 产物名带版本号：HGMini-v0.7.31-debug.apk，避免多个版本在下载目录里互相覆盖。
    @Suppress("DEPRECATION")
    applicationVariants.all {
        val buildTypeName = buildType.name
        outputs.all {
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.outputFileName =
                "HGMini-v$appVersionName-$buildTypeName.apk"
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
