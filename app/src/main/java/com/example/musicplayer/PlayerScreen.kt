package com.example.musicplayer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

// 背景の影(Compose側)で追加。View側(text_styles.xml)と同じ考え方で、
// 背景(アルバムアート/写真/パターン)が有効なときだけ影を付ける。背景無効時はnull(影なし)
private fun backgroundTextShadow(hasBackground: Boolean): Shadow? {
    return if (hasBackground) {
        Shadow(
            color = Color.Black.copy(alpha = 0.6f),
            offset = Offset(0f, 1f),
            blurRadius = 3f
        )
    } else {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onClose: () -> Unit,
    onFavoriteClick: () -> Unit,
    isFavorite: Boolean,
    onQueueClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onEqualizerClick: () -> Unit,
    lyrics: String? = null // 歌詞表示機能で追加。曲に歌詞がない場合はnull
) {
    val uiState by viewModel.uiState.collectAsState()

    // アルバムアート⇔歌詞の表示切替。曲が変わったら自動的にアルバムアート表示に戻す
    var showLyrics by remember(uiState.title, uiState.artist) { mutableStateOf(false) }

    // 背景機能(文字視認性)で追加。背景(アルバムアート/写真/パターン)が有効な場合、
    // この画面の文字・アイコンを白系に切り替えるため、colorSchemeをローカルに上書きする
    val context = LocalContext.current
    val themePrefs = remember { ThemePreferences(context) }
    val backgroundType by themePrefs.backgroundType.collectAsState(initial = "none")
    val hasBackground = backgroundType != "none"
    val screenColorScheme = if (hasBackground) {
        MaterialTheme.colorScheme.copy(
            onSurface = Color.White,
            onSurfaceVariant = Color.White.copy(alpha = 0.85f),
            onBackground = Color.White
        )
    } else {
        MaterialTheme.colorScheme
    }

    // ===== 下スライドで閉じる機能 =====
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    // このピクセル数以上下にスライドされたら「閉じる」と判断する
    val dismissThresholdPx = with(density) { 120.dp.toPx() }
    // ドラッグ量に応じて薄くする際の基準距離(この距離で最も薄くなる)
    val fadeDistancePx = with(density) { 400.dp.toPx() }

    // 中身(Column)が verticalScroll でスクロール可能なため、
    // 素朴なドラッグ検知だと「スクロール領域の外側」でしか反応しない。
    // NestedScrollConnection を使い、スクロールと協調させることで画面全体で反応するようにする。
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            // 上方向にドラッグされた場合: まだ画面が下にずれている途中なら、
            // 一覧をスクロールさせる前に、先に画面を元の位置へ戻す方を優先する
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f && offsetY.value > 0f) {
                    val newOffset = (offsetY.value + available.y).coerceAtLeast(0f)
                    val consumedY = newOffset - offsetY.value
                    scope.launch { offsetY.snapTo(newOffset) }
                    return Offset(0f, consumedY)
                }
                return Offset.Zero
            }

            // 下方向にドラッグされた場合: 一覧が既に一番上まで来ていて
            // それ以上スクロールできない分(available)を、画面全体を下げる動きに使う
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (available.y > 0f) {
                    val newOffset = offsetY.value + available.y
                    scope.launch { offsetY.snapTo(newOffset) }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            // ドラッグ操作が終わったタイミング。閉じるか、元の位置に戻すかを判定する
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offsetY.value > dismissThresholdPx) {
                    onClose()
                } else if (offsetY.value > 0f) {
                    offsetY.animateTo(0f, animationSpec = tween(AppMotion.screenMs))
                }
                return Velocity.Zero
            }
        }
    }

    // 背景が有効な場合のみ、この画面のcolorSchemeをローカルに上書きする(設定画面の各種
    // ボトムシートなど、この画面の外側は影響を受けない)
    MaterialTheme(colorScheme = screenColorScheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection)
                .offset { IntOffset(0, offsetY.value.roundToInt()) }
                .graphicsLayer {
                    alpha = 1f - (offsetY.value / fadeDistancePx).coerceIn(0f, 0.6f)
                }
        ) {
            // 背景レイヤー(なし/アルバムアート/写真/パターン)。コンテンツより先に描画することで一番奥に置く
            AppBackgroundLayer(albumArtUri = uiState.albumArtUri, modifier = Modifier.fillMaxSize())

            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    // タイトルバー自体も透明にし、背景(なし/アルバムアート/写真/パターン)が
                    // 裏に透けて見えるようにする。これによりタイトル文字の白色と背景の相性が崩れない
                    TopAppBar(
                        title = {
                            Text(
                                "再生中",
                                style = LocalTextStyle.current.copy(shadow = backgroundTextShadow(hasBackground))
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onClose) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "閉じる")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                            navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = AppSpacing.l)
                        .padding(bottom = AppSpacing.l)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    Spacer(modifier = Modifier.height(AppSpacing.m))

                    // アートの読み込み失敗はここで検知し、下の背景色の切り替えにも使う
                    var imageLoadFailed by remember(uiState.albumArtUri) { mutableStateOf(false) }

                    // アルバムアートが無い・読み込みに失敗した・歌詞表示中のいずれかの場合は
                    // メインカラーを反映した配色にする(アート表示中は画像が全面を覆うため実質見えない)
                    val useThemedBackground = showLyrics || uiState.albumArtUri == null || imageLoadFailed

                    // アルバムアート ⇔ 歌詞
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(AppShapes.medium)
                            .background(
                                if (useThemedBackground) {
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                            MaterialTheme.colorScheme.primaryContainer
                                        )
                                    )
                                } else {
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    )
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (showLyrics) {
                            if (lyrics.isNullOrBlank()) {
                                Text(
                                    text = "この曲の歌詞はありません",
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(AppSpacing.l)
                                )
                            } else {
                                Text(
                                    text = lyrics,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(20.dp)
                                )
                            }
                        } else {
                            if (uiState.albumArtUri != null && !imageLoadFailed) {
                                AsyncImage(
                                    model = uiState.albumArtUri,
                                    contentDescription = "アルバムアート",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    onError = { imageLoadFailed = true }
                                )
                            } else {
                                Icon(
                                    Icons.Filled.MusicNote,
                                    contentDescription = null,
                                    modifier = Modifier.size(96.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(AppSpacing.xl))

                    // プレイリスト追加(左)・曲名/アーティスト(中央)・お気に入り(右)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onAddToPlaylistClick) {
                            Icon(
                                Icons.Filled.PlaylistAdd,
                                contentDescription = "プレイリストへ追加",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = uiState.title,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                                style = LocalTextStyle.current.copy(shadow = backgroundTextShadow(hasBackground))
                            )
                            Spacer(modifier = Modifier.height(AppSpacing.xs))
                            Text(
                                text = uiState.artist,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                                style = LocalTextStyle.current.copy(shadow = backgroundTextShadow(hasBackground))
                            )
                        }

                        IconButton(onClick = onFavoriteClick) {
                            Icon(
                                if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = "お気に入り",
                                tint = if (isFavorite) AppColors.favorite else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(AppSpacing.l))

                    // シークバー
                    Slider(
                        value = uiState.currentPositionMs.toFloat(),
                        onValueChange = { viewModel.seekTo(it.toLong()) },
                        valueRange = 0f..(uiState.durationMs.coerceAtLeast(1L).toFloat())
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatDuration(uiState.currentPositionMs),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            style = LocalTextStyle.current.copy(shadow = backgroundTextShadow(hasBackground))
                        )
                        Text(
                            text = formatDuration(uiState.durationMs),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            style = LocalTextStyle.current.copy(shadow = backgroundTextShadow(hasBackground))
                        )
                    }

                    Spacer(modifier = Modifier.height(AppSpacing.l))

                    // 再生コントロール(前へ・再生/一時停止・次へ)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.skipToPrevious() }) {
                            Icon(
                                Icons.Filled.SkipPrevious,
                                contentDescription = "前へ",
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.width(AppSpacing.m))
                        FilledIconButton(
                            onClick = { viewModel.togglePlayPause() },
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(
                                if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = "再生/一時停止",
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(AppSpacing.m))
                        IconButton(onClick = { viewModel.skipToNext() }) {
                            Icon(
                                Icons.Filled.SkipNext,
                                contentDescription = "次へ",
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(AppSpacing.l))

                    // シャッフル・リピート・歌詞切替・イコライザー・キュー
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        IconButton(onClick = { viewModel.toggleShuffle() }) {
                            Icon(
                                Icons.Filled.Shuffle,
                                contentDescription = "シャッフル",
                                tint = if (uiState.shuffleActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { viewModel.toggleRepeat() }) {
                            Icon(
                                if (uiState.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                                contentDescription = "リピート",
                                tint = if (uiState.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showLyrics = !showLyrics }) {
                            Icon(
                                Icons.Filled.Article,
                                contentDescription = "歌詞を表示",
                                tint = if (showLyrics) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onEqualizerClick) {
                            Icon(
                                Icons.Filled.Equalizer,
                                contentDescription = "イコライザー",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onQueueClick) {
                            Icon(
                                Icons.Filled.QueueMusic,
                                contentDescription = "再生キュー",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return String.format("%d:%02d", minutes, seconds)
}