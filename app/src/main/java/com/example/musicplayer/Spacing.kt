package com.example.musicplayer

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * SD Music Player 専用の余白(Spacing)体系。
 * XML側の対応する値は values/dimens.xml を参照(同じ数値を二重管理しています)。
 */
object AppSpacing {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 16.dp
    val l: Dp = 24.dp
    val xl: Dp = 32.dp
    val screenHorizontal: Dp = 16.dp
    val cardSpacing: Dp = 12.dp
}