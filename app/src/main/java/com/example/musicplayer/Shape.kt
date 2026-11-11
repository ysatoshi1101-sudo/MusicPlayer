package com.example.musicplayer

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * SD Music Player 専用のShape(角丸)体系。
 *
 * Material3のShapesクラスは extraSmall/small/medium/large/extraLarge の5枠のみのため、
 * 要望の6種類(Small/Medium/Large/Card/Button/BottomSheet)は以下のように割り当てています。
 *   Small(8dp)                  → extraSmall
 *   Medium(16dp) / Button(16dp) → small (値が同じため共有)
 *   Card(20dp)                  → medium
 *   Large(24dp)                 → large
 *   BottomSheet(28dp)           → extraLarge
 *
 * Material3標準コンポーネント(Card, Buttonなど)は MusicPlayerShapes 経由で自動的にこの角丸を使います。
 * 名前で直接指定したい場合は AppShapes を使ってください(例: Modifier.clip(AppShapes.card))。
 */
val MusicPlayerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

object AppShapes {
    val small = RoundedCornerShape(8.dp)
    val medium = RoundedCornerShape(16.dp)
    val large = RoundedCornerShape(24.dp)
    val card = RoundedCornerShape(20.dp)
    val button = RoundedCornerShape(16.dp)
    val bottomSheet = RoundedCornerShape(28.dp)
}