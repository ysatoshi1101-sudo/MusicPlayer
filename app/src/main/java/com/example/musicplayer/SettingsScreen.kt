package com.example.musicplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.delay

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
fun SettingsScreen(
    onClose: () -> Unit,
    playerViewModel: PlayerViewModel,
    database: AppDatabase,
    onOpenFolders: () -> Unit,
    onLibraryChanged: () -> Unit,
    currentColorKey: String,
    onColorSeedSelected: (String) -> Unit,
    currentSkinKey: String,
    onSkinSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val themePrefs = remember { ThemePreferences(context) }
    val libraryScanner = remember { LibraryScanner(context, database) }
    val scope = rememberCoroutineScope()

    val themeMode by themePrefs.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val lastSleepMinutes by themePrefs.lastSleepTimerMinutes.collectAsState(initial = 0)
    val playerUiState by playerViewModel.uiState.collectAsState()

    val currentBackgroundType by themePrefs.backgroundType.collectAsState(initial = "none")
    val currentBackgroundImagePath by themePrefs.backgroundImagePath.collectAsState(initial = null)
    val currentBackgroundPatternKey by themePrefs.backgroundPatternKey.collectAsState(initial = backgroundPatterns.first().key)
    val currentBackgroundOpacity by themePrefs.backgroundOpacity.collectAsState(initial = 0.5f)
    val currentBackgroundDarkness by themePrefs.backgroundDarkness.collectAsState(initial = 0.3f)

    // 曲情報編集機能で追加。trueの場合、アプリ内表示だけでなく実ファイルのタグも書き換える
    val currentEditOriginalFile by themePrefs.editOriginalFileEnabled.collectAsState(initial = false)

    var showThemeSheet by remember { mutableStateOf(false) }
    var showColorSheet by remember { mutableStateOf(false) }
    var showSkinSheet by remember { mutableStateOf(false) }
    var showBackgroundSheet by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var showEqualizerSheet by remember { mutableStateOf(false) }
    var showEditModeSheet by remember { mutableStateOf(false) }
    var isUpdatingLibrary by remember { mutableStateOf(false) }
    var libraryUpdateMessage by remember { mutableStateOf<String?>(null) }

    val pickAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (e: SecurityException) {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }

                    var displayName = "不明な曲"
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1 && cursor.moveToFirst()) {
                            displayName = cursor.getString(nameIndex)
                        }
                    }

                    val inserted = libraryScanner.insertManualSong(uri, displayName)
                    libraryUpdateMessage = if (inserted) {
                        onLibraryChanged()
                        "$displayName を追加しました"
                    } else {
                        "この曲はすでに追加されています"
                    }
                } catch (e: Exception) {
                    libraryUpdateMessage = "追加中にエラーが発生しました: ${e.message}"
                }
            }
        }
    }

    val pickFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val added = libraryScanner.addFolder(uri)
                libraryUpdateMessage = "${added}曲を追加しました"
                if (added > 0) onLibraryChanged()
            }
        }
    }

    // 背景用の画像選択。選んだ画像は内部ストレージにコピーして保存する(プレイリストカバー機能と同じ考え方)
    val pickBackgroundImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val savedPath = saveBackgroundImage(context, uri)
                if (savedPath != null) {
                    themePrefs.setBackgroundImagePath(savedPath)
                    themePrefs.setBackgroundType("custom_image")
                }
            }
        }
    }

    // 背景機能(文字視認性)で追加。背景(アルバムアート/写真/パターン)が有効な場合、
    // 設定画面の本体部分(下のBox)だけ文字・アイコンを白系に切り替える。
    // 各種ボトムシートはこのBoxの外側(このComposable関数の末尾)にあるため対象外のまま
    val hasBackground = currentBackgroundType != "none"
    val screenColorScheme = if (hasBackground) {
        MaterialTheme.colorScheme.copy(
            onSurface = Color.White,
            onSurfaceVariant = Color.White.copy(alpha = 0.85f),
            onBackground = Color.White
        )
    } else {
        MaterialTheme.colorScheme
    }

    MaterialTheme(colorScheme = screenColorScheme) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 背景レイヤー(なし/アルバムアート/写真/パターン)。コンテンツより先に描画することで一番奥に置く
            AppBackgroundLayer(albumArtUri = playerUiState.albumArtUri, modifier = Modifier.fillMaxSize())

            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    // タイトルバー自体も透明にし、背景(なし/アルバムアート/写真/パターン)が
                    // 裏に透けて見えるようにする。これによりタイトル文字の白色と背景の相性が崩れない
                    TopAppBar(
                        title = {
                            Text(
                                "設定",
                                style = LocalTextStyle.current.copy(shadow = backgroundTextShadow(hasBackground))
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onClose) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "戻る")
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

                val scrollState = rememberScrollState()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {

                    // 設定画面本体
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(end = 8.dp)
                    ) {

                        SectionHeader("外観", hasBackground)

                        SettingsRow(
                            title = "テーマ",
                            valueText = themeModeLabel(themeMode),
                            hasBackground = hasBackground,
                            onClick = { showThemeSheet = true }
                        )

                        SettingsRow(
                            title = "メインカラー",
                            valueText = colorSeedLabel(currentColorKey),
                            hasBackground = hasBackground,
                            onClick = { showColorSheet = true }
                        )

                        SettingsRow(
                            title = "スキン",
                            valueText = skinLabel(currentSkinKey),
                            hasBackground = hasBackground,
                            onClick = { showSkinSheet = true }
                        )

                        SettingsRow(
                            title = "背景",
                            valueText = backgroundTypeLabel(currentBackgroundType),
                            hasBackground = hasBackground,
                            onClick = { showBackgroundSheet = true }
                        )


                        SectionHeader("再生", hasBackground)

                        SettingsRow(
                            title = "スリープタイマー",
                            valueText = sleepTimerLabel(playerUiState),
                            hasBackground = hasBackground,
                            onClick = { showSleepTimerSheet = true }
                        )

                        SettingsRow(
                            title = "イコライザー",
                            valueText = if (playerUiState.equalizer.enabled) {
                                "オン"
                            } else {
                                "オフ"
                            },
                            hasBackground = hasBackground,
                            onClick = { showEqualizerSheet = true }
                        )


                        SectionHeader("編集", hasBackground)

                        SettingsRow(
                            title = "曲情報の編集方法",
                            valueText = editModeLabel(currentEditOriginalFile),
                            hasBackground = hasBackground,
                            onClick = { showEditModeSheet = true }
                        )


                        SectionHeader("ライブラリ", hasBackground)

                        SettingsRow(
                            title = "曲を追加",
                            valueText = "",
                            hasBackground = hasBackground,
                            onClick = {
                                pickAudioLauncher.launch(arrayOf("audio/*"))
                            }
                        )

                        SettingsRow(
                            title = "フォルダを追加",
                            valueText = "",
                            hasBackground = hasBackground,
                            onClick = {
                                pickFolderLauncher.launch(null)
                            }
                        )

                        SettingsRow(
                            title = "フォルダを表示",
                            valueText = "",
                            hasBackground = hasBackground,
                            onClick = onOpenFolders
                        )

                        SettingsRow(
                            title = "ライブラリを再読み込み",
                            valueText = if (isUpdatingLibrary) {
                                "更新中..."
                            } else {
                                ""
                            },
                            hasBackground = hasBackground,
                            onClick = {
                                if (!isUpdatingLibrary) {
                                    isUpdatingLibrary = true
                                    libraryUpdateMessage = null

                                    scope.launch {
                                        val added =
                                            libraryScanner.rescanAllFolders()

                                        val removed =
                                            libraryScanner.removeMissingFiles()

                                        isUpdatingLibrary = false

                                        libraryUpdateMessage =
                                            "追加: ${added}曲 / 削除: ${removed}曲"

                                        if (added > 0 || removed > 0) {
                                            onLibraryChanged()
                                        }
                                    }
                                }
                            }
                        )

                        libraryUpdateMessage?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    shadow = backgroundTextShadow(hasBackground)
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = AppSpacing.m,
                                    top = AppSpacing.xs
                                )
                            )
                        }

                        // 一番下に余白を確保
                        Spacer(
                            modifier = Modifier.height(AppSpacing.l)
                        )
                    }

                    // 右端のスクロールバー
                    SettingsVerticalScrollbar(
                        scrollState = scrollState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }
    }

    if (showThemeSheet) {
        ThemeBottomSheet(
            currentMode = themeMode,
            onDismiss = { showThemeSheet = false },
            onSelectMode = { mode ->
                scope.launch {
                    themePrefs.setThemeMode(mode)
                    applyNightMode(mode)
                }
            }
        )
    }

    if (showColorSheet) {
        ColorSeedBottomSheet(
            currentKey = currentColorKey,
            onDismiss = { showColorSheet = false },
            onSelect = { key ->
                showColorSheet = false
                onColorSeedSelected(key)
            }
        )
    }

    if (showSkinSheet) {
        SkinBottomSheet(
            currentKey = currentSkinKey,
            onDismiss = { showSkinSheet = false },
            onSelect = { key ->
                showSkinSheet = false
                onSkinSelected(key)
            }
        )
    }

    if (showBackgroundSheet) {
        BackgroundBottomSheet(
            currentType = currentBackgroundType,
            currentImagePath = currentBackgroundImagePath,
            currentPatternKey = currentBackgroundPatternKey,
            currentOpacity = currentBackgroundOpacity,
            currentDarkness = currentBackgroundDarkness,
            onDismiss = { showBackgroundSheet = false },
            onTypeSelected = { key -> scope.launch { themePrefs.setBackgroundType(key) } },
            onPickImage = { pickBackgroundImageLauncher.launch("image/*") },
            onPatternSelected = { key ->
                scope.launch {
                    themePrefs.setBackgroundPatternKey(key)
                    themePrefs.setBackgroundType("pattern")
                }
            },
            onOpacityChange = { value -> scope.launch { themePrefs.setBackgroundOpacity(value) } },
            onDarknessChange = { value -> scope.launch { themePrefs.setBackgroundDarkness(value) } }
        )
    }

    if (showSleepTimerSheet) {
        SleepTimerBottomSheet(
            initialMinutes = if (playerUiState.sleepTimerMinutes > 0) playerUiState.sleepTimerMinutes else lastSleepMinutes,
            initialEndOfTrack = playerUiState.sleepTimerEndOfTrack,
            onDismiss = { showSleepTimerSheet = false },
            onConfirm = { minutes, endOfTrack ->
                if (endOfTrack) {
                    playerViewModel.setSleepTimerEndOfTrack(true)
                } else {
                    playerViewModel.setSleepTimerMinutes(minutes)
                    scope.launch { themePrefs.setLastSleepTimerMinutes(minutes) }
                }
                showSleepTimerSheet = false
            }
        )
    }

    if (showEqualizerSheet) {
        LaunchedEffect(showEqualizerSheet) {
            playerViewModel.requestEqualizerState()
        }
        EqualizerBottomSheet(
            state = playerUiState.equalizer,
            onDismiss = { showEqualizerSheet = false },
            onEnabledChange = { playerViewModel.setEqualizerEnabled(it) },
            onBandLevelChange = { band, level -> playerViewModel.setEqualizerBandLevel(band, level) },
            onBassBoostChange = { enabled, strength -> playerViewModel.setBassBoost(enabled, strength) },
            onVirtualizerChange = { enabled, strength -> playerViewModel.setVirtualizer(enabled, strength) }
        )
    }

    if (showEditModeSheet) {
        EditModeBottomSheet(
            currentEnabled = currentEditOriginalFile,
            onDismiss = { showEditModeSheet = false },
            onSelect = { enabled ->
                showEditModeSheet = false
                scope.launch { themePrefs.setEditOriginalFileEnabled(enabled) }
            }
        )
    }
}
@Composable
private fun SettingsVerticalScrollbar(
    scrollState: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier
) {
    val canScroll = scrollState.maxValue > 0

    if (!canScroll) {
        return
    }

    // スクロール中かどうか
    val isScrolling = scrollState.isScrollInProgress

    // スクロール終了後、少し時間を置いてから非表示にする
    var scrollbarVisible by remember { mutableStateOf(false) }

    LaunchedEffect(isScrolling) {
        if (isScrolling) {
            // スクロール開始時はすぐ表示
            scrollbarVisible = true
        } else {
            // スクロール終了後もしばらく表示
            delay(800)

            // その後フェードアウト
            scrollbarVisible = false
        }
    }

    val scrollbarAlpha by animateFloatAsState(
        targetValue = if (scrollbarVisible) 0.55f else 0f,
        animationSpec = tween(
            durationMillis = 500,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        label = "settingsScrollbarAlpha"
    )

    if (scrollbarAlpha <= 0f) {
        return
    }

    // Composable側でテーマカラーを取得
    val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(
        modifier = modifier
            .width(4.dp)
    ) {
        val trackHeight = size.height

        val viewportRatio =
            size.height / (size.height + scrollState.maxValue)

        val thumbHeight = (trackHeight * viewportRatio)
            .coerceAtLeast(32.dp.toPx())

        val scrollRatio =
            if (scrollState.maxValue > 0) {
                scrollState.value.toFloat() /
                        scrollState.maxValue.toFloat()
            } else {
                0f
            }

        val thumbOffset =
            (trackHeight - thumbHeight) * scrollRatio

        drawRoundRect(
            color = scrollbarColor.copy(alpha = scrollbarAlpha),
            topLeft = Offset(
                x = 0f,
                y = thumbOffset
            ),
            size = Size(
                width = size.width,
                height = thumbHeight
            ),
            cornerRadius = CornerRadius(
                x = size.width / 2f,
                y = size.width / 2f
            )
        )
    }
}

private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.LIGHT -> "ライトモード"
    ThemeMode.DARK -> "ダークモード"
    ThemeMode.SYSTEM -> "システム設定に従う"
}

// メインカラーの選択肢一覧。順序はUI表示順。AppColorSeedをそのまま利用し、新規カラー体系は作らない
private data class ColorOption(val key: String, val label: String, val seedColor: Color?)

private val colorOptions = listOf(
    ColorOption("dynamic", "Dynamic", null),
    ColorOption("indigo", "インディゴ", AppColorSeed.INDIGO.seedColor),
    ColorOption("purple", "パープル", AppColorSeed.PURPLE.seedColor),
    ColorOption("blue", "ブルー", AppColorSeed.BLUE.seedColor),
    ColorOption("green", "グリーン", AppColorSeed.GREEN.seedColor),
    ColorOption("orange", "オレンジ", AppColorSeed.ORANGE.seedColor),
    ColorOption("red", "レッド", AppColorSeed.RED.seedColor),
    ColorOption("pink", "ピンク", AppColorSeed.PINK.seedColor)
)

private fun colorSeedLabel(key: String): String =
    colorOptions.firstOrNull { it.key == key }?.label ?: "Dynamic"

// スキンの選択肢一覧(名前と説明文のみ)。
// 角丸・影・縁取りの実際の数値はここには持たせず、Skin.ktのskinTokens()を単一の情報源として都度参照する
private data class SkinOption(val key: String, val label: String, val description: String)

private val skinOptions = listOf(
    SkinOption("standard", "Standard", "標準のデザイン"),
    SkinOption("modern", "Modern", "モダンでスタイリッシュなデザイン(角丸・影・縁取りが強め)"),
    SkinOption("glass", "Glass", "半透明でガラスのような質感のデザイン(ミニプレーヤー等が常に半透明になります)")
)

private fun skinLabel(key: String): String =
    skinOptions.firstOrNull { it.key == key }?.label ?: "Standard"

// 背景タイプの選択肢一覧
private data class BackgroundTypeOption(val key: String, val label: String)

private val backgroundTypeOptions = listOf(
    BackgroundTypeOption("none", "なし"),
    BackgroundTypeOption("album_art", "再生中のアルバムアート"),
    BackgroundTypeOption("custom_image", "写真"),
    BackgroundTypeOption("pattern", "パターン")
)

private fun backgroundTypeLabel(key: String): String =
    backgroundTypeOptions.firstOrNull { it.key == key }?.label ?: "なし"

// 曲情報編集機能で追加
private fun editModeLabel(editOriginalFile: Boolean): String =
    if (editOriginalFile) "元ファイルも更新" else "アプリ内のみ"

// 選んだ画像を内部ストレージにコピーして保存する(プレイリストカバー機能と同じ考え方)
private suspend fun saveBackgroundImage(context: Context, uri: Uri): String? {
    return withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, "app_background").apply { mkdirs() }
            val file = File(dir, "background_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}

private fun sleepTimerLabel(state: PlayerUiState): String {
    return when {
        state.sleepTimerEndOfTrack -> "現在の曲終了後"
        state.sleepTimerMinutes > 0 -> "${state.sleepTimerMinutes}分"
        else -> "オフ"
    }
}

// 背景の影(Compose側)で追加。SectionHeaderは設定画面本体(背景の上に直接乗る)からのみ
// 呼ばれるため、hasBackgroundを受け取って影の有無を切り替える
@Composable
private fun SectionHeader(text: String, hasBackground: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall.copy(
            shadow = backgroundTextShadow(hasBackground)
        ),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = AppSpacing.m, top = AppSpacing.l, bottom = AppSpacing.xs)
    )
}

// 背景の影(Compose側)で追加。SettingsRowも同様にhasBackgroundを受け取る
@Composable
private fun SettingsRow(title: String, valueText: String, hasBackground: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.m, vertical = AppSpacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge.copy(
                shadow = backgroundTextShadow(hasBackground)
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
        if (valueText.isNotBlank()) {
            Text(
                valueText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = LocalTextStyle.current.copy(shadow = backgroundTextShadow(hasBackground))
            )
            Spacer(modifier = Modifier.width(AppSpacing.xs))
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeBottomSheet(
    currentMode: ThemeMode,
    onDismiss: () -> Unit,
    onSelectMode: (ThemeMode) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = AppSpacing.l, vertical = AppSpacing.s)) {
            Text(
                "テーマ",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(AppSpacing.m))

            ThemeOptionRow("ライトモード", currentMode == ThemeMode.LIGHT) { onSelectMode(ThemeMode.LIGHT) }
            ThemeOptionRow("ダークモード", currentMode == ThemeMode.DARK) { onSelectMode(ThemeMode.DARK) }
            ThemeOptionRow("システム設定に従う", currentMode == ThemeMode.SYSTEM) { onSelectMode(ThemeMode.SYSTEM) }

            Spacer(modifier = Modifier.height(AppSpacing.l))
        }
    }
}

@Composable
private fun ThemeOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AppSpacing.s + AppSpacing.xs / 2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(AppSpacing.s))
        Text(label)
    }
}

// 曲情報編集方法の選択シート。「元ファイルも更新」を選んだ場合の注意点も併記する
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditModeBottomSheet(
    currentEnabled: Boolean,
    onDismiss: () -> Unit,
    onSelect: (Boolean) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = AppSpacing.l, vertical = AppSpacing.s)) {
            Text(
                "曲情報の編集方法",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(AppSpacing.m))

            ThemeOptionRow("アプリ内のみ(元のファイルは変更しない)", !currentEnabled) { onSelect(false) }
            ThemeOptionRow("元のファイルも書き換える", currentEnabled) { onSelect(true) }

            Spacer(modifier = Modifier.height(AppSpacing.m))

            Text(
                "「元のファイルも書き換える」を選ぶと、曲の編集画面で保存したときに、" +
                        "アプリ内の表示だけでなく端末上の音楽ファイル自体のタグ(曲名・アーティスト・アルバム)も" +
                        "書き換えます。SAFフォルダ選択で追加したフォルダの場合、書き込み権限を得るために" +
                        "「フォルダを追加」から一度フォルダを選び直す必要がある場合があります。" +
                        "書き込みに失敗した場合はアプリ内の表示のみが更新されます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(AppSpacing.l))
        }
    }
}

// メインカラー選択シート。7色+Dynamicの8択。色そのものが視覚的に分かるスウォッチ+選択中はチェックマーク表示
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorSeedBottomSheet(
    currentKey: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = AppSpacing.l, vertical = AppSpacing.s)) {
            Text(
                "メインカラー",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(AppSpacing.m))

            colorOptions.forEach { option ->
                ColorOptionRow(
                    option = option,
                    selected = option.key == currentKey,
                    onClick = { onSelect(option.key) }
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.l))
        }
    }
}

@Composable
private fun ColorOptionRow(option: ColorOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AppSpacing.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(option.seedColor ?: MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (option.seedColor == null) {
                Icon(
                    Icons.Filled.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.m))
        Text(option.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "選択中",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// スキン選択シート。角丸の大きさが分かる四角いプレビューを添えて選びやすくする
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkinBottomSheet(
    currentKey: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = AppSpacing.l, vertical = AppSpacing.s)) {
            Text(
                "スキン",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Text(
                "アプリ全体のカードの見た目が変わります(選択後、画面が再読み込みされます)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.xs),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(AppSpacing.m))

            skinOptions.forEach { option ->
                SkinOptionRow(
                    option = option,
                    selected = option.key == currentKey,
                    onClick = { onSelect(option.key) }
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.l))
        }
    }
}

@Composable
private fun SkinOptionRow(option: SkinOption, selected: Boolean, onClick: () -> Unit) {
    // プレビューの角丸は、skinTokens()(Skin.kt)の値をそのまま使う。
    // 独自の数値を持たないため、将来skinTokens()側の値だけ変えれば、このプレビューにも自動で反映される
    val previewCornerRadius = skinTokens(AppSkin.fromKey(option.key)).cardCornerRadius

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AppSpacing.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(previewCornerRadius))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Widgets,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.m))
        Column(modifier = Modifier.weight(1f)) {
            Text(option.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                option.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "選択中",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// 背景選択シート。タイプ(なし/アルバムアート/写真/パターン)+タイプごとの追加設定をまとめて1つのシートに集約する
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackgroundBottomSheet(
    currentType: String,
    currentImagePath: String?,
    currentPatternKey: String,
    currentOpacity: Float,
    currentDarkness: Float,
    onDismiss: () -> Unit,
    onTypeSelected: (String) -> Unit,
    onPickImage: () -> Unit,
    onPatternSelected: (String) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onDarknessChange: (Float) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = AppSpacing.l, vertical = AppSpacing.s)
                .fillMaxWidth()
        ) {
            Text(
                "背景",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(AppSpacing.m))

            backgroundTypeOptions.forEach { option ->
                BackgroundTypeRow(
                    label = option.label,
                    selected = option.key == currentType,
                    onClick = { onTypeSelected(option.key) }
                )
            }

            if (currentType == "custom_image") {
                Spacer(modifier = Modifier.height(AppSpacing.s))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!currentImagePath.isNullOrBlank()) {
                        AsyncImage(
                            model = Uri.fromFile(File(currentImagePath)),
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(MaterialTheme.shapes.medium),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.m))
                    }
                    OutlinedButton(onClick = onPickImage) {
                        Text(if (currentImagePath.isNullOrBlank()) "画像を選択" else "画像を変更")
                    }
                }
            }

            if (currentType == "pattern") {
                Spacer(modifier = Modifier.height(AppSpacing.s))
                PatternGrid(
                    currentKey = currentPatternKey,
                    onSelect = onPatternSelected
                )
            }

            if (currentType == "album_art" || currentType == "custom_image") {
                Spacer(modifier = Modifier.height(AppSpacing.m))
                Text(
                    "不透明度",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = currentOpacity,
                    onValueChange = onOpacityChange,
                    valueRange = 0.1f..1f
                )
                Text(
                    "暗さ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = currentDarkness,
                    onValueChange = onDarknessChange,
                    valueRange = 0f..0.8f
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.l))
        }
    }
}

@Composable
private fun BackgroundTypeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AppSpacing.s + AppSpacing.xs / 2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(AppSpacing.s))
        Text(label)
    }
}

// パターンのプリセットをグリッド(3列)で表示する
@Composable
private fun PatternGrid(currentKey: String, onSelect: (String) -> Unit) {
    val rows = backgroundPatterns.chunked(3)
    Column {
        rows.forEach { rowPatterns ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)
            ) {
                rowPatterns.forEach { pattern ->
                    PatternTile(
                        pattern = pattern,
                        selected = pattern.key == currentKey,
                        onClick = { onSelect(pattern.key) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // 3の倍数でない最後の行は、空白で埋めて幅を揃える
                repeat(3 - rowPatterns.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.s))
        }
    }
}

@Composable
private fun PatternTile(
    pattern: BackgroundPatternOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = pattern.colorsArgb.map { Color(it) }
    val brush = if (colors.size >= 2) {
        Brush.linearGradient(colors)
    } else {
        Brush.linearGradient(listOf(colors.first(), colors.first()))
    }

    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium)
                .background(brush)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                    } else {
                        Modifier
                    }
                )
        )
        Spacer(modifier = Modifier.height(AppSpacing.xs))
        Text(
            pattern.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerBottomSheet(
    initialMinutes: Int,
    initialEndOfTrack: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (minutes: Int, endOfTrack: Boolean) -> Unit
) {
    var sliderValue by remember { mutableStateOf(initialMinutes.coerceIn(0, 180).toFloat()) }
    var endOfTrackChecked by remember { mutableStateOf(initialEndOfTrack) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = AppSpacing.l, vertical = AppSpacing.s)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("スリープタイマー", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(AppSpacing.l))

            Text(
                text = if (sliderValue.toInt() == 0) "オフ" else "${sliderValue.toInt()}分",
                style = MaterialTheme.typography.headlineMedium,
                color = if (endOfTrackChecked) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(AppSpacing.m))

            Slider(
                value = sliderValue,
                onValueChange = {
                    sliderValue = it
                    if (it > 0f) endOfTrackChecked = false
                },
                valueRange = 0f..180f,
                steps = 179,
                enabled = !endOfTrackChecked
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("オフ", style = MaterialTheme.typography.bodySmall)
                Text("30分", style = MaterialTheme.typography.bodySmall)
                Text("60分", style = MaterialTheme.typography.bodySmall)
                Text("90分", style = MaterialTheme.typography.bodySmall)
                Text("120分", style = MaterialTheme.typography.bodySmall)
                Text("150分", style = MaterialTheme.typography.bodySmall)
                Text("180分", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(AppSpacing.l))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("現在の曲が終わったら停止")
                Switch(
                    checked = endOfTrackChecked,
                    onCheckedChange = { checked ->
                        endOfTrackChecked = checked
                        if (checked) sliderValue = 0f
                    }
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.l))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onDismiss) {
                    Text("キャンセル")
                }
                TextButton(onClick = { onConfirm(sliderValue.toInt(), endOfTrackChecked) }) {
                    Text("完了")
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.m))
        }
    }
}

// ===== イコライザー: プリセット定義 =====

// 標準的な5バンド(60/230/910/3600/14000Hz)を基準にしたdB値。
// 端末のバンド数が5と異なる場合は presetToBandLevels() で比例配置する
private data class EqPresetOption(val key: String, val label: String, val dbLevels: List<Int>)

private const val EQ_PRESET_CUSTOM = "custom"

private val eqPresets = listOf(
    EqPresetOption("default", "デフォルト", listOf(0, 0, 0, 0, 0)),
    EqPresetOption("bass_boost", "Bass Boost", listOf(6, 4, 0, -2, -3)),
    EqPresetOption("vocal", "Vocal", listOf(-2, -1, 3, 4, 1)),
    EqPresetOption("rock", "Rock", listOf(5, 3, -2, 2, 4)),
    EqPresetOption("pop", "Pop", listOf(-1, 2, 4, 2, -1)),
    EqPresetOption("jazz", "Jazz", listOf(3, 2, 0, 2, 3)),
    EqPresetOption("classical", "Classical", listOf(3, 2, 0, 1, 3)),
    EqPresetOption("dance", "Dance", listOf(6, 4, -1, 2, 5))
)

// プリセットのdB値(5バンド基準)を、実際の端末のバンド数・範囲に合わせて変換する
private fun presetToBandLevels(preset: EqPresetOption, numBands: Int, minLevel: Int, maxLevel: Int): List<Int> {
    if (numBands <= 0) return emptyList()
    val source = preset.dbLevels
    return List(numBands) { targetIndex ->
        val sourceIndex = if (numBands == source.size) {
            targetIndex
        } else {
            val ratio = targetIndex.toFloat() * (source.size - 1) / (numBands - 1).coerceAtLeast(1)
            ratio.roundToInt().coerceIn(0, source.size - 1)
        }
        (source[sourceIndex] * 100).coerceIn(minLevel, maxLevel)
    }
}

// 周波数から分かりやすい補足名を返す
private fun bandNickname(freqMilliHz: Int): String {
    val hz = freqMilliHz / 1000
    return when {
        hz < 150 -> "重低音"
        hz < 500 -> "低音"
        hz < 2000 -> "中音"
        hz < 6000 -> "ボーカル"
        else -> "高音"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerBottomSheet(
    state: EqualizerUiState,
    onDismiss: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onBandLevelChange: (band: Int, level: Int) -> Unit,
    onBassBoostChange: (enabled: Boolean, strength: Int) -> Unit,
    onVirtualizerChange: (enabled: Boolean, strength: Int) -> Unit
) {
    val context = LocalContext.current
    val themePrefs = remember { ThemePreferences(context) }
    val scope = rememberCoroutineScope()

    var selectedPresetKey by remember { mutableStateOf("default") }
    var hasRestoredOnce by remember { mutableStateOf(false) }

    // 端末のイコライザー情報(バンド数・範囲)が読み込まれた最初のタイミングで、
    // 前回選択していたプリセット(またはCustomの値)を復元する
    LaunchedEffect(state.numBands) {
        if (state.numBands > 0 && !hasRestoredOnce) {
            hasRestoredOnce = true
            val savedKey = themePrefs.eqPresetKey.first()
            selectedPresetKey = savedKey

            if (savedKey == EQ_PRESET_CUSTOM) {
                val savedLevelsText = themePrefs.eqCustomLevels.first()
                val savedLevels = savedLevelsText.split(",").mapNotNull { it.trim().toIntOrNull() }
                if (savedLevels.size == state.numBands) {
                    savedLevels.forEachIndexed { band, level -> onBandLevelChange(band, level) }
                }
            } else {
                val preset = eqPresets.firstOrNull { it.key == savedKey }
                if (preset != null) {
                    val levels = presetToBandLevels(preset, state.numBands, state.minLevel, state.maxLevel)
                    levels.forEachIndexed { band, level -> onBandLevelChange(band, level) }
                }
            }
        }
    }

    fun applyPreset(preset: EqPresetOption) {
        selectedPresetKey = preset.key
        val levels = presetToBandLevels(preset, state.numBands, state.minLevel, state.maxLevel)
        levels.forEachIndexed { band, level -> onBandLevelChange(band, level) }
        scope.launch { themePrefs.setEqPreset(preset.key) }
    }

    fun markAsCustomAndSave() {
        selectedPresetKey = EQ_PRESET_CUSTOM
        scope.launch {
            themePrefs.setEqPreset(EQ_PRESET_CUSTOM)
            themePrefs.setEqCustomLevels(state.bandLevels.joinToString(","))
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = AppSpacing.l, vertical = AppSpacing.s)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    Text("イコライザー", style = MaterialTheme.typography.titleLarge)
                }
                Switch(checked = state.enabled, onCheckedChange = onEnabledChange)
            }

            if (state.numBands == 0) {
                Spacer(modifier = Modifier.height(AppSpacing.m))
                Text(
                    "この端末ではイコライザーが利用できません",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(AppSpacing.l))
            } else {
                Spacer(modifier = Modifier.height(AppSpacing.m))

                // プリセット選択(横スクロールチップ)。「デフォルト」を選ぶと全バンド0dBに戻る
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                ) {
                    eqPresets.forEach { preset ->
                        EqPresetChip(
                            label = preset.label,
                            selected = selectedPresetKey == preset.key,
                            onClick = { applyPreset(preset) }
                        )
                    }
                    EqPresetChip(
                        label = "Custom",
                        selected = selectedPresetKey == EQ_PRESET_CUSTOM,
                        onClick = { /* Customはスライダーを手動調整すると自動で選択される */ }
                    )
                }

                Spacer(modifier = Modifier.height(AppSpacing.l))

                for (band in 0 until state.numBands) {
                    val freqLabel = frequencyLabel(state.centerFreqs.getOrElse(band) { 0 })
                    val nickname = bandNickname(state.centerFreqs.getOrElse(band) { 0 })
                    val level = state.bandLevels.getOrElse(band) { 0 }

                    EqBandSlider(
                        freqLabel = freqLabel,
                        nickname = nickname,
                        level = level,
                        minLevel = state.minLevel,
                        maxLevel = state.maxLevel,
                        enabled = state.enabled,
                        onLevelChange = { newLevel -> onBandLevelChange(band, newLevel) },
                        onLevelChangeFinished = { markAsCustomAndSave() }
                    )
                }
            }

            if (state.bassBoostSupported) {
                Spacer(modifier = Modifier.height(AppSpacing.m))
                EqEffectCard(
                    title = "Bass Boost",
                    description = "低音を強化します",
                    icon = Icons.Filled.Speaker,
                    enabled = state.bassBoostEnabled,
                    strength = state.bassBoostStrength,
                    onEnabledChange = { checked -> onBassBoostChange(checked, state.bassBoostStrength) },
                    onStrengthChange = { newValue -> onBassBoostChange(state.bassBoostEnabled, newValue) }
                )
            }

            if (state.virtualizerSupported) {
                Spacer(modifier = Modifier.height(AppSpacing.m))
                EqEffectCard(
                    title = "Virtualizer",
                    description = "立体音響効果を加えます",
                    icon = Icons.Filled.SurroundSound,
                    enabled = state.virtualizerEnabled,
                    strength = state.virtualizerStrength,
                    onEnabledChange = { checked -> onVirtualizerChange(checked, state.virtualizerStrength) },
                    onStrengthChange = { newValue -> onVirtualizerChange(state.virtualizerEnabled, newValue) }
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.l))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EqPresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (selected) 1.05f else 1f, label = "presetChipScale")
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
        )
    )
}

@Composable
private fun EqBandSlider(
    freqLabel: String,
    nickname: String,
    level: Int,
    minLevel: Int,
    maxLevel: Int,
    enabled: Boolean,
    onLevelChange: (Int) -> Unit,
    onLevelChangeFinished: () -> Unit
) {
    Column(modifier = Modifier.padding(vertical = AppSpacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(freqLabel, style = MaterialTheme.typography.bodySmall)
                Text(
                    nickname,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val dbText = if (level > 0) "+${level / 100}dB" else "${level / 100}dB"
            Text(
                dbText,
                style = MaterialTheme.typography.bodySmall,
                color = if (level == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
            )
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            // 中央(0dB)ラインの目印。範囲が左右対称(-15dB〜+15dB等)であることを前提にした簡易表示
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(2.dp)
                    .height(20.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Slider(
                value = level.toFloat(),
                onValueChange = { newValue -> onLevelChange(newValue.toInt()) },
                onValueChangeFinished = onLevelChangeFinished,
                valueRange = minLevel.toFloat()..maxLevel.toFloat(),
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
private fun EqEffectCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    strength: Int,
    onEnabledChange: (Boolean) -> Unit,
    onStrengthChange: (Int) -> Unit
) {
    // スキンごとの数値をLocalAppSkinTokens(Theme.ktがProvide)からそのまま読む。
    // 「Modernなら〜」のようなif/when分岐はここには一切持たない
    val tokens = LocalAppSkinTokens.current
    val borderModifier = if (tokens.cardStrokeWidth > 0.dp) {
        Modifier.border(
            width = tokens.cardStrokeWidth,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            shape = MaterialTheme.shapes.medium
        )
    } else {
        Modifier
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(borderModifier),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = tokens.cardElevation),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(AppSpacing.m)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(AppSpacing.s))
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Spacer(modifier = Modifier.height(AppSpacing.s))
            Slider(
                value = strength.toFloat(),
                onValueChange = { newValue -> onStrengthChange(newValue.toInt()) },
                valueRange = 0f..1000f,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

private fun frequencyLabel(freqMilliHz: Int): String {
    val hz = freqMilliHz / 1000
    return if (hz >= 1000) "${hz / 1000}kHz" else "${hz}Hz"
}