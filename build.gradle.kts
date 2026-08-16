plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.compose.compiler) apply false
}

gradle.afterProject {
    try {
        println("AGP_VER=" + com.android.Version.ANDROID_GRADLE_PLUGIN_VERSION)
    } catch (t: Throwable) {
    }
}
