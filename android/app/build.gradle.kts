import org.gradle.api.tasks.bundling.Zip

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.smswebgateway.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.smswebgateway.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 本地构建：使用 AGP 自动生成的 debug keystore 签名，仅供自用侧载，不是正式发布签名。
            signingConfig = signingConfigs.getByName("debug")
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
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = false
    }
}

ksp {
    arg("room.incremental", "true")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// ---------------------------------------------------------------------------
// Root Watchdog 模块打包：把 ../root-module/ 打成 assets/root-module.zip。
// root-module 目录由另一个模块维护；目录不存在时任务被跳过（APK 内会显示“未内置 Watchdog 模块”）。
// 生成物位于 build/generated/rootModule/assets/，通过 AGP 的 generated assets 目录接入，
// 由 AGP 自动建立 merge/lint 等任务对它的依赖，不会写进源码目录。
// ---------------------------------------------------------------------------
val rootModuleDir = rootProject.projectDir.resolve("../root-module")
val packageRootModule by tasks.registering(Zip::class) {
    group = "build"
    description = "把 ../root-module/ 打包为 assets/root-module.zip（目录不存在则产出空 assets，APK 内显示未内置）"
    // 仅打包模块运行所需文件（zip 根目录即模块根目录），不含 tests/、build.sh。与 root-module/build.sh 的文件清单一致。
    // 目录不存在时 from() 没有匹配文件，Zip 任务不产出 zip（allowZip64 / 空归档会被跳过）。
    from(rootModuleDir) {
        include("module.prop", "service.sh", "watchdog.sh", "uninstall.sh", "customize.sh", "README.md")
    }
    filePermissions { unix("rwxr-xr-x") }
    archiveFileName.set("root-module.zip")
    destinationDirectory.set(layout.buildDirectory.dir("generated/rootModule/assets"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(packageRootModule, Zip::getDestinationDirectory)
    }
}
