package com.example.musicplayer

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class QueueItem(
    val mediaId: String,
    val title: String,
    val artist: String,
    val albumArtUri: Uri?
)

data class EqualizerUiState(
    val enabled: Boolean = false,
    val numBands: Int = 0,
    val minLevel: Int = 0,
    val maxLevel: Int = 0,
    val centerFreqs: List<Int> = emptyList(),
    val bandLevels: List<Int> = emptyList(),
    val bassBoostSupported: Boolean = false,
    val bassBoostEnabled: Boolean = false,
    val bassBoostStrength: Int = 0,
    val virtualizerSupported: Boolean = false,
    val virtualizerEnabled: Boolean = false,
    val virtualizerStrength: Int = 0
)

data class PlayerUiState(
    val title: String = "",
    val artist: String = "",
    val albumArtUri: android.net.Uri? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val shuffleActive: Boolean = false, // シャッフルON/OFF切り替え機能で追加。ONにした時点の並び順をoriginalOrderMediaIdsに記憶する
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueItem> = emptyList(),
    val currentQueueIndex: Int = -1,
    val currentMediaId: String? = null, // 再生中アニメーション機能で追加。今再生中の曲のID(Song.idの文字列)
    val sleepTimerMinutes: Int = 0, // 0 = オフ。1〜180分
    val sleepTimerEndOfTrack: Boolean = false,
    val sleepTimerRemainingMs: Long? = null,
    val equalizer: EqualizerUiState = EqualizerUiState()
)

class PlayerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // WMA等、再生できない形式の曲を自動スキップした際にUI側(Toast表示)へ通知するためのイベント。
    // 状態(StateFlow)ではなく一度きりの通知なのでSharedFlowを使う
    private val _skipMessageEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val skipMessageEvent: SharedFlow<String> = _skipMessageEvent.asSharedFlow()

    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())

    // シャッフルON/OFF切り替え機能で追加。ONにした瞬間の曲順(曲ID列)を覚えておき、OFFにしたときに復元する
    private var originalOrderMediaIds: List<String>? = null

    private var sleepTimerEndElapsedMs: Long? = null
    private var sleepTimerEndOfTrackActive = false

    private val positionUpdater = object : Runnable {
        override fun run() {
            updatePositionOnly()
            checkSleepTimer()
            handler.postDelayed(this, 500L)
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
            refreshFullState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshFullState()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            refreshFullState()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            refreshFullState()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            refreshFullState()
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            // 「曲終了後停止」設定時、曲が自然に終わって次の曲へ移った瞬間に一時停止する
            if (sleepTimerEndOfTrackActive && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                controller?.pause()
                clearSleepTimer()
            }
            refreshFullState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // キューの最後の曲が終わったときは onMediaItemTransition が呼ばれないため、ここでも確認する
            if (sleepTimerEndOfTrackActive && playbackState == Player.STATE_ENDED) {
                clearSleepTimer()
            }
            refreshFullState()
        }

        // WMA等、Media3が再生できない形式の曲でエラーが発生した際に呼ばれる。
        // その曲をキューから取り除いて次の曲へ自動的に進める(何もしないと再生が止まったままになるため)
        override fun onPlayerError(error: PlaybackException) {
            val c = controller ?: return

            val failedTitle = c.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: "この曲"

            val failedIndex = c.currentMediaItemIndex
            if (failedIndex in 0 until c.mediaItemCount) {
                c.removeMediaItem(failedIndex)
            }

            _skipMessageEvent.tryEmit("${failedTitle}は再生できない形式のためスキップしました")

            if (c.mediaItemCount > 0) {
                c.prepare()
                c.play()
            } else {
                refreshFullState()
            }
        }
    }

    fun attach(mediaController: MediaController) {
        if (controller === mediaController) return
        controller = mediaController
        mediaController.addListener(listener)
        refreshFullState()
        requestEqualizerState()
        handler.post(positionUpdater)
    }

    fun detach() {
        controller?.removeListener(listener)
        handler.removeCallbacks(positionUpdater)
    }

    private fun refreshFullState() {
        val c = controller ?: return
        val metadata = c.mediaMetadata
        _uiState.value = _uiState.value.copy(
            title = metadata.title?.toString() ?: "",
            artist = metadata.artist?.toString() ?: "",
            albumArtUri = metadata.artworkUri,
            isPlaying = c.isPlaying,
            currentPositionMs = c.currentPosition.coerceAtLeast(0L),
            durationMs = c.duration.coerceAtLeast(0L),
            shuffleEnabled = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            queue = buildQueue(c),
            currentQueueIndex = c.currentMediaItemIndex,
            currentMediaId = c.currentMediaItem?.mediaId
        )
    }

    private fun buildQueue(c: MediaController): List<QueueItem> {
        val items = mutableListOf<QueueItem>()
        for (i in 0 until c.mediaItemCount) {
            val item = c.getMediaItemAt(i)
            items.add(
                QueueItem(
                    mediaId = item.mediaId,
                    title = item.mediaMetadata.title?.toString() ?: "",
                    artist = item.mediaMetadata.artist?.toString() ?: "",
                    albumArtUri = item.mediaMetadata.artworkUri
                )
            )
        }
        return items
    }

    private fun updatePositionOnly() {
        val c = controller ?: return
        _uiState.value = _uiState.value.copy(
            currentPositionMs = c.currentPosition.coerceAtLeast(0L),
            durationMs = c.duration.coerceAtLeast(0L)
        )
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun skipToNext() {
        controller?.seekToNextMediaItem()
    }

    fun skipToPrevious() {
        controller?.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    // シャッフルON/OFF切り替え機能で変更。ONにするときは現在の並び順を記憶してからシャッフルし、
    // OFFにするときはその並び順に復元する(現在再生中の曲は途切れさせない)
    fun toggleShuffle() {
        val c = controller ?: return

        if (_uiState.value.shuffleActive) {
            // OFF: 記憶していた元の並び順に戻す
            val original = originalOrderMediaIds
            if (original != null) {
                val itemsById = (0 until c.mediaItemCount).associate { i ->
                    val item = c.getMediaItemAt(i)
                    item.mediaId to item
                }
                val restoredOrder = original.mapNotNull { itemsById[it] }
                val restoredIds = restoredOrder.map { it.mediaId }.toSet()
                // シャッフル中に「次に再生」等で追加された曲があれば末尾にそのまま残す
                val extraItems = itemsById.values.filter { it.mediaId !in restoredIds }
                applyQueueOrder(restoredOrder + extraItems)
            }
            originalOrderMediaIds = null
            _uiState.value = _uiState.value.copy(shuffleActive = false)
        } else {
            // ON: 現在の並び順を記憶してから、現在の曲を先頭にした状態で残りをシャッフルする
            originalOrderMediaIds = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
            val shuffledOrder = buildShuffledOrder()
            if (shuffledOrder != null) {
                applyQueueOrder(shuffledOrder)
                _uiState.value = _uiState.value.copy(shuffleActive = true)
            } else {
                originalOrderMediaIds = null
            }
        }
    }

    // 現在再生中の曲を先頭にし、残りをシャッフルした並び順を作る(2曲未満なら何もしない)
    private fun buildShuffledOrder(): List<MediaItem>? {
        val c = controller ?: return null
        if (c.mediaItemCount < 2) return null
        val currentIndex = c.currentMediaItemIndex
        if (currentIndex !in 0 until c.mediaItemCount) return null

        val currentItem = c.getMediaItemAt(currentIndex)
        val restItems = (0 until c.mediaItemCount)
            .filter { it != currentIndex }
            .map { c.getMediaItemAt(it) }
            .shuffled()
        return listOf(currentItem) + restItems
    }

    // 指定した並び順を実際のキューへ適用する。現在再生中の曲は移動させるだけで、
    // 一度も削除しないため再生が途切れない
    private fun applyQueueOrder(newOrder: List<MediaItem>) {
        val c = controller ?: return
        if (newOrder.size < 2) return
        val currentMediaId = c.currentMediaItem?.mediaId ?: return
        val targetIndex = newOrder.indexOfFirst { it.mediaId == currentMediaId }
        if (targetIndex == -1) return

        val currentIndex = c.currentMediaItemIndex
        if (currentIndex != targetIndex) {
            c.moveMediaItem(currentIndex, targetIndex)
        }
        if (targetIndex > 0) {
            c.removeMediaItems(0, targetIndex)
        }
        if (c.mediaItemCount > 1) {
            c.removeMediaItems(1, c.mediaItemCount)
        }
        val before = newOrder.subList(0, targetIndex)
        val after = newOrder.subList(targetIndex + 1, newOrder.size)
        if (before.isNotEmpty()) c.addMediaItems(0, before)
        c.addMediaItems(c.mediaItemCount, after)
    }
    // シャッフルボタン(フル画面プレーヤー)で追加。Media3標準のshuffleModeEnabledではなく、
    // 「現在再生中の曲を先頭に固定し、残りの曲だけを実際に並べ替える」方式にする。
    // これにより再生キュー画面(QueueBottomSheetFragment)の表示順にも即座に反映される
    fun shuffleKeepingCurrent() {
        val c = controller ?: return
        val currentIndex = c.currentMediaItemIndex
        if (c.mediaItemCount <= 2) return // 2曲以下はシャッフルする意味がないため何もしない
        if (currentIndex !in 0 until c.mediaItemCount) return

        // 現在再生中の曲を先頭(インデックス0)へ移動する。再生自体は途切れない
        if (currentIndex != 0) {
            c.moveMediaItem(currentIndex, 0)
        }

        // 手動で並び替えるため、Media3側の自動シャッフルは使わない(併用すると二重にシャッフルされてしまうため)
        c.shuffleModeEnabled = false

        // 先頭以外(インデックス1以降)の曲だけを取り出してシャッフルし、入れ替える
        val restItems = (1 until c.mediaItemCount).map { c.getMediaItemAt(it) }.shuffled()
        c.removeMediaItems(1, c.mediaItemCount)
        c.addMediaItems(1, restItems)
    }
    fun toggleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        controller?.moveMediaItem(fromIndex, toIndex)
    }

    fun removeQueueItem(index: Int) {
        controller?.removeMediaItem(index)
    }

    fun playQueueItem(index: Int) {
        controller?.seekTo(index, 0L)
        controller?.play()
    }

    // 指定した分数でスリープタイマーをセットする(0以下はオフ扱い)
    fun setSleepTimerMinutes(minutes: Int) {
        sleepTimerEndOfTrackActive = false
        if (minutes <= 0) {
            sleepTimerEndElapsedMs = null
            _uiState.value = _uiState.value.copy(
                sleepTimerMinutes = 0,
                sleepTimerEndOfTrack = false,
                sleepTimerRemainingMs = null
            )
        } else {
            sleepTimerEndElapsedMs = SystemClock.elapsedRealtime() + minutes * 60_000L
            _uiState.value = _uiState.value.copy(
                sleepTimerMinutes = minutes,
                sleepTimerEndOfTrack = false,
                sleepTimerRemainingMs = minutes * 60_000L
            )
        }
    }

    // 「現在の曲が終わったら停止」を切り替える
    fun setSleepTimerEndOfTrack(enabled: Boolean) {
        if (enabled) {
            sleepTimerEndElapsedMs = null
            sleepTimerEndOfTrackActive = true
            _uiState.value = _uiState.value.copy(
                sleepTimerMinutes = 0,
                sleepTimerEndOfTrack = true,
                sleepTimerRemainingMs = null
            )
        } else {
            sleepTimerEndOfTrackActive = false
            _uiState.value = _uiState.value.copy(sleepTimerEndOfTrack = false)
        }
    }

    private fun clearSleepTimer() {
        sleepTimerEndOfTrackActive = false
        sleepTimerEndElapsedMs = null
        _uiState.value = _uiState.value.copy(
            sleepTimerMinutes = 0,
            sleepTimerEndOfTrack = false,
            sleepTimerRemainingMs = null
        )
    }

    private fun checkSleepTimer() {
        val endAt = sleepTimerEndElapsedMs ?: return
        val remaining = endAt - SystemClock.elapsedRealtime()
        if (remaining <= 0) {
            controller?.pause()
            clearSleepTimer()
        } else {
            _uiState.value = _uiState.value.copy(sleepTimerRemainingMs = remaining)
        }
    }

    // ===== イコライザー関連 =====

    fun requestEqualizerState() {
        sendCustomCommand(PlaybackService.CMD_EQ_GET_STATE, Bundle.EMPTY)
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            equalizer = _uiState.value.equalizer.copy(enabled = enabled)
        )
        val extras = Bundle().apply { putBoolean(PlaybackService.KEY_ENABLED, enabled) }
        sendCustomCommand(PlaybackService.CMD_EQ_SET_ENABLED, extras)
    }

    fun setEqualizerBandLevel(band: Int, level: Int) {
        val current = _uiState.value.equalizer
        val newLevels = current.bandLevels.toMutableList()
        if (band in newLevels.indices) {
            newLevels[band] = level
            _uiState.value = _uiState.value.copy(equalizer = current.copy(bandLevels = newLevels))
        }
        val extras = Bundle().apply {
            putInt(PlaybackService.KEY_BAND, band)
            putInt(PlaybackService.KEY_LEVEL, level)
        }
        sendCustomCommand(PlaybackService.CMD_EQ_SET_BAND_LEVEL, extras)
    }

    fun setBassBoost(enabled: Boolean, strength: Int) {
        _uiState.value = _uiState.value.copy(
            equalizer = _uiState.value.equalizer.copy(
                bassBoostEnabled = enabled,
                bassBoostStrength = strength
            )
        )
        val extras = Bundle().apply {
            putBoolean(PlaybackService.KEY_ENABLED, enabled)
            putInt(PlaybackService.KEY_STRENGTH, strength)
        }
        sendCustomCommand(PlaybackService.CMD_EQ_SET_BASS_BOOST, extras)
    }

    fun setVirtualizer(enabled: Boolean, strength: Int) {
        _uiState.value = _uiState.value.copy(
            equalizer = _uiState.value.equalizer.copy(
                virtualizerEnabled = enabled,
                virtualizerStrength = strength
            )
        )
        val extras = Bundle().apply {
            putBoolean(PlaybackService.KEY_ENABLED, enabled)
            putInt(PlaybackService.KEY_STRENGTH, strength)
        }
        sendCustomCommand(PlaybackService.CMD_EQ_SET_VIRTUALIZER, extras)
    }

    private fun sendCustomCommand(action: String, extras: Bundle) {
        val c = controller ?: return
        val future = c.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), extras)
        future.addListener({
            try {
                val result = future.get()
                applyEqualizerBundle(result.extras)
            } catch (e: Exception) {
                // 接続が切れている場合などは無視する
            }
        }, MoreExecutors.directExecutor())
    }

    private fun applyEqualizerBundle(extras: Bundle?) {
        val b = extras ?: return
        val centerFreqs = b.getIntArray(PlaybackService.KEY_CENTER_FREQS)?.toList() ?: emptyList()
        val currentLevels = b.getIntArray(PlaybackService.KEY_CURRENT_LEVELS)?.toList() ?: emptyList()
        val newState = EqualizerUiState(
            enabled = b.getBoolean(PlaybackService.KEY_ENABLED),
            numBands = b.getInt(PlaybackService.KEY_NUM_BANDS),
            minLevel = b.getInt(PlaybackService.KEY_MIN_LEVEL),
            maxLevel = b.getInt(PlaybackService.KEY_MAX_LEVEL),
            centerFreqs = centerFreqs,
            bandLevels = currentLevels,
            bassBoostSupported = b.getBoolean(PlaybackService.KEY_BASS_BOOST_SUPPORTED),
            bassBoostEnabled = b.getBoolean(PlaybackService.KEY_BASS_BOOST_ENABLED),
            bassBoostStrength = b.getInt(PlaybackService.KEY_BASS_BOOST_STRENGTH),
            virtualizerSupported = b.getBoolean(PlaybackService.KEY_VIRTUALIZER_SUPPORTED),
            virtualizerEnabled = b.getBoolean(PlaybackService.KEY_VIRTUALIZER_ENABLED),
            virtualizerStrength = b.getInt(PlaybackService.KEY_VIRTUALIZER_STRENGTH)
        )
        handler.post {
            _uiState.value = _uiState.value.copy(equalizer = newState)
        }
    }

    override fun onCleared() {
        super.onCleared()
        detach()
    }
}