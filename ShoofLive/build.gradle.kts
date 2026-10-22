plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

version = 2

cloudstream {
    description = "شوف لايف (ShoofLive) — مسلسلات وأفلام وحلقات مترجمة مع كل السيرفرات وروابط التحميل."
    authors = listOf("mehdigm4life")
    language = "ar"

    status = 1

    tvTypes = listOf(
        "TvSeries",
        "Movie"
    )

    iconUrl = "https://shooflive.watch/wp-content/uploads/2026/09/favicon.png"
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

android {
    namespace = "com.shooflive"
    compileSdk = 34
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
            freeCompilerArgs.addAll(
                "-XXLanguage:+BreakContinueInInlineLambdas",
                "-Xskip-metadata-version-check"
            )
        }
    }
}

dependencies {
    val cloudstream by configurations
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.room:room-ktx:2.8.0")
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
