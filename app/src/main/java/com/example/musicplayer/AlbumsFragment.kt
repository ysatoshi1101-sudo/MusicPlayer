package com.example.musicplayer

import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class AlbumsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var database: AppDatabase

    // 画面遷移(replace)でViewが再生成されてもスクロール位置を復元できるよう、
    // Fragmentインスタンス自身(バックスタックで生存)に状態を保持しておく
    private var savedLayoutState: Parcelable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_playlists_simple, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = AppDatabase.getDatabase(requireContext())

        view.findViewById<TextView>(R.id.textViewScreenTitle).apply {
            text = "アルバム"
            visibility = View.VISIBLE
        }

        // 画面タイトル右端の設定ボタン
        view.findViewById<ImageButton>(R.id.buttonScreenSettings).apply {
            visibility = View.VISIBLE
            setOnClickListener { (activity as? MainActivity)?.openSettings() }
        }

        recyclerView = view.findViewById(R.id.recyclerViewGroups)
        recyclerView.layoutManager = GridLayoutManager(requireContext(), 2)

        val adapter = AlbumAdapter { selectedAlbum ->
            val detailFragment = FilteredSongsFragment.newInstance("album", selectedAlbum.name)
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, detailFragment)
                .addToBackStack(null)
                .commit()
        }
        recyclerView.adapter = adapter

        val fastScroller = view.findViewById<FastScrollerView>(R.id.fastScroller)
        RecyclerViewFastScrollHelper(recyclerView, fastScroller).attach()

        lifecycleScope.launch {
            val allSongs = SongRepository(database).getAllSongs()
            val albums = allSongs
                .groupBy { it.album }
                .map { (albumName, songs) ->
                    AlbumInfo(
                        name = albumName,
                        artist = songs.first().artist,
                        albumArtUri = songs.firstOrNull { it.albumArtUri != null }?.albumArtUri,
                        songCount = songs.size
                    )
                }
                .sortedBy { it.name }

            // コミットコールバック: DiffUtilの計算が終わりアダプターに実際に反映された直後に呼ばれる。
            // アイテム件数が0のタイミングで復元すると状態が捨てられてしまうため、ここで復元する
            adapter.submitList(albums) {
                recyclerView.layoutManager?.onRestoreInstanceState(savedLayoutState)
            }
        }
    }

    override fun onDestroyView() {
        // アルバム詳細画面へ遷移する際、このFragmentのViewは破棄される(Fragmentインスタンス自体は
        // バックスタックに残る)。戻ってきたときに復元できるよう、破棄直前にスクロール位置を保存する
        savedLayoutState = recyclerView.layoutManager?.onSaveInstanceState()
        super.onDestroyView()
    }
}