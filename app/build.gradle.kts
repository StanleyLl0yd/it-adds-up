plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose)
}

android {
    namespace = "io.github.stanleyll0yd.itaddsup"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.stanleyll0yd.itaddsup"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0-alpha.1"
        val bannerId = providers.environmentVariable("YANDEX_BANNER_ID").orElse("").get()
        require(bannerId.isEmpty() || bannerId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        buildConfigField("String", "YANDEX_BANNER_ID", "\"$bannerId\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (System.getenv("RELEASE_STORE_FILE") != null) {
            create("production") {
                storeFile = file(requireNotNull(System.getenv("RELEASE_STORE_FILE")))
                storePassword = requireNotNull(System.getenv("RELEASE_STORE_PASSWORD"))
                keyAlias = requireNotNull(System.getenv("RELEASE_KEY_ALIAS"))
                keyPassword = requireNotNull(System.getenv("RELEASE_KEY_PASSWORD"))
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("production")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore)
    implementation(libs.work)
    implementation(libs.yandex.ads)
    testImplementation(libs.junit)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.test.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.test.runner)
    debugImplementation(libs.compose.test.manifest)
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
