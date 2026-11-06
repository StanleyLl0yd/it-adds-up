plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.13" apply false
    id("com.diffplug.spotless") version "8.0.0"
}

spotless {
    kotlin {
        target("**/src/**/*.kt")
        ktfmt("0.59").kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts", "**/*.gradle.kts")
        ktfmt("0.59").kotlinlangStyle()
    }
}
