plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

version = 3

cloudstream {
    description = "أنمي عرب (Anime3rb) — مشاهدة وتحميل الأنمي المترجم بجودات متعددة."
    authors = listOf("mehdigm4life")
    language = "ar"

    status = 1

    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA",
        "TvSeries",
        "Movie",
        "Cartoon"
    )

    iconUrl = "https://images.anime3rb.com/favicon/apple-touch-icon.png"
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

android {
    namespace = "com.anime3rb"
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
