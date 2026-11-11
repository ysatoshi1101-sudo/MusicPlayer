package com.example.musicplayer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class FoldersFragment : Fragment() {

    companion object {
        private const val ARG_PATH_PREFIX = "path_prefix"

        fun newInstance(pathPrefix: String): FoldersFragment {
            val fragment = FoldersFragment()
            fragment.arguments = Bundle().apply { putString(ARG_PATH_PREFIX, pathPrefix) }
            return fragment
        }
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var database: AppDatabase
    private var pathPrefix: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_playlists_simple, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pathPrefix = arguments?.getString(ARG_PATH_PREFIX) ?: ""
        database = AppDatabase.getDatabase(requireContext())

        recyclerView = view.findViewById(R.id.recyclerViewGroups)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        lifecycleScope.launch {
            val allSongs = SongRepository(database).getAllSongs()

            // このフォルダの直下にある、次のサブフォルダ名を集める
            val nextSegments = allSongs.mapNotNull { song ->
                if (song.folderPath == pathPrefix || song.folderPath.isBlank()) return@mapNotNull null
                if (pathPrefix.isNotBlank() && !song.folderPath.startsWith("$pathPrefix/")) return@mapNotNull null
                val remainder = if (pathPrefix.isBlank()) song.folderPath else song.folderPath.removePrefix("$pathPrefix/")
                remainder.substringBefore("/")
            }.filter { it.isNotBlank() }.distinct().sorted()

            // このフォルダ直下にある曲の数
            val songsHereCount = allSongs.count { it.folderPath == pathPrefix }

            val items = mutableListOf<String>()
            items.addAll(nextSegments.map { "📁 $it" })
            if (songsHereCount > 0) {
                items.add("🎵 このフォルダの曲(${songsHereCount}曲)")
            }

            if (items.isEmpty()) {
                items.add("(音楽ファイルが見つかりません)")
            }

            recyclerView.adapter = GroupAdapter(items) { label ->
                when {
                    label.startsWith("📁 ") -> {
                        val segment = label.removePrefix("📁 ")
                        val newPrefix = if (pathPrefix.isBlank()) segment else "$pathPrefix/$segment"
                        parentFragmentManager.beginTransaction()
                            .replace(R.id.fragmentContainer, newInstance(newPrefix))
                            .addToBackStack(null)
                            .commit()
                    }
                    label.startsWith("🎵 ") -> {
                        val detailFragment = FilteredSongsFragment.newInstance("folder", pathPrefix)
                        parentFragmentManager.beginTransaction()
                            .replace(R.id.fragmentContainer, detailFragment)
                            .addToBackStack(null)
                            .commit()
                    }
                }
            }
            RecyclerViewFastScrollHelper(recyclerView, view.findViewById(R.id.fastScroller)).attach()
        }
    }
}