package com.example.musicplayer

/**
 * 背景の「パターン」機能で使う、あらかじめ用意した単色・グラデーションのプリセット一覧。
 * colorsArgb が1色なら単色、2色なら左上から右下へのグラデーションとして扱う。
 *
 * Compose(AppBackgroundLayer.kt)・View(MainActivity.kt)の両方から、この表を
 * 唯一の情報源として参照する。新しいパターンを増やす場合はここに1つ追加するだけでよい。
 */
data class BackgroundPatternOption(
    val key: String,
    val label: String,
    val colorsArgb: List<Int>
)

val backgroundPatterns = listOf(
    BackgroundPatternOption("midnight", "Midnight", listOf(0xFF121212.toInt())),
    BackgroundPatternOption("charcoal", "Charcoal", listOf(0xFF1E1E2A.toInt())),
    BackgroundPatternOption("indigo_glow", "Indigo Glow", listOf(0xFF3F51B5.toInt(), 0xFF1A1A2E.toInt())),
    BackgroundPatternOption("sunset", "Sunset", listOf(0xFFFF9800.toInt(), 0xFFE91E63.toInt())),
    BackgroundPatternOption("ocean", "Ocean", listOf(0xFF2196F3.toInt(), 0xFF0D1B2A.toInt())),
    BackgroundPatternOption("forest", "Forest", listOf(0xFF4CAF50.toInt(), 0xFF102A1E.toInt())),
    BackgroundPatternOption("berry", "Berry", listOf(0xFF9C27B0.toInt(), 0xFF2A1030.toInt())),
    BackgroundPatternOption("slate", "Slate", listOf(0xFF37474F.toInt()))
)

fun backgroundPatternByKey(key: String): BackgroundPatternOption =
    backgroundPatterns.firstOrNull { it.key == key } ?: backgroundPatterns.first()