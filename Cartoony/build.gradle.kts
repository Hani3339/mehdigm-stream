version = 1

cloudstream {
    description = "مسلسلات وأفلام الكرتون والأنمي من موقع كرتوني"
    authors = listOf("mehdigm4life")
    language = "ar"

    // إصدار تجريبي إلى أن يتم اختبار البحث والمشاهدة والتنزيل من داخل CloudStream.
    status = 3

    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "TvSeries",
        "Movie",
        "Cartoon"
    )

    iconUrl = "https://cartoony.net/assets/img/logo.webp?brand=main"
}

android {
    namespace = "com.cartoony"
}
