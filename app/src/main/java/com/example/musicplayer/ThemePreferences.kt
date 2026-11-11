package com.example.musicplayer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_settings")

enum class ThemeMode { LIGHT, DARK, SYSTEM }

class ThemePreferences(private val context: Context) {

    companion object {
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        private val DYNAMIC_COLOR_KEY = booleanPreferencesKey("dynamic_color_enabled")
        private val LAST_SLEEP_MINUTES_KEY = intPreferencesKey("last_sleep_timer_minutes")

        private val LAST_PLAYED_SONG_ID_KEY = longPreferencesKey("last_played_song_id")
        private val LAST_PLAYED_POSITION_KEY = longPreferencesKey("last_played_position_ms")

        // 再生キュー保持機能で追加。キュー内の曲IDをカンマ区切りで保存する
        private val QUEUE_SONG_IDS_KEY = stringPreferencesKey("queue_song_ids")
        private val QUEUE_CURRENT_INDEX_KEY = intPreferencesKey("queue_current_index")
        // メインカラー機能で追加。"dynamic" / "indigo" / "purple" / "blue" / "green" / "orange" / "red" / "pink"
        private val THEME_COLOR_SEED_KEY = stringPreferencesKey("theme_color_seed")

        // イコライザーのプリセット機能で追加。"default" / "bass_boost" / ... / "custom"
        private val EQ_PRESET_KEY = stringPreferencesKey("eq_preset_key")
        // Customプリセット時の各バンドの値(カンマ区切りの文字列。例: "300,-100,0,200,150")
        private val EQ_CUSTOM_LEVELS_KEY = stringPreferencesKey("eq_custom_levels")

        // スキン機能で追加。"standard" / "modern"
        private val SKIN_KEY = stringPreferencesKey("selected_skin_key")

        // 背景機能で追加。"none" / "album_art" / "custom_image" / "pattern"
        private val BACKGROUND_TYPE_KEY = stringPreferencesKey("background_type")
        // "custom_image"選択時、内部ストレージに保存した画像ファイルの絶対パス
        private val BACKGROUND_IMAGE_PATH_KEY = stringPreferencesKey("background_image_path")
        // "pattern"選択時に選んでいるプリセットのキー(BackgroundPattern.kt参照)
        private val BACKGROUND_PATTERN_KEY = stringPreferencesKey("background_pattern_key")
        // 写真背景(album_art/custom_image)の不透明度・暗さ。パターンには適用しない
        private val BACKGROUND_OPACITY_KEY = floatPreferencesKey("background_opacity")
        private val BACKGROUND_DARKNESS_KEY = floatPreferencesKey("background_darkness")

        // 曲情報編集機能で追加。trueの場合、アプリ内表示だけでなく実ファイルのタグも書き換える
        private val EDIT_ORIGINAL_FILE_KEY = booleanPreferencesKey("edit_original_file_enabled")
    }

    val themeMode: Flow<ThemeMode> = context.themeDataStore.data.map { prefs ->
        when (prefs[THEME_MODE_KEY]) {
            "LIGHT" -> ThemeMode.LIGHT
            "DARK" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
    }

    // 旧Dynamic Colorスイッチ用キー。メインカラー機能に一本化したため現在は未参照だが、
    // 既存データ保護のためキー・読み書き機能自体は残す
    val dynamicColorEnabled: Flow<Boolean> = context.themeDataStore.data.map { prefs ->
        prefs[DYNAMIC_COLOR_KEY] ?: false
    }

    val lastSleepTimerMinutes: Flow<Int> = context.themeDataStore.data.map { prefs ->
        prefs[LAST_SLEEP_MINUTES_KEY] ?: 0
    }

    // メインカラー選択値。未設定時はDynamic Color扱い
    val themeColorSeed: Flow<String> = context.themeDataStore.data.map { prefs ->
        prefs[THEME_COLOR_SEED_KEY] ?: "dynamic"
    }

    // イコライザーの選択中プリセット。未設定時は「デフォルト」扱い
    val eqPresetKey: Flow<String> = context.themeDataStore.data.map { prefs ->
        prefs[EQ_PRESET_KEY] ?: "default"
    }

    // Customプリセット時の各バンドの値(カンマ区切り文字列)。未設定時は空文字
    val eqCustomLevels: Flow<String> = context.themeDataStore.data.map { prefs ->
        prefs[EQ_CUSTOM_LEVELS_KEY] ?: ""
    }

    // スキン選択値。未設定時は"standard"扱い
    val skinKey: Flow<String> = context.themeDataStore.data.map { prefs ->
        prefs[SKIN_KEY] ?: "standard"
    }

    // 背景タイプ。未設定時は"none"扱い
    val backgroundType: Flow<String> = context.themeDataStore.data.map { prefs ->
        prefs[BACKGROUND_TYPE_KEY] ?: "none"
    }

    // 写真(ユーザー選択)背景の保存パス。未設定時はnull
    val backgroundImagePath: Flow<String?> = context.themeDataStore.data.map { prefs ->
        prefs[BACKGROUND_IMAGE_PATH_KEY]
    }

    // パターン背景の選択キー。未設定時は先頭のプリセット
    val backgroundPatternKey: Flow<String> = context.themeDataStore.data.map { prefs ->
        prefs[BACKGROUND_PATTERN_KEY] ?: backgroundPatterns.first().key
    }

    // 写真背景の不透明度(0.1f〜1f)。未設定時は0.5f
    val backgroundOpacity: Flow<Float> = context.themeDataStore.data.map { prefs ->
        prefs[BACKGROUND_OPACITY_KEY] ?: 0.5f
    }

    // 写真背景の暗さオーバーレイ量(0f〜0.8f)。未設定時は0.3f
    val backgroundDarkness: Flow<Float> = context.themeDataStore.data.map { prefs ->
        prefs[BACKGROUND_DARKNESS_KEY] ?: 0.3f
    }

    // 曲情報編集機能で追加。未設定時はfalse(アプリ内表示のみ)
    val editOriginalFileEnabled: Flow<Boolean> = context.themeDataStore.data.map { prefs ->
        prefs[EDIT_ORIGINAL_FILE_KEY] ?: false
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.themeDataStore.edit { prefs -> prefs[THEME_MODE_KEY] = mode.name }
    }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        context.themeDataStore.edit { prefs -> prefs[DYNAMIC_COLOR_KEY] = enabled }
    }

    suspend fun setLastSleepTimerMinutes(minutes: Int) {
        context.themeDataStore.edit { prefs -> prefs[LAST_SLEEP_MINUTES_KEY] = minutes }
    }

    suspend fun setThemeColorSeed(key: String) {
        context.themeDataStore.edit { prefs -> prefs[THEME_COLOR_SEED_KEY] = key }
    }

    suspend fun setEqPreset(key: String) {
        context.themeDataStore.edit { prefs -> prefs[EQ_PRESET_KEY] = key }
    }

    suspend fun setEqCustomLevels(levels: String) {
        context.themeDataStore.edit { prefs -> prefs[EQ_CUSTOM_LEVELS_KEY] = levels }
    }

    suspend fun setSkinKey(key: String) {
        context.themeDataStore.edit { prefs -> prefs[SKIN_KEY] = key }
    }

    suspend fun setBackgroundType(type: String) {
        context.themeDataStore.edit { prefs -> prefs[BACKGROUND_TYPE_KEY] = type }
    }

    suspend fun setBackgroundImagePath(path: String?) {
        context.themeDataStore.edit { prefs ->
            if (path != null) {
                prefs[BACKGROUND_IMAGE_PATH_KEY] = path
            } else {
                prefs.remove(BACKGROUND_IMAGE_PATH_KEY)
            }
        }
    }

    suspend fun setBackgroundPatternKey(key: String) {
        context.themeDataStore.edit { prefs -> prefs[BACKGROUND_PATTERN_KEY] = key }
    }

    suspend fun setBackgroundOpacity(value: Float) {
        context.themeDataStore.edit { prefs -> prefs[BACKGROUND_OPACITY_KEY] = value }
    }

    suspend fun setBackgroundDarkness(value: Float) {
        context.themeDataStore.edit { prefs -> prefs[BACKGROUND_DARKNESS_KEY] = value }
    }

    suspend fun setEditOriginalFileEnabled(enabled: Boolean) {
        context.themeDataStore.edit { prefs -> prefs[EDIT_ORIGINAL_FILE_KEY] = enabled }
    }

    val lastPlayedSongId: Flow<Long?> = context.themeDataStore.data.map { prefs ->
        prefs[LAST_PLAYED_SONG_ID_KEY]
    }

    val lastPlayedPositionMs: Flow<Long> = context.themeDataStore.data.map { prefs ->
        prefs[LAST_PLAYED_POSITION_KEY] ?: 0L
    }

    suspend fun saveLastPlayed(songId: Long, positionMs: Long) {
        context.themeDataStore.edit { prefs ->
            prefs[LAST_PLAYED_SONG_ID_KEY] = songId
            prefs[LAST_PLAYED_POSITION_KEY] = positionMs
        }
    }
    // 再生キュー保持機能で追加。曲ID一覧・現在のインデックス・再生位置をまとめて1回で保存する
    val queueSongIds: Flow<String> = context.themeDataStore.data.map { prefs ->
        prefs[QUEUE_SONG_IDS_KEY] ?: ""
    }

    val queueCurrentIndex: Flow<Int> = context.themeDataStore.data.map { prefs ->
        prefs[QUEUE_CURRENT_INDEX_KEY] ?: 0
    }

    suspend fun saveQueueState(songIds: List<Long>, currentIndex: Int, positionMs: Long) {
        context.themeDataStore.edit { prefs ->
            prefs[QUEUE_SONG_IDS_KEY] = songIds.joinToString(",")
            prefs[QUEUE_CURRENT_INDEX_KEY] = currentIndex
            prefs[LAST_PLAYED_POSITION_KEY] = positionMs
        }
    }
}

fun applyNightMode(mode: ThemeMode) {
    val nightMode = when (mode) {
        ThemeMode.LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        ThemeMode.DARK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        ThemeMode.SYSTEM -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }
    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode)
}