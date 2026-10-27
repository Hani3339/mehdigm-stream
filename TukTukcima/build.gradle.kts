import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.FileCollectionDependency

version = 13

cloudstream {
    description = "مشاهدة وتحميل الأفلام والمسلسلات والأنمي من توك توك سينما"
    authors = listOf("mehdigm4life")
    language = "ar"

    // يبقى تجريبياً حتى اكتمال التحقق الحي من النطاق والسيرفرات بعد إعادة البناء.
    status = 3

    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "Anime",
        "AnimeMovie",
        "Cartoon"
    )

    iconUrl = "https://tuktukcinemas.com/wp-content/themes/TukTukCinema3/tuk.png?x50750"
}

android {
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
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
}
