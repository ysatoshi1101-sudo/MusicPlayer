package com.example.musicplayer

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class ArtistsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var database: AppDatabase
    private lateinit var editTextSearch: EditText
    private lateinit var adapter: ArtistAdapter

    private var allArtists: List<ArtistInfo> = emptyList()

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
            text = "アーティスト"
            visibility = View.VISIBLE
        }

        // 画面タイトル右端の設定ボタン
        view.findViewById<ImageButton>(R.id.buttonScreenSettings).apply {
            visibility = View.VISIBLE
            setOnClickListener { (activity as? MainActivity)?.openSettings() }
        }

        // アーティスト一覧にのみ検索欄を表示する
        view.findViewById<View>(R.id.searchBar).visibility = View.VISIBLE
        editTextSearch = view.findViewById(R.id.editTextSearch)
        editTextSearch.hint = "アーティスト名で検索"
        editTextSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterArtists(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        recyclerView = view.findViewById(R.id.recyclerViewGroups)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        adapter = ArtistAdapter { selectedArtist ->
            val detailFragment = FilteredSongsFragment.newInstance("artist", selectedArtist.name)
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, detailFragment)
                .addToBackStack(null)
                .commit()
        }
        recyclerView.adapter = adapter

        val fastScroller = view.findViewById<FastScrollerView>(R.id.fastScroller)
        RecyclerViewFastScrollHelper(recyclerView, fastScroller).attach()

        loadArtists()
    }

    private fun loadArtists() {
        lifecycleScope.launch {
            val allSongs = SongRepository(database).getAllSongs()
            allArtists = allSongs
                .groupBy { it.artist }
                .map { (artistName, songs) -> ArtistInfo(name = artistName, songCount = songs.size) }
                .sortedBy { it.name }

            filterArtists(editTextSearch.text.toString())
        }
    }

    private fun filterArtists(query: String) {
        val filtered = if (query.isBlank()) {
            allArtists
        } else {
            allArtists.filter { it.name.contains(query, ignoreCase = true) }
        }
        adapter.submitList(filtered)
    }
}