package com.example.musicplayer

import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@UnstableApi
class PlaybackService : MediaSessionService() {

    companion object {
        // イコライザー関連のカスタムコマンド名
        const val CMD_EQ_GET_STATE = "com.example.musicplayer.EQ_GET_STATE"
        const val CMD_EQ_SET_ENABLED = "com.example.musicplayer.EQ_SET_ENABLED"
        const val CMD_EQ_SET_BAND_LEVEL = "com.example.musicplayer.EQ_SET_BAND_LEVEL"
        const val CMD_EQ_SET_BASS_BOOST = "com.example.musicplayer.EQ_SET_BASS_BOOST"
        const val CMD_EQ_SET_VIRTUALIZER = "com.example.musicplayer.EQ_SET_VIRTUALIZER"

        // Bundleのキー名
        const val KEY_ENABLED = "enabled"
        const val KEY_BAND = "band"
        const val KEY_LEVEL = "level"
        const val KEY_STRENGTH = "strength"

        const val KEY_NUM_BANDS = "numBands"
        const val KEY_MIN_LEVEL = "minLevel"
        const val KEY_MAX_LEVEL = "maxLevel"
        const val KEY_CENTER_FREQS = "centerFreqs"
        const val KEY_CURRENT_LEVELS = "currentLevels"
        const val KEY_BASS_BOOST_SUPPORTED = "bassBoostSupported"
        const val KEY_BASS_BOOST_ENABLED = "bassBoostEnabled"
        const val KEY_BASS_BOOST_STRENGTH = "bassBoostStrength"
        const val KEY_VIRTUALIZER_SUPPORTED = "virtualizerSupported"
        const val KEY_VIRTUALIZER_ENABLED = "virtualizerEnabled"
        const val KEY_VIRTUALIZER_STRENGTH = "virtualizerStrength"

        // 再生キュー保存(バックグラウンド再生対応)で追加。再生中に再生位置を保存する間隔
        private const val PERIODIC_SAVE_INTERVAL_MS = 5000L
    }

    private var mediaSession: MediaSession? = null

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    // 再生キュー保存(バックグラウンド再生対応)で追加。
    // 画面(MainActivity)が閉じている間も再生は続くため、再生キューの保存は
    // 再生状態を直接持っているこのサービス側でも行う
    private lateinit var themePrefs: ThemePreferences
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    // 再生中だけ、一定間隔で再生位置を保存し続ける
    private val periodicSaveRunnable = object : Runnable {
        override fun run() {
            saveQueueState()
            handler.postDelayed(this, PERIODIC_SAVE_INTERVAL_MS)
        }
    }

    // 再生キュー保存(バックグラウンド再生対応)で追加。再生状態が変わるたびに保存する
    private val queueSaveListener = object : Player.Listener {
        // 曲が切り替わったとき(バックグラウンドで次の曲へ進んだ場合も含む)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            saveQueueState()
        }

        // キューの内容が変わったとき(追加・削除・並び替えなど)
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            saveQueueState()
        }

        // キューの最後まで再生し終えたとき
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) saveQueueState()
        }

        // 再生開始 → 定期保存をスタート / 一時停止・停止 → 定期保存を止めて、今の状態を保存
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(periodicSaveRunnable)
            if (isPlaying) {
                handler.postDelayed(periodicSaveRunnable, PERIODIC_SAVE_INTERVAL_MS)
            } else {
                saveQueueState()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        themePrefs = ThemePreferences(applicationContext)

        // 音声セッションIDを先に自分で発行しておく。
        // ExoPlayerに任せると再生開始まで確定しないことがあるため、
        // 最初から固定のIDを割り当てて、それにイコライザー類を紐づける。
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val sessionId = audioManager.generateAudioSessionId()

        val player = ExoPlayer.Builder(this).build()
        player.setAudioSessionId(sessionId)

        // 再生キュー保存(バックグラウンド再生対応)で追加
        player.addListener(queueSaveListener)

        setupAudioEffects(sessionId)

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(PlaybackCallback())
            .build()
    }

    // ===== 再生キュー保存(バックグラウンド再生対応)で追加 =====

    // 保存する内容(曲ID一覧・今の曲の位置・再生位置)をまとめたもの
    private class QueueSnapshot(
        val songIds: List<Long>,
        val currentIndex: Int,
        val positionMs: Long
    )

    // 今のキューの状態を取り出す。キューが空のときはnull(保存しない)。
    // 起動直後にサービスが空の状態で、前回の保存を上書きしてしまうのを防ぐ
    private fun captureQueueSnapshot(): QueueSnapshot? {
        val player = mediaSession?.player ?: return null
        if (player.mediaItemCount == 0) return null

        val songIds = (0 until player.mediaItemCount).mapNotNull {
            player.getMediaItemAt(it).mediaId.toLongOrNull()
        }
        if (songIds.isEmpty()) return null

        // キューの最後まで再生し終えた状態では、再起動時に曲の最後から始まらないよう0にする
        val positionMs = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition
        return QueueSnapshot(songIds, player.currentMediaItemIndex, positionMs)
    }

    // 通常の保存。プレーヤーの状態はこの場(メインスレッド)で取り出してから、書き込みだけ非同期で行う
    private fun saveQueueState() {
        val snapshot = captureQueueSnapshot() ?: return
        serviceScope.launch {
            themePrefs.saveQueueState(snapshot.songIds, snapshot.currentIndex, snapshot.positionMs)
        }
    }

    // 終了直前用の保存。プロセスがすぐに終了させられても保存が間に合うよう、書き込み完了まで待つ
    private fun saveQueueStateBlocking() {
        val snapshot = captureQueueSnapshot() ?: return
        try {
            runBlocking {
                themePrefs.saveQueueState(snapshot.songIds, snapshot.currentIndex, snapshot.positionMs)
            }
        } catch (e: Exception) {
            // 保存に失敗しても、終了処理は止めない
        }
    }

    // アプリがタスク一覧(最近使ったアプリ)から消されたときに、最後の状態を保存する
    override fun onTaskRemoved(rootIntent: Intent?) {
        saveQueueStateBlocking()
        super.onTaskRemoved(rootIntent)
    }

    private fun setupAudioEffects(audioSessionId: Int) {
        releaseAudioEffects()

        equalizer = try {
            Equalizer(0, audioSessionId).also { it.setEnabled(false) }
        } catch (e: Exception) {
            null
        }

        bassBoost = try {
            BassBoost(0, audioSessionId).also { it.setEnabled(false) }
        } catch (e: Exception) {
            null
        }

        virtualizer = try {
            Virtualizer(0, audioSessionId).also { it.setEnabled(false) }
        } catch (e: Exception) {
            null
        }
    }

    private fun releaseAudioEffects() {
        equalizer?.release()
        bassBoost?.release()
        virtualizer?.release()
        equalizer = null
        bassBoost = null
        virtualizer = null
    }

    private inner class PlaybackCallback : MediaSession.Callback {

        // アプリ(UI)が接続してきたときに、通常のコマンドに加えて
        // イコライザー用のカスタムコマンドも使えるようにする
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val connectionResult = super.onConnect(session, controller)
            val sessionCommands = connectionResult.availableSessionCommands.buildUpon()
                .add(SessionCommand(CMD_EQ_GET_STATE, Bundle.EMPTY))
                .add(SessionCommand(CMD_EQ_SET_ENABLED, Bundle.EMPTY))
                .add(SessionCommand(CMD_EQ_SET_BAND_LEVEL, Bundle.EMPTY))
                .add(SessionCommand(CMD_EQ_SET_BASS_BOOST, Bundle.EMPTY))
                .add(SessionCommand(CMD_EQ_SET_VIRTUALIZER, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(
                sessionCommands,
                connectionResult.availablePlayerCommands
            )
        }

        // UI側から送られてきたイコライザー関連のコマンドを処理する
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_EQ_GET_STATE -> {
                    // 状態を返すだけなので何もしない(下でまとめて返す)
                }
                CMD_EQ_SET_ENABLED -> {
                    equalizer?.setEnabled(args.getBoolean(KEY_ENABLED))
                }
                CMD_EQ_SET_BAND_LEVEL -> {
                    val band = args.getInt(KEY_BAND).toShort()
                    val level = args.getInt(KEY_LEVEL).toShort()
                    try {
                        equalizer?.setBandLevel(band, level)
                    } catch (e: Exception) {
                        // 範囲外の値などは無視する
                    }
                }
                CMD_EQ_SET_BASS_BOOST -> {
                    bassBoost?.let { effect ->
                        effect.setEnabled(args.getBoolean(KEY_ENABLED))
                        if (effect.strengthSupported) {
                            try {
                                effect.setStrength(args.getInt(KEY_STRENGTH).toShort())
                            } catch (e: Exception) {
                            }
                        }
                    }
                }
                CMD_EQ_SET_VIRTUALIZER -> {
                    virtualizer?.let { effect ->
                        effect.setEnabled(args.getBoolean(KEY_ENABLED))
                        if (effect.strengthSupported) {
                            try {
                                effect.setStrength(args.getInt(KEY_STRENGTH).toShort())
                            } catch (e: Exception) {
                            }
                        }
                    }
                }
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(
                SessionResult(SessionResult.RESULT_SUCCESS, buildStateBundle())
            )
        }
    }

    // 現在のイコライザー/Bass Boost/Virtualizerの状態をひとまとめにして返す
    private fun buildStateBundle(): Bundle {
        val bundle = Bundle()
        val eq = equalizer
        if (eq != null) {
            val numBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            val centerFreqs = IntArray(numBands) { i -> eq.getCenterFreq(i.toShort()) }
            val currentLevels = IntArray(numBands) { i -> eq.getBandLevel(i.toShort()).toInt() }
            bundle.putBoolean(KEY_ENABLED, eq.enabled)
            bundle.putInt(KEY_NUM_BANDS, numBands)
            bundle.putInt(KEY_MIN_LEVEL, range[0].toInt())
            bundle.putInt(KEY_MAX_LEVEL, range[1].toInt())
            bundle.putIntArray(KEY_CENTER_FREQS, centerFreqs)
            bundle.putIntArray(KEY_CURRENT_LEVELS, currentLevels)
        } else {
            bundle.putBoolean(KEY_ENABLED, false)
            bundle.putInt(KEY_NUM_BANDS, 0)
            bundle.putInt(KEY_MIN_LEVEL, 0)
            bundle.putInt(KEY_MAX_LEVEL, 0)
            bundle.putIntArray(KEY_CENTER_FREQS, IntArray(0))
            bundle.putIntArray(KEY_CURRENT_LEVELS, IntArray(0))
        }

        val bb = bassBoost
        bundle.putBoolean(KEY_BASS_BOOST_SUPPORTED, bb != null)
        bundle.putBoolean(KEY_BASS_BOOST_ENABLED, bb?.enabled ?: false)
        bundle.putInt(KEY_BASS_BOOST_STRENGTH, bb?.roundedStrength?.toInt() ?: 0)

        val vr = virtualizer
        bundle.putBoolean(KEY_VIRTUALIZER_SUPPORTED, vr != null)
        bundle.putBoolean(KEY_VIRTUALIZER_ENABLED, vr?.enabled ?: false)
        bundle.putInt(KEY_VIRTUALIZER_STRENGTH, vr?.roundedStrength?.toInt() ?: 0)

        return bundle
    }

    // システムがこのサービスに接続してきたときに、どのセッションを渡すか
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        // 再生キュー保存(バックグラウンド再生対応)で追加。プレーヤーを解放する前に、最後の状態を保存する
        handler.removeCallbacks(periodicSaveRunnable)
        saveQueueStateBlocking()
        serviceScope.cancel()

        releaseAudioEffects()
        mediaSession?.run {
            player.removeListener(queueSaveListener)
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}