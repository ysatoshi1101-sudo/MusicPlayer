package com.example.musicplayer

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * アプリ全体の見た目の雰囲気(スキン)。
 * メインカラー(色)・ライト/ダーク(明暗)とは独立した「形・質感」の軸。
 */
enum class AppSkin {
    STANDARD,
    MODERN,
    GLASS;

    companion object {
        fun fromKey(key: String): AppSkin = when (key) {
            "modern" -> MODERN
            "glass" -> GLASS
            else -> STANDARD
        }
    }
}

fun AppSkin.toKey(): String = when (this) {
    AppSkin.STANDARD -> "standard"
    AppSkin.MODERN -> "modern"
    AppSkin.GLASS -> "glass"
}

/**
 * スキンごとの「見た目の数値」をまとめた設定表(Compose側)。
 *
 * 新しいスキンを追加するときに触るのは、原則として以下の4箇所だけでよい。
 * カード系のComposable・レイアウトXML自体は変更不要(すべてこの表 or テーマ属性を参照する形になっているため)。
 *
 * 1. このファイルの skinTokens() に1行追加(Compose側の値)
 * 2. values/dimens と values/themes.xml に同じ数値でXML側の定義を追加(View側の値)
 * 3. MainActivity.kt の themeOverlayForSkin() に1行追加(View側のThemeOverlay切替)
 * 4. SettingsScreen.kt の skinOptions に1行追加(設定画面の選択肢)
 *
 * Glassスキン(半透明+縁取り)は上記4箇所に加えて、ミニプレーヤー・BottomNavigation・
 * 再生キューの背景色も「背景機能の有無に関わらず常に半透明にする」ため、
 * themes.xml の ThemeOverlay.SDMusicPlayer.Skin.Glass 側で個別に上書きしている。
 */
data class AppSkinTokens(
    val cardCornerRadius: Dp,
    val cardElevation: Dp,
    val cardStrokeWidth: Dp
)

// ここが「スキンの値」の唯一の定義場所(Compose側)。新しいスキンはここに1つ追加するだけでよい
fun skinTokens(skin: AppSkin): AppSkinTokens = when (skin) {
    AppSkin.STANDARD -> AppSkinTokens(
        cardCornerRadius = 20.dp,
        cardElevation = 4.dp,
        cardStrokeWidth = 0.dp
    )
    AppSkin.MODERN -> AppSkinTokens(
        cardCornerRadius = 36.dp,
        cardElevation = 10.dp,
        cardStrokeWidth = 1.5.dp
    )
    AppSkin.GLASS -> AppSkinTokens(
        cardCornerRadius = 24.dp,
        cardElevation = 1.dp,
        cardStrokeWidth = 1.dp
    )
}

// Compose画面のどこからでも「今のスキンの数値」を参照できるようにするためのCompositionLocal。
// MusicPlayerTheme(Theme.kt)がProvideする。各Composableはこれを読むだけでよく、
// スキンの種類ごとのif/when分岐を自分で持つ必要がなくなる
val LocalAppSkinTokens = staticCompositionLocalOf { skinTokens(AppSkin.STANDARD) }

// スキンに応じてMaterial3のShapesを組み立てる。
// MusicPlayerShapes(Shape.kt)をベースに、Card系コンポーネントが使う「medium」スロットの
// 角丸だけをスキンで上書きする(それ以外のスロットは共通のまま)
fun skinShapes(tokens: AppSkinTokens): Shapes {
    return MusicPlayerShapes.copy(medium = RoundedCornerShape(tokens.cardCornerRadius))
}