package com.example.musicplayer

import android.app.AlertDialog
import androidx.activity.addCallback
import android.content.ComponentName
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.load
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.DynamicColors
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

private sealed class OverlayScreen {
    object None : OverlayScreen()
    data class Player(val songId: Long) : OverlayScreen()
    object Settings : OverlayScreen()
}

interface LibraryRefreshable {
    fun refreshLibrary()
}

class MainActivity : AppCompatActivity() {

    private lateinit var miniPlayer: com.google.android.material.card.MaterialCardView
    private lateinit var miniPlayerTitle: TextView
    private lateinit var miniPlayerArtist: TextView
    private lateinit var miniPlayerPlayPause: ImageButton
    private lateinit var composePlayerContainer: ComposeView

    // 背景機能で追加。fragmentContainerの背後に敷く、写真/パターン用の共有ビュー
    private lateinit var backgroundImageView: ImageView
    private lateinit var backgroundDarkOverlay: View

    private lateinit var database: AppDatabase

    private val playerViewModel: PlayerViewModel by viewModels()

    var mediaController: MediaController? = null
        private set

    private var currentOverlay by mutableStateOf<OverlayScreen>(OverlayScreen.None)

    private var isSettingsOpen = false
    private var isFullPlayerOpen = false

    private var hasAttemptedRestore = false

    // メインカラー機能で追加。onCreate冒頭で読み込んだ値をComposeとView両方に使い回す
    private var themeColorKey: String = "dynamic"

    // スキン機能で追加。onCreate冒頭で読み込んだ値をComposeとView両方に使い回す
    private var themeSkinKey: String = "standard"

    // 背景機能(文字視認性)で追加。onCreate冒頭で読み込んだ「背景が有効か」を保持し、
    // 実行中に「なし」⇔「それ以外」が切り替わったときだけrecreate()するために使う
    private var themeHasBackground: Boolean = false

    // 背景機能で追加。DataStoreの最新値をここにキャッシュしておき、
    // いずれかが更新されるたびにapplyBackground()で背景ビューへ反映する
    private var bgType: String = "none"
    private var bgOpacity: Float = 0.5f
    private var bgDarkness: Float = 0.3f
    private var bgPatternKey: String = backgroundPatterns.first().key
    private var bgImagePath: String? = null

    private val playerListener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
            updateMiniPlayer()
            // 背景が「再生中のアルバムアート」の場合、曲が変わるたびに背景も更新する
            if (bgType == "album_art") applyBackground()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseIcon(isPlaying)
        }
    }

    // メインカラー文字列キー → View側ThemeOverlayのリソースID。"dynamic"の場合はnull(Overlay不使用)
    private fun themeOverlayForKey(key: String): Int? = when (key) {
        "indigo" -> R.style.ThemeOverlay_SDMusicPlayer_Indigo
        "purple" -> R.style.ThemeOverlay_SDMusicPlayer_Purple
        "blue" -> R.style.ThemeOverlay_SDMusicPlayer_Blue
        "green" -> R.style.ThemeOverlay_SDMusicPlayer_Green
        "orange" -> R.style.ThemeOverlay_SDMusicPlayer_Orange
        "red" -> R.style.ThemeOverlay_SDMusicPlayer_Red
        "pink" -> R.style.ThemeOverlay_SDMusicPlayer_Pink
        else -> null
    }

    // スキン文字列キー → View側ThemeOverlayのリソースID。"standard"の場合はnull(Base.Theme.MusicPlayerの既定値のまま)
    private fun themeOverlayForSkin(key: String): Int? = when (key) {
        "modern" -> R.style.ThemeOverlay_SDMusicPlayer_Skin_Modern
        else -> null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 保存済みのメインカラー・スキン・背景設定を、UI構築より前に同期的に読み込む。
        // これを非同期にすると、起動直後は必ずデフォルトの見た目で描画されてから
        // 読み込み完了後に切り替わる「フラッシュ」が毎回発生してしまうため、
        // このキーだけをrunBlockingで読む(DataStoreは2回目以降メモリキャッシュされるため
        // 実際のブロック時間はごく短い想定)。
        // 背景設定(画像・パターン・不透明度・暗さ)自体はrecreate()を伴わずライブ反映されるため、
        // ここで読むのは「背景が有効かどうか」の判定に必要な種類(backgroundType)のみ
        runBlocking {
            val prefs = ThemePreferences(this@MainActivity)
            themeColorKey = prefs.themeColorSeed.first()
            themeSkinKey = prefs.skinKey.first()
            themeHasBackground = prefs.backgroundType.first() != "none"
        }

        // View/XML側(Bottom Navigation・Mini Player・シャッフルボタン等)への反映。
        // super.onCreate()より前に適用する必要がある(テーマはウィンドウ生成時に確定するため)。
        val overlayRes = themeOverlayForKey(themeColorKey)
        if (overlayRes != null) {
            theme.applyStyle(overlayRes, true)
        } else {
            // 「Dynamic」選択時。API31未満の端末では自動的に何もせず、
            // Base.Theme.MusicPlayerのデフォルト色(color_accent)のままになる
            DynamicColors.applyToActivityIfAvailable(this)
        }

        val skinOverlayRes = themeOverlayForSkin(themeSkinKey)
        if (skinOverlayRes != null) {
            theme.applyStyle(skinOverlayRes, true)
        }

        // 背景機能(文字視認性)で追加。背景が有効な場合、文字を白+影にするThemeOverlayを適用する
        if (themeHasBackground) {
            theme.applyStyle(R.style.ThemeOverlay_SDMusicPlayer_BackgroundText, true)
        }

        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_main)

        onBackPressedDispatcher.addCallback(this) {
            when {
                isFullPlayerOpen -> closeFullScreenPlayer()
                isSettingsOpen -> closeSettings()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }

        database = AppDatabase.getDatabase(this)

        miniPlayer = findViewById(R.id.miniPlayer)
        miniPlayerTitle = findViewById(R.id.miniPlayerTitle)
        miniPlayerArtist = findViewById(R.id.miniPlayerArtist)
        miniPlayerPlayPause = findViewById(R.id.miniPlayerPlayPause)
        composePlayerContainer = findViewById(R.id.composePlayerContainer)
        backgroundImageView = findViewById(R.id.appBackgroundImage)
        backgroundDarkOverlay = findViewById(R.id.appBackgroundDarkOverlay)

        val fragmentContainerView = findViewById<SwipeableFrameLayout>(R.id.fragmentContainer)
        ViewCompat.setOnApplyWindowInsetsListener(fragmentContainerView) { view, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(top = statusBar.top)
            insets
        }

        // 横スワイプでタブ切替(スワイプ機能)で追加。詳細画面表示中(バックスタックあり)・
        // 設定/プレーヤー表示中はスワイプを無効にする
        fragmentContainerView.isSwipeAllowed = {
            supportFragmentManager.backStackEntryCount == 0 && !isSettingsOpen && !isFullPlayerOpen
        }
        fragmentContainerView.onSwipeLeft = { switchToAdjacentTab(1) }
        fragmentContainerView.onSwipeRight = { switchToAdjacentTab(-1) }

        val bottomNavView = findViewById<View>(R.id.bottomNavigation)
        ViewCompat.setOnApplyWindowInsetsListener(bottomNavView) { view, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(bottom = navBar.bottom)
            insets
        }

        // 背景関連の設定(DataStore)の監視を開始する。メインカラー・スキンと異なりrecreate()は不要で、
        // 値が変わるたびにその場で背景ビューへ反映される(ただし背景の有無自体が切り替わったときは、
        // 文字色テーマを反映するため例外的にrecreateする。詳細はobserveBackgroundPrefs()参照)
        observeBackgroundPrefs()

        // WMA等、再生に失敗した曲を自動スキップした際の通知(Toast)を監視する(WMA非対応の追加対応で新規)
        observeSkipMessages()

        // Compose側(フルスクリーンプレーヤー・設定画面)に渡すメインカラー・スキン
        val composeColorSeed = AppColorSeed.fromKey(themeColorKey)
        val composeSkin = AppSkin.fromKey(themeSkinKey)

        composePlayerContainer.setContent {
            MusicPlayerTheme(colorSeed = composeColorSeed, skin = composeSkin) {
                when (val overlay = currentOverlay) {
                    is OverlayScreen.Player -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            var isFavorite by remember(overlay.songId) { mutableStateOf(false) }
                            var songLyrics by remember(overlay.songId) { mutableStateOf<String?>(null) }
                            // 再生画面から開くイコライザーボトムシートの表示状態(設定画面のものとは別管理)
                            var showEqualizerSheet by remember { mutableStateOf(false) }
                            val playerUiState by playerViewModel.uiState.collectAsState()

                            LaunchedEffect(overlay.songId) {
                                isFavorite = database.favoriteDao().isFavorite(overlay.songId)
                                songLyrics = database.songDao().getSongById(overlay.songId)?.lyrics
                            }

                            PlayerScreen(
                                viewModel = playerViewModel,
                                onClose = { closeFullScreenPlayer() },
                                onFavoriteClick = {
                                    lifecycleScope.launch {
                                        if (isFavorite) {
                                            database.favoriteDao().removeFavorite(overlay.songId)
                                        } else {
                                            database.favoriteDao().addFavorite(FavoriteSong(overlay.songId))
                                        }
                                        isFavorite = !isFavorite
                                    }
                                },
                                isFavorite = isFavorite,
                                onQueueClick = { openQueueBottomSheet() },
                                onAddToPlaylistClick = { showAddToPlaylistDialogForSong(overlay.songId) },
                                onEqualizerClick = { showEqualizerSheet = true },
                                lyrics = songLyrics
                            )

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
                        }
                    }
                    OverlayScreen.Settings -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .systemBarsPadding()
                        ) {
                            SettingsScreen(
                                onClose = { closeSettings() },
                                playerViewModel = playerViewModel,
                                database = database,
                                onOpenFolders = {
                                    closeSettings()
                                    supportFragmentManager.beginTransaction()
                                        .replace(R.id.fragmentContainer, FoldersFragment.newInstance(""))
                                        .addToBackStack(null)
                                        .commit()
                                },
                                onLibraryChanged = { refreshCurrentLibraryFragment() },
                                currentColorKey = themeColorKey,
                                onColorSeedSelected = { key -> applyThemeColorSeed(key) },
                                currentSkinKey = themeSkinKey,
                                onSkinSelected = { key -> applySkin(key) }
                            )
                        }
                    }
                    OverlayScreen.None -> {
                        // 何も表示しない
                    }
                }
            }
        }

        miniPlayer.setOnClickListener {
            openFullScreenPlayer()
        }

        miniPlayerPlayPause.setOnClickListener {
            val controller = mediaController ?: return@setOnClickListener
            if (controller.isPlaying) controller.pause() else controller.play()
        }
        findViewById<ImageButton>(R.id.miniPlayerNext).setOnClickListener {
            mediaController?.seekToNextMediaItem()
        }
        findViewById<ImageButton>(R.id.miniPlayerPrevious).setOnClickListener {
            mediaController?.seekToPreviousMediaItem()
        }
        findViewById<ImageButton>(R.id.miniPlayerQueue).setOnClickListener {
            openQueueBottomSheet()
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, HomeFragment())
                .commit()
        } else {
            isSettingsOpen = savedInstanceState.getBoolean("isSettingsOpen", false)
            isFullPlayerOpen = savedInstanceState.getBoolean("isFullPlayerOpen", false)
        }

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        bottomNav.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_home -> HomeFragment()
                R.id.nav_songs -> SongsFragment()
                R.id.nav_artists -> ArtistsFragment()
                R.id.nav_albums -> AlbumsFragment()
                R.id.nav_playlists -> PlaylistsFragment()
                else -> null
            }
            if (fragment != null) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, fragment)
                    .commit()
                true
            } else {
                false
            }
        }

        if (isSettingsOpen) {
            openSettings()
        }
    }

    // メインカラーが選択されたときの保存 + 反映(Activity再生成で全画面に反映する)
    private fun applyThemeColorSeed(key: String) {
        lifecycleScope.launch {
            ThemePreferences(this@MainActivity).setThemeColorSeed(key)
            recreate()
        }
    }

    // スキンが選択されたときの保存 + 反映(Activity再生成で全画面に反映する)
    private fun applySkin(key: String) {
        lifecycleScope.launch {
            ThemePreferences(this@MainActivity).setSkinKey(key)
            recreate()
        }
    }

    // ===== 背景機能 =====

    // 背景関連の設定(DataStore)を監視し、値が変わるたびに背景ビューへ反映する。
    // メインカラー・スキンと異なりrecreate()は不要で、その場で更新される。
    // ただしbackgroundTypeについては「なし」⇔「それ以外」が切り替わった場合のみ、
    // 文字色テーマ(ThemeOverlay.SDMusicPlayer.BackgroundText)を反映するため例外的にrecreateする
    private fun observeBackgroundPrefs() {
        val prefs = ThemePreferences(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    prefs.backgroundType.collect { type ->
                        bgType = type
                        applyBackground()

                        val nowHasBackground = type != "none"
                        if (nowHasBackground != themeHasBackground) {
                            themeHasBackground = nowHasBackground
                            recreate()
                        }
                    }
                }
                launch { prefs.backgroundImagePath.collect { bgImagePath = it; applyBackground() } }
                launch { prefs.backgroundPatternKey.collect { bgPatternKey = it; applyBackground() } }
                launch { prefs.backgroundOpacity.collect { bgOpacity = it; applyBackground() } }
                launch { prefs.backgroundDarkness.collect { bgDarkness = it; applyBackground() } }
            }
        }
    }

    // WMA等、Media3が再生できない形式の曲が来た際、PlayerViewModel側で自動的にキューから
    // スキップされる。そのタイミングでUI側に一言Toastで知らせるための監視(WMA非対応の追加対応で新規)。
    // 一度きりの通知(SharedFlow)であり、既存のobserveBackgroundPrefs()と同じ
    // 「STARTEDの間だけ監視する」パターンに合わせている
    private fun observeSkipMessages() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerViewModel.skipMessageEvent.collect { message ->
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun applyBackground() {
        when (bgType) {
            "album_art" -> showImageBackground(mediaController?.mediaMetadata?.artworkUri)
            "custom_image" -> {
                val path = bgImagePath
                if (path.isNullOrBlank()) hideBackground() else showImageBackground(Uri.fromFile(File(path)))
            }
            "pattern" -> showPatternBackground(bgPatternKey)
            else -> hideBackground()
        }
    }

    private fun showImageBackground(uri: Uri?) {
        if (uri == null) {
            hideBackground()
            return
        }
        backgroundImageView.visibility = View.VISIBLE
        backgroundImageView.background = null
        backgroundImageView.alpha = bgOpacity
        backgroundImageView.load(uri) { crossfade(true) }

        backgroundDarkOverlay.visibility = View.VISIBLE
        backgroundDarkOverlay.setBackgroundColor(
            android.graphics.Color.argb((bgDarkness * 255).toInt(), 0, 0, 0)
        )
    }

    private fun showPatternBackground(key: String) {
        val pattern = backgroundPatternByKey(key)
        backgroundImageView.visibility = View.VISIBLE
        backgroundImageView.setImageDrawable(null)
        backgroundImageView.alpha = 1f

        if (pattern.colorsArgb.size >= 2) {
            backgroundImageView.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                pattern.colorsArgb.toIntArray()
            )
        } else {
            backgroundImageView.background = null
            backgroundImageView.setBackgroundColor(pattern.colorsArgb.first())
        }

        // パターンは常にそのまま表示するため、暗さのオーバーレイは使わない
        backgroundDarkOverlay.visibility = View.GONE
    }

    private fun hideBackground() {
        backgroundImageView.visibility = View.GONE
        backgroundImageView.setImageDrawable(null)
        backgroundImageView.background = null
        backgroundDarkOverlay.visibility = View.GONE
    }

    // 再生画面のプレイリスト追加ボタンから呼ばれる。SongsFragment等の同処理と同じAlertDialog方式
    private fun showAddToPlaylistDialogForSong(songId: Long) {
        lifecycleScope.launch {
            val playlists = database.playlistDao().getAllPlaylists()

            if (playlists.isEmpty()) {
                Toast.makeText(this@MainActivity, "先にプレイリストを作成してください", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val playlistNames = playlists.map { it.name }.toTypedArray()

            AlertDialog.Builder(this@MainActivity)
                .setTitle("プレイリストに追加")
                .setItems(playlistNames) { _, which ->
                    val selectedPlaylist = playlists[which]
                    lifecycleScope.launch {
                        database.playlistDao().addSongToPlaylist(
                            PlaylistSongCrossRef(playlistId = selectedPlaylist.id, songId = songId)
                        )
                        Toast.makeText(
                            this@MainActivity,
                            "${selectedPlaylist.name} に追加しました",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isSettingsOpen", isSettingsOpen)
        outState.putBoolean("isFullPlayerOpen", isFullPlayerOpen)
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener(
            {
                mediaController = controllerFuture.get()
                mediaController?.addListener(playerListener)
                playerViewModel.attach(mediaController!!)
                updateMiniPlayer()
                updatePlayPauseIcon(mediaController?.isPlaying ?: false)
                restoreLastPlayedIfNeeded()

                // MediaControllerが使えるようになったタイミングで、背景が「アルバムアート」の場合は
                // 現在再生中の曲のジャケットをすぐに反映する
                if (bgType == "album_art") applyBackground()

                if (isFullPlayerOpen && composePlayerContainer.visibility != View.VISIBLE) {
                    openFullScreenPlayer()
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    override fun onStop() {
        super.onStop()
        saveLastPlayedState()
        mediaController?.removeListener(playerListener)
        playerViewModel.detach()
        mediaController?.release()
        mediaController = null
    }

    // 再生キュー保持機能で変更。曲1件だけでなく、キュー全体(曲順・現在位置)を保存する
    private fun saveLastPlayedState() {
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0) return

        val songIds = (0 until controller.mediaItemCount).mapNotNull {
            controller.getMediaItemAt(it).mediaId.toLongOrNull()
        }
        val currentIndex = controller.currentMediaItemIndex
        val position = controller.currentPosition

        lifecycleScope.launch {
            ThemePreferences(this@MainActivity).saveQueueState(songIds, currentIndex, position)
        }
    }

    // 再生キュー保持機能で変更。保存されたキュー全体を復元する。
    // 保存後に端末からファイルが削除された曲は復元対象から除外し、現在のインデックスもずらして辻褄を合わせる
    private fun restoreLastPlayedIfNeeded() {
        if (hasAttemptedRestore) return
        hasAttemptedRestore = true

        val controller = mediaController ?: return
        if (controller.mediaItemCount > 0) return

        lifecycleScope.launch {
            val themePrefs = ThemePreferences(this@MainActivity)
            val savedIds = themePrefs.queueSongIds.first()
                .split(",")
                .mapNotNull { it.trim().toLongOrNull() }
            if (savedIds.isEmpty()) return@launch

            val savedIndex = themePrefs.queueCurrentIndex.first()
            val savedPosition = themePrefs.lastPlayedPositionMs.first()

            val allSongs = SongRepository(database).getAllSongs()
            val songsById = allSongs.associateBy { it.id }

            val restoredSongs = mutableListOf<Song>()
            var adjustedIndex = savedIndex
            savedIds.forEachIndexed { originalIndex, id ->
                val song = songsById[id]
                if (song != null) {
                    restoredSongs.add(song)
                } else if (originalIndex < savedIndex) {
                    adjustedIndex--
                }
            }
            if (restoredSongs.isEmpty()) return@launch
            val finalIndex = adjustedIndex.coerceIn(0, restoredSongs.size - 1)

            val mediaItems = restoredSongs.map { toMediaItem(it) }
            mediaController?.setMediaItems(mediaItems, finalIndex, savedPosition)
            mediaController?.prepare()
            updateMiniPlayer()
        }
    }

    private fun toMediaItem(song: Song): MediaItem {
        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(song.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(song.albumArtUri)
                    .build()
            )
            .build()
    }

    private fun updateMiniPlayer() {
        val controller = mediaController ?: return
        val metadata = controller.mediaMetadata

        if (metadata.title == null) {
            miniPlayer.visibility = View.GONE
            return
        }

        miniPlayer.visibility = View.VISIBLE
        miniPlayerTitle.text = metadata.title
        miniPlayerArtist.text = metadata.artist
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        val icon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        miniPlayerPlayPause.setImageResource(icon)
    }

    private fun openQueueBottomSheet() {
        QueueBottomSheetFragment().show(supportFragmentManager, "queue")
    }

    private fun refreshCurrentLibraryFragment() {
        (supportFragmentManager.findFragmentById(R.id.fragmentContainer) as? LibraryRefreshable)?.refreshLibrary()
    }

    fun openSettings() {
        isSettingsOpen = true
        isFullPlayerOpen = false
        currentOverlay = OverlayScreen.Settings
        composePlayerContainer.visibility = View.VISIBLE
        findViewById<View>(R.id.bottomNavigation).visibility = View.GONE
        miniPlayer.visibility = View.GONE
    }

    private fun closeSettings() {
        isSettingsOpen = false
        currentOverlay = OverlayScreen.None
        composePlayerContainer.visibility = View.GONE
        findViewById<View>(R.id.bottomNavigation).visibility = View.VISIBLE
        updateMiniPlayer()
    }

    fun openFullScreenPlayer() {
        val currentSongId = getCurrentSongId() ?: return

        isFullPlayerOpen = true
        isSettingsOpen = false
        currentOverlay = OverlayScreen.Player(currentSongId)

        hideStatusBarOnly()
        composePlayerContainer.visibility = View.VISIBLE

        findViewById<View>(R.id.bottomNavigation).visibility = View.GONE
        miniPlayer.visibility = View.GONE
    }

    private fun closeFullScreenPlayer() {
        isFullPlayerOpen = false
        currentOverlay = OverlayScreen.None
        composePlayerContainer.visibility = View.GONE

        findViewById<View>(R.id.bottomNavigation).visibility = View.VISIBLE
        updateMiniPlayer()

        showSystemBars()
    }

    private fun hideStatusBarOnly() {
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun showSystemBars() {
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun getCurrentSongId(): Long? {
        return mediaController?.currentMediaItem?.mediaId?.toLongOrNull()
    }

    // 横スワイプでタブ切替(スワイプ機能)で追加。direction=1で次のタブへ、-1で前のタブへ移動する。
    // isSwipeAllowedで既に条件チェック済みだが、念のためここでも同じ条件を確認する
    private fun switchToAdjacentTab(direction: Int) {
        if (supportFragmentManager.backStackEntryCount > 0) return
        if (isSettingsOpen || isFullPlayerOpen) return

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        val currentIndex = TAB_ORDER.indexOf(bottomNav.selectedItemId)
        if (currentIndex == -1) return

        val newIndex = (currentIndex + direction).coerceIn(0, TAB_ORDER.size - 1)
        if (newIndex != currentIndex) {
            bottomNav.selectedItemId = TAB_ORDER[newIndex]
        }
    }

    companion object {
        // 横スワイプでタブを切り替える際の並び順。menu/bottom_nav_menu.xmlと同じ順序にすること
        private val TAB_ORDER = listOf(
            R.id.nav_home,
            R.id.nav_songs,
            R.id.nav_artists,
            R.id.nav_albums,
            R.id.nav_playlists
        )
    }
}