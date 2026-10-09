plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.xiaomi.fixnotification"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.app.limi"
        minSdk = 26
        targetSdk = 34
        versionCode = 1332
        versionName = "1.3.3.2.ntd"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    applicationVariants.all {
        val variant = this
        if (buildType.name == "release") {
            assembleProvider.configure {
                doLast {
                    val apkFile = file("build/outputs/apk/release/app-release.apk")
                    if (apkFile.exists()) {
                        val targetDir = File("C:/Users/duyih/Downloads/Bộ công cụ LIMI")
                        if (!targetDir.exists()) targetDir.mkdirs()
                        val vName = variant.versionName ?: "1.2.8.5.ntd"
                        val formattedName = if (vName.endsWith(".apk", ignoreCase = true)) vName else "$vName.apk"
                        val versionApkName = if (formattedName.startsWith("Limi-v", ignoreCase = true)) formattedName else "Limi-v$formattedName"
                        
                        // Copy chính xác theo định dạng Limi-v<versionName>.apk (vd: Limi-v1.2.8.5.ntd.apk)
                        apkFile.copyTo(File(targetDir, versionApkName), overwrite = true)
                        apkFile.copyTo(File(targetDir, "Limi_beta.apk"), overwrite = true)
                        apkFile.copyTo(File(project.rootDir, versionApkName), overwrite = true)
                    }
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    // Shizuku API
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
