plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.toretto5060.pickupcatapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.toretto5060.pickupcatapp"
        minSdk = 29
        targetSdk = 37
        // 沿用源项目的发布约定：命令行/CI 用 -PmcVersionName / -PmcVersionCode 注入版本号，
        // -PmcVersionSuffix 可加后缀（如 nightly）。本地不带参数时用默认值。
        versionCode = (findProperty("mcVersionCode") as String?)?.toInt() ?: 1
        versionName = ((findProperty("mcVersionName") as String?) ?: "0.0.1") +
                ((findProperty("mcVersionSuffix") as String?) ?: "")
    }

    buildTypes {
        release {
            // 独立 App 的第一版先不启用 R8，避免遗漏 keep 规则；后续可按需开启。
            isMinifyEnabled = false
            // 正式发布时可另建 signingConfigs.release（keystore.properties）；
            // 没有配置时回退 debug key，保证 release APK 可直接安装。
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    androidResources {
        localeFilters += listOf("zh", "en")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// 与源项目同样的产物命名：release 输出 PickupCat-v<versionName>.apk，一眼看出版本。
androidComponents {
    onVariants(selector().withBuildType("release")) {
        it.packaging.dex.useLegacyPackaging.set(true)
        val vName = ((project.findProperty("mcVersionName") as String?) ?: "0.0.1") +
                ((project.findProperty("mcVersionSuffix") as String?) ?: "")
        val outName = "PickupCat-v$vName.apk"
        it.outputs.forEach { output ->
            output.outputFileName.set(outName)
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.miuix.core)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.preference)
    implementation(libs.material.icons.extended)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
