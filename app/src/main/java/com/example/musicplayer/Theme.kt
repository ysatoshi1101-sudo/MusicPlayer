package com.example.musicplayer

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val AccentGreen = Color(0xFF1DB954)

/**
 * 設定画面から選べる固定メインカラー。
 * DataStoreにはこのenumではなく文字列キー("indigo"等)で保存するため、
 * fromKey/toKeyで相互変換する(MainActivity・SettingsScreen双方から利用)。
 */
enum class AppColorSeed(val seedColor: Color) {
    INDIGO(Color(0xFF3F51B5)),
    PURPLE(Color(0xFF9C27B0)),
    BLUE(Color(0xFF2196F3)),
    GREEN(Color(0xFF4CAF50)),
    ORANGE(Color(0xFFFF9800)),
    RED(Color(0xFFF44336)),
    PINK(Color(0xFFE91E63));

    companion object {
        // "dynamic"または未知の値の場合はnull(=Dynamic Color優先)を返す
        fun fromKey(key: String): AppColorSeed? = when (key) {
            "indigo" -> INDIGO
            "purple" -> PURPLE
            "blue" -> BLUE
            "green" -> GREEN
            "orange" -> ORANGE
            "red" -> RED
            "pink" -> PINK
            else -> null
        }
    }
}

// AppColorSeed(nullを含む)をDataStore保存用の文字列キーに変換する
fun AppColorSeed?.toKey(): String = when (this) {
    AppColorSeed.INDIGO -> "indigo"
    AppColorSeed.PURPLE -> "purple"
    AppColorSeed.BLUE -> "blue"
    AppColorSeed.GREEN -> "green"
    AppColorSeed.ORANGE -> "orange"
    AppColorSeed.RED -> "red"
    AppColorSeed.PINK -> "pink"
    null -> "dynamic"
}

private fun buildSeededColorScheme(seed: AppColorSeed, useDarkTheme: Boolean): ColorScheme {
    return if (useDarkTheme) {
        darkColorScheme(primary = seed.seedColor)
    } else {
        lightColorScheme(primary = seed.seedColor)
    }
}

/**
 * アプリ全体のCompose用テーマ。
 *
 * @param colorSeed 固定メインカラー。nullの場合はDynamic Color(またはデフォルト緑)を使用する。
 *                  MainActivity.kt側で、保存済みのメインカラー設定から都度渡される。
 * @param skin スキン(見た目の雰囲気)。skinTokens()で数値化され、LocalAppSkinTokens経由で
 *             配下のComposable全体に配布される。MainActivity.kt側で、保存済みのスキン設定から都度渡される。
 */
@Composable
fun MusicPlayerTheme(
    colorSeed: AppColorSeed? = null,
    skin: AppSkin = AppSkin.STANDARD,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val themePrefs = remember { ThemePreferences(context) }
    val themeMode by themePrefs.themeMode.collectAsState(initial = ThemeMode.SYSTEM)

    val systemDark = isSystemInDarkTheme()
    val useDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDark
    }

    val colorScheme = when {
        colorSeed != null -> buildSeededColorScheme(colorSeed, useDarkTheme)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && useDarkTheme ->
            dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)
        useDarkTheme -> darkColorScheme(primary = AccentGreen)
        else -> lightColorScheme(primary = AccentGreen)
    }

    // スキンの数値表を1回だけ計算し、配下のComposable全体にCompositionLocalとして配布する。
    // 各Composableは「スキンの種類」を意識せず、この数値をそのまま読むだけでよい
    val skinTokensValue = skinTokens(skin)

    CompositionLocalProvider(LocalAppSkinTokens provides skinTokensValue) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MusicPlayerTypography,
            shapes = skinShapes(skinTokensValue),
            content = content
        )
    }
}

object AppColors {
    val favorite: Color = Color(0xFFFFC107)
    val success: Color = Color(0xFF4CAF50)
}