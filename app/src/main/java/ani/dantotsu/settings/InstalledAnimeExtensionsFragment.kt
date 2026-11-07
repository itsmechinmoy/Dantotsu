package ani.dantotsu.settings

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.R
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.databinding.FragmentExtensionsBinding
import ani.dantotsu.others.LanguageMapper.Companion.getLanguageName
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.settings.extensionprefs.AnimeSourcePreferencesFragment
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog

import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import kotlinx.coroutines.launch
import rx.android.schedulers.AndroidSchedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale


class InstalledAnimeExtensionsFragment : BaseInstalledExtensionsFragment(), SearchQueryHandler {
    private var _binding: FragmentExtensionsBinding? = null
    private val binding get() = _binding!!
    private lateinit var extensionsRecyclerView: RecyclerView
    private val skipIcons: Boolean = PrefManager.getVal(PrefName.SkipExtensionIcons)
    private val animeExtensionManager: AnimeExtensionManager = Injekt.get()
    private val extensionsAdapter = AnimeExtensionsAdapter(
        { pkg ->
            val name = pkg.name
            val allSettings = pkg.sources.filterIsInstance<ConfigurableAnimeSource>()
            if (allSettings.isNotEmpty()) {
                val openPrefFragment: (ConfigurableAnimeSource) -> Unit = { source ->
                    changeExtensionsUIVisibility(false, name)
                    val fragment = AnimeSourcePreferencesFragment().getInstance(source.id) {
                        changeExtensionsUIVisibility(true)
                    }
                    parentFragmentManager.beginTransaction()
                        .setCustomAnimations(R.anim.slide_up, R.anim.slide_down)
                        .replace(R.id.fragmentExtensionsContainer, fragment)
                        .addToBackStack(null)
                        .commit()
                }
                showConfigurableSourceSelection(
                    requireContext(),
                    allSettings,
                    { getLanguageName(it.lang) },
                    openPrefFragment,
                    { changeExtensionsUIVisibility(true) }
                )
            } else {
                Toast.makeText(requireContext(), "Source is not configurable", Toast.LENGTH_SHORT).show()
            }
        },
        { pkg ->
            if (isAdded) {
                animeExtensionManager.uninstallExtension(pkg.pkgName)
                snackString("Extension uninstalled")
            }
        }, { pkg ->
            if (isAdded) {
                if (pkg.hasUpdate) {
                    subscribeExtensionUpdate(requireContext(), animeExtensionManager.updateExtension(pkg))
                } else {
                    snackString("No update available")
                }
            }
        }, skipIcons
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExtensionsBinding.inflate(inflater, container, false)

        extensionsRecyclerView = binding.allExtensionsRecyclerView
        extensionsRecyclerView.layoutManager = SafeLinearLayoutManager(requireContext())
        extensionsRecyclerView.adapter = extensionsAdapter

        setupExtensionsDragAndDrop(
            extensionsRecyclerView,
            onMoveItem = { fromPosition, toPosition ->
                val newList = extensionsAdapter.currentList.toMutableList().apply {
                    add(toPosition, removeAt(fromPosition))
                }
                extensionsAdapter.submitList(newList)
            },
            onClearView = {
                extensionsAdapter.updatePref()
            }
        )


        lifecycleScope.launch {
            animeExtensionManager.installedExtensionsFlow.collect { extensions ->
                extensionsAdapter.updateData(sortToAnimeSourcesList(extensions))
            }
        }
        return binding.root
    }


    private fun sortToAnimeSourcesList(inpt: List<AnimeExtension.Installed>): List<AnimeExtension.Installed> {
        val sourcesMap = inpt.associateBy { it.name }
        val orderedSources = AnimeSources.pinnedAnimeSources.mapNotNull { name ->
            sourcesMap[name]
        }
        return orderedSources + inpt.filter { !AnimeSources.pinnedAnimeSources.contains(it.name) }
    }

    override fun onDestroyView() {
        super.onDestroyView();_binding = null
    }

    override fun updateContentBasedOnQuery(query: String?) {
        extensionsAdapter.filter(
            query ?: "",
            sortToAnimeSourcesList(animeExtensionManager.installedExtensionsFlow.value)
        )
    }

    override fun notifyDataChanged() { // Do nothing
    }

    private class AnimeExtensionsAdapter(
        private val onSettingsClicked: (AnimeExtension.Installed) -> Unit,
        private val onUninstallClicked: (AnimeExtension.Installed) -> Unit,
        private val onUpdateClicked: (AnimeExtension.Installed) -> Unit,
        val skipIcons: Boolean
    ) : ListAdapter<AnimeExtension.Installed, AnimeExtensionsAdapter.ViewHolder>(
        DIFF_CALLBACK_INSTALLED
    ) {

        fun updateData(newExtensions: List<AnimeExtension.Installed>) {
            submitList(newExtensions)
        }

        fun updatePref() {
            val map = currentList.map { it.name }
            PrefManager.setVal(PrefName.AnimeSourcesOrder, map)
            AnimeSources.pinnedAnimeSources = map
            AnimeSources.performReorderAnimeSources()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_extension, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val extension = getItem(position)
            val nsfw = if (extension.isNsfw) "(18+)" else ""
            val lang = getLanguageName(extension.lang)
            holder.extensionNameTextView.text = extension.name
            val majorLib = extension.libVersion.toInt()
            val displayVersion = if (extension.versionName.startsWith("$majorLib.") || extension.versionName.startsWith("$majorLib")) {
                extension.versionName
            } else {
                "$majorLib.${extension.versionName}"
            }
            val repo = extension.repoName ?: extension.repository?.let { ani.dantotsu.parsers.ExtensionRepoMetaHelper.getRepoBadgeName(it) } ?: ani.dantotsu.parsers.ExtensionRepoMetaHelper.getInstalledRepo(extension.pkgName)?.let { ani.dantotsu.parsers.ExtensionRepoMetaHelper.getRepoBadgeName(it) }
            val repoBadge = if (!repo.isNullOrBlank()) "@$repo" else ""
            val versionText = listOf(lang, displayVersion, nsfw, repoBadge).filter { it.isNotBlank() }.joinToString(" ")
            holder.extensionVersionTextView.text = versionText
            if (!skipIcons) {
                holder.extensionIconImageView.setImageDrawable(extension.icon)
            }
            if (extension.hasUpdate) {
                holder.updateView.isVisible = true
            } else {
                holder.updateView.isVisible = false
            }
            holder.deleteView.setOnClickListener {
                onUninstallClicked(extension)
            }
            holder.updateView.setOnClickListener {
                onUpdateClicked(extension)
            }
            holder.settingsImageView.setOnClickListener {
                onSettingsClicked(extension)
            }
        }

        fun filter(query: String, currentList: List<AnimeExtension.Installed>) {
            val filteredList = ArrayList<AnimeExtension.Installed>()
            for (extension in currentList) {
                if (extension.name.lowercase(Locale.ROOT).contains(query.lowercase(Locale.ROOT))) {
                    filteredList.add(extension)
                }
            }
            if (filteredList != currentList)
                submitList(filteredList)
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val extensionNameTextView: TextView = view.findViewById(R.id.extensionNameTextView)
            val extensionVersionTextView: TextView =
                view.findViewById(R.id.extensionVersionTextView)
            val settingsImageView: ImageView = view.findViewById(R.id.settingsImageView)
            val extensionIconImageView: ImageView = view.findViewById(R.id.extensionIconImageView)
            val deleteView: ImageView = view.findViewById(R.id.deleteTextView)
            val updateView: ImageView = view.findViewById(R.id.updateTextView)
        }

        companion object {
            val DIFF_CALLBACK_INSTALLED =
                object : DiffUtil.ItemCallback<AnimeExtension.Installed>() {
                    override fun areItemsTheSame(
                        oldItem: AnimeExtension.Installed,
                        newItem: AnimeExtension.Installed
                    ): Boolean {
                        return oldItem.pkgName == newItem.pkgName
                    }

                    override fun areContentsTheSame(
                        oldItem: AnimeExtension.Installed,
                        newItem: AnimeExtension.Installed
                    ): Boolean {
                        return oldItem == newItem
                    }
                }
        }
    }

}
