plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.wuwa.config.manager"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wuwa.config.manager"
        minSdk = 24
        targetSdk = 36
        versionCode = 102
        versionName = "1.0.2"

        buildConfigField("int", "PRESET_REVISION", "1")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ENABLE_DEBUG_LOGS", "true")
        }
        release {
            isDebuggable = false
            isJniDebuggable = false
            buildConfigField("boolean", "ENABLE_DEBUG_LOGS", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)

    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.appcompat)
    implementation(libs.com.google.android.material)

    implementation(libs.dev.rikka.shizuku.api)
    implementation(libs.dev.rikka.shizuku.provider)

    implementation(libs.kotlinx.coroutines.core)

    coreLibraryDesugaring(libs.com.android.tools.desugar.jdk.libs)

    testImplementation(libs.junit)
}
