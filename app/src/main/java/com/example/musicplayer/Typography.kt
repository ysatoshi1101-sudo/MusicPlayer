package com.example.musicplayer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * SD Music Player 専用のTypography(文字サイズ体系)。
 *
 * 対応表(要望のサイズ名 → Material3のスロット):
 *   Display(28sp)    → displaySmall
 *   Headline(24sp)   → headlineSmall
 *   Title(20sp)      → titleLarge
 *   CardTitle(18sp)  → titleMedium
 *   Body(16sp)       → bodyLarge
 *   BodySmall(14sp)  → bodyMedium
 *   Label(12sp)      → labelMedium
 *   Button(14sp)     → labelLarge (Material3標準のButtonコンポーネントが自動的にこのスロットを使う)
 *
 * XML側の対応するTextAppearanceは values/text_styles.xml を参照。
 */
val MusicPlayerTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Normal),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Normal),
        labelMedium = base.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal),
        labelLarge = base.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium)
    )
}

/**
 * 名前で直接参照したい場合のショートカット。
 * 中身は MusicPlayerTypography(=MaterialTheme.typography)と同じ値を指すだけで、
 * 別のスタイルを新たに作っているわけではありません。
 *
 * 使用例: Text("再生中", style = AppTypography.title)
 */
object AppTypography {
    val display: TextStyle @Composable get() = MaterialTheme.typography.displaySmall
    val headline: TextStyle @Composable get() = MaterialTheme.typography.headlineSmall
    val title: TextStyle @Composable get() = MaterialTheme.typography.titleLarge
    val cardTitle: TextStyle @Composable get() = MaterialTheme.typography.titleMedium
    val body: TextStyle @Composable get() = MaterialTheme.typography.bodyLarge
    val bodySmall: TextStyle @Composable get() = MaterialTheme.typography.bodyMedium
    val label: TextStyle @Composable get() = MaterialTheme.typography.labelMedium
    val button: TextStyle @Composable get() = MaterialTheme.typography.labelLarge
}