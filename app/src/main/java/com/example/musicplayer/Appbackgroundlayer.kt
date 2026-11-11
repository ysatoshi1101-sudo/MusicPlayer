package com.example.musicplayer

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * アプリ共通の背景レイヤー。PlayerScreen・SettingsScreenなど、Composeで描画される
 * 画面の一番奥(コンテンツより背後)に置いて使う。
 *
 * 背景タイプが「なし」の場合は何も描画しない(呼び出し側の通常の背景色がそのまま見える)。
 *
 * @param albumArtUri 背景タイプが「アルバムアート」のときに使う、現在再生中の曲のジャケット画像。
 *                    呼び出し側(PlayerScreen/SettingsScreen)がPlayerViewModelから渡す。
 */
@Composable
fun AppBackgroundLayer(
    albumArtUri: Uri?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val themePrefs = remember { ThemePreferences(context) }

    // 白フラッシュ対策で追加。collectAsStateの初期値を"none"固定にすると、背景が有効な場合でも
    // 実際の値が読み込まれるまでの最初の1フレームだけ「なし」として描画され、
    // その直後に本来の背景へ切り替わる白フラッシュが発生してしまう。
    // DataStoreは2回目以降メモリキャッシュされ実際のブロック時間はごく短いため、
    // MainActivity.ktの起動時読み込みと同じ考え方でここも同期的に初期値を読み込む
    val initialBackgroundType = remember { runBlocking { themePrefs.backgroundType.first() } }
    val backgroundType by themePrefs.backgroundType.collectAsState(initial = initialBackgroundType)

    val customImagePath by themePrefs.backgroundImagePath.collectAsState(initial = null)
    val patternKey by themePrefs.backgroundPatternKey.collectAsState(initial = backgroundPatterns.first().key)
    val opacity by themePrefs.backgroundOpacity.collectAsState(initial = 0.5f)
    val darkness by themePrefs.backgroundDarkness.collectAsState(initial = 0.3f)

    when (backgroundType) {
        "album_art" -> {
            if (albumArtUri != null) {
                PhotoBackground(uri = albumArtUri, opacity = opacity, darkness = darkness, modifier = modifier)
            }
        }
        "custom_image" -> {
            val path = customImagePath
            if (!path.isNullOrBlank()) {
                PhotoBackground(uri = Uri.fromFile(File(path)), opacity = opacity, darkness = darkness, modifier = modifier)
            }
        }
        "pattern" -> {
            PatternBackground(patternKey = patternKey, modifier = modifier)
        }
        else -> {
            // 「なし」: 何も描画しない。呼び出し側の通常の背景色がそのまま見える
        }
    }
}

@Composable
private fun PhotoBackground(uri: Uri, opacity: Float, darkness: Float, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .alpha(opacity),
            contentScale = ContentScale.Crop
        )
        // 暗さの調整: 黒いオーバーレイを重ねて、画像が明るくても文字が読めるようにする
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = darkness))
        )
    }
}

@Composable
private fun PatternBackground(patternKey: String, modifier: Modifier) {
    val pattern = backgroundPatternByKey(patternKey)
    val colors = pattern.colorsArgb.map { Color(it) }

    val backgroundModifier = if (colors.size >= 2) {
        Modifier.background(Brush.linearGradient(colors))
    } else {
        Modifier.background(colors.first())
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(backgroundModifier)
    )
}