import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.FileCollectionDependency
import com.lagradost.cloudstream3.gradle.tasks.CompileDexTask

version = 7

cloudstream {
    description = "سيما ناو — مشاهدة وتحميل أحدث الأفلام والمسلسلات العربية والأجنبية والتركية والآسيوية والأنمي والبرامج بجودات متعددة."
    authors = listOf("mehdigm4life")
    language = "ar"

    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AnimeMovie",
        "AsianDrama",
        "Cartoon"
    )

    iconUrl = "https://github.com/mehdigm4life/mehdigm-stream/raw/refs/heads/main/build/CimaNow.png"
}

android {
    namespace = "com.cimanow"

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// CloudStream's official .cs3 task packages only plugin classes into DEX, not implementation AARs.
// Resolve AndroidX WebKit separately, then merge its classes.jar into the plugin DEX explicitly.
val androidXWebKitAar by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val androidXWebKitClassesJar = layout.buildDirectory.file("intermediates/cs3-deps/androidx-webkit/classes.jar")
val androidXWebKitClassesDir = layout.buildDirectory.dir("intermediates/cs3-deps/androidx-webkit/classes")
val extractAndroidXWebKitAar = tasks.register<Copy>("extractAndroidXWebKitAar") {
    from(provider { zipTree(androidXWebKitAar.singleFile) }) {
        include("classes.jar")
    }
    into(androidXWebKitClassesJar.map { it.asFile.parentFile })
}
val unpackAndroidXWebKitClasses = tasks.register<Copy>("unpackAndroidXWebKitClasses") {
    dependsOn(extractAndroidXWebKitAar)
    from(provider { zipTree(androidXWebKitClassesJar.get().asFile) })
    into(androidXWebKitClassesDir)
}

tasks.named<CompileDexTask>("compileDex") {
    dependsOn(unpackAndroidXWebKitClasses)
    // CompileDexTask consumes class directories safely; passing the classes.jar directly leaves
    // lazy JarClassFileEntries pointing at a closed ZIP before D8 consumes them.
    input.from(androidXWebKitClassesDir)
}

dependencies {
    add(androidXWebKitAar.name, "androidx.webkit:webkit:1.12.1")
    compileOnly("org.mozilla:rhino:1.8.1")
    val testImplementation by configurations
    val compileOnly by configurations
    compileOnly.dependencies.withType<FileCollectionDependency>().all {
        testImplementation(files)
    }
    compileOnly.dependencies.withType<ExternalModuleDependency>().all {
        if (!(group == "com.lagradost" && name == "cloudstream3")) {
            testImplementation("$group:$name:${version.orEmpty()}")
        }
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    testImplementation("dev.whyoleg.cryptography:cryptography-core:0.5.0")
    testImplementation("dev.whyoleg.cryptography:cryptography-provider-jdk:0.5.0")
}
