package dev.spofify.mobile.core.palette

internal object PaletteTestData {
    val presets = """[
        {"id":"jade","name":"Jade","dark":{"primary":"#66e3b4","onPrimary":"#08271d","secondary":"#91bfff","tertiary":"#dfbd78"},"light":{"primary":"#08704e","onPrimary":"#ffffff","secondary":"#2759a4","tertiary":"#77551b"}},
        {"id":"ember","name":"Ember","dark":{"primary":"#ffad89","onPrimary":"#31170d","secondary":"#c4b0ff","tertiary":"#a4d7bd"},"light":{"primary":"#aa431b","onPrimary":"#ffffff","secondary":"#654199","tertiary":"#23644a"}},
        {"id":"violet","name":"Violet","dark":{"primary":"#c8b5ff","onPrimary":"#22183c","secondary":"#6de2d5","tertiary":"#e8b4c5"},"light":{"primary":"#6442a5","onPrimary":"#ffffff","secondary":"#0c6d67","tertiary":"#8b465e"}}
    ]""".trimIndent()

    fun document(
        owner: String = "account", id: String = "jade", revision: String = "0",
        version: String = "1", default: String = "jade", entries: String = presets,
    ): String = """{"accountId":"$owner","paletteId":"$id","revision":$revision,"catalogVersion":$version,"defaultPaletteId":"$default","presets":$entries}"""
}
