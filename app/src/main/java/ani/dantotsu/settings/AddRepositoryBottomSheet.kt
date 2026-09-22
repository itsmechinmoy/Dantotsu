package ani.dantotsu.settings

import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.BottomSheetAddRepositoryBinding
import ani.dantotsu.databinding.ItemRepoBinding
import ani.dantotsu.media.MediaType
import ani.dantotsu.parsers.ExtensionRepoMeta
import ani.dantotsu.parsers.ExtensionRepoMetaHelper
import ani.dantotsu.parsers.novel.NovelExtensionManager
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.customAlertDialog
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.viewbinding.BindableItem
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.util.system.openInBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class RepoItem(
    val url: String,
    private val mediaType: MediaType,
    val onRemove: (String, MediaType) -> Unit
) : BindableItem<ItemRepoBinding>() {
    override fun getLayout() = R.layout.item_repo

    override fun bind(viewBinding: ItemRepoBinding, position: Int) {
        viewBinding.root.tag = url
        val initialMeta = ExtensionRepoMetaHelper.getCachedMeta(url)
        bindMeta(viewBinding, initialMeta)

        CoroutineScope(Dispatchers.Main).launch {
            val meta = withContext(Dispatchers.IO) {
                ExtensionRepoMetaHelper.getMeta(url)
            }
            if (viewBinding.root.tag == url) {
                bindMeta(viewBinding, meta)
            }
        }

        viewBinding.repoDeleteImageView.setOnClickListener {
            onRemove(url, mediaType)
        }
        viewBinding.repoCopyImageView.setOnClickListener {
            viewBinding.repoCopyImageView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            copyToClipboard(url, true)
        }
    }

    private fun bindMeta(viewBinding: ItemRepoBinding, meta: ExtensionRepoMeta?) {
        val context = viewBinding.root.context
        val cleanUrl = ExtensionRepoMetaHelper.cleanShownUrl(url)
        val name = meta?.name?.takeIf { it.isNotBlank() }
            ?: meta?.shortName?.takeIf { it.isNotBlank() }
            ?: cleanUrl

        val repoPageUrl = ExtensionRepoMetaHelper.getRepoPageUrl(url, meta)
        val displayUrl = repoPageUrl?.removePrefix("https://")?.removePrefix("http://") ?: cleanUrl

        viewBinding.repoNameTextView.text = name
        viewBinding.repoUrlTextView.text = displayUrl

        val openRepoPage: (View) -> Unit = {
            val target = repoPageUrl ?: meta?.website?.takeIf { it.isNotBlank() }
            if (target != null) {
                context.openInBrowser(target)
            }
        }

        if (repoPageUrl != null || (meta?.website?.isNotBlank() == true)) {
            viewBinding.repoNameTextView.setOnClickListener(openRepoPage)
            viewBinding.repoUrlTextView.setOnClickListener(openRepoPage)
        } else {
            viewBinding.repoNameTextView.setOnClickListener(null)
            viewBinding.repoUrlTextView.setOnClickListener(null)
        }

        if (repoPageUrl != null) {
            viewBinding.repoGithubImageView.isVisible = true
            viewBinding.repoGithubImageView.setOnClickListener {
                context.openInBrowser(repoPageUrl)
            }
        } else {
            viewBinding.repoGithubImageView.isGone = true
        }

        val websiteUrl = meta?.website?.takeIf { it.isNotBlank() }
        if (websiteUrl != null && websiteUrl != repoPageUrl && websiteUrl != "$repoPageUrl/") {
            viewBinding.repoWebsiteImageView.isVisible = true
            viewBinding.repoWebsiteImageView.setOnClickListener {
                context.openInBrowser(websiteUrl)
            }
        } else {
            viewBinding.repoWebsiteImageView.isGone = true
        }

        val discordUrl = meta?.discord?.takeIf { it.isNotBlank() }
        if (discordUrl != null) {
            viewBinding.repoDiscordImageView.isVisible = true
            viewBinding.repoDiscordImageView.setOnClickListener {
                context.openInBrowser(discordUrl)
            }
        } else {
            viewBinding.repoDiscordImageView.isGone = true
        }
    }

    override fun initializeViewBinding(view: View): ItemRepoBinding {
        return ItemRepoBinding.bind(view)
    }
}

class AddRepositoryBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetAddRepositoryBinding? = null
    private val binding get() = _binding!!
    private var mediaType: MediaType = MediaType.ANIME
    private var onRepositoryAdded: ((String, MediaType) -> Unit)? = null
    private var repositories: MutableList<String> = mutableListOf()
    private var onRepositoryRemoved: ((String, MediaType) -> Unit)? = null
    private var adapter: GroupieAdapter = GroupieAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAddRepositoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.repositoriesRecyclerView.adapter = adapter
        binding.repositoriesRecyclerView.layoutManager = LinearLayoutManager(
            context,
            LinearLayoutManager.VERTICAL,
            false
        )
        adapter.addAll(repositories.map { RepoItem(it, mediaType, ::onRepositoryRemoved) })

        binding.repositoryInput.hint = when (mediaType) {
            MediaType.ANIME -> getString(R.string.anime_add_repository)
            MediaType.MANGA -> getString(R.string.manga_add_repository)
            MediaType.NOVEL -> getString(R.string.novel_add_repository)
        }

        binding.addButton.setOnClickListener {
            val input = binding.repositoryInput.text.toString()
            val error = isValidUrl(input)
            if (error == null) {
                acceptUrl(input)
            } else {
                binding.repositoryInput.error = error
            }
        }

        binding.cancelButton.setOnClickListener {
            dismiss()
        }

        binding.repositoryInput.setOnEditorActionListener { textView, action, keyEvent ->
            if (action == EditorInfo.IME_ACTION_DONE ||
                (keyEvent?.action == KeyEvent.ACTION_UP && keyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                val url = textView.text.toString()
                if (url.isNotBlank()) {
                    val error = isValidUrl(url)
                    if (error == null) {
                        acceptUrl(url)
                        return@setOnEditorActionListener true
                    } else {
                        binding.repositoryInput.error = error
                    }
                }
            }
            false
        }
    }

    private fun acceptUrl(url: String) {
        val finalUrl = getRepoUrl(url)
        context?.let { context ->
            addRepoWarning(context) {
                onRepositoryAdded?.invoke(finalUrl, mediaType)
                dismiss()
            }
        }
    }

    private fun isValidUrl(input: String): String? {
        if (input.startsWith("http://") || input.startsWith("https://")) {
            if (mediaType == MediaType.NOVEL) {
                if (!input.removeSuffix("/").endsWith(".json")) {
                    return "URL must end with a .json file"
                }
            } else {
                val clean = input.removeSuffix("/")
                if (!clean.endsWith("index.min.json") && !clean.endsWith("repo.json") && !clean.endsWith("index.json") && !clean.endsWith("index.pb")) {
                    return "URL must end with repo.json or index.json or index.min.json or index.pb"
                }
            }
            return null
        }

        val parts = input.split("/")
        if (parts.size !in 2..3) {
            return "Must be a full URL or in format: username/repo[/branch]"
        }

        val username = parts[0]
        val repo = parts[1]
        val branch = if (parts.size == 3) parts[2] else "repo"

        if (username.isBlank() || repo.isBlank()) {
            return "Username and repository name cannot be empty"
        }
        if (parts.size == 3 && branch.isBlank()) {
            return "Branch name cannot be empty"
        }

        return null
    }

    private fun getRepoUrl(input: String): String {
        if (input.startsWith("http://") || input.startsWith("https://")) {
            return input
        }

        val parts = input.split("/")
        val username = parts[0]
        val repo = parts[1]
        val branch = if (parts.size == 3) parts[2] else "repo"

        return "https://raw.githubusercontent.com/$username/$repo/$branch/repo.json"
    }

    private fun onRepositoryRemoved(url: String, mediaType: MediaType) {
        onRepositoryRemoved?.invoke(url, mediaType)
        repositories.remove(url)
        adapter.update(repositories.map { RepoItem(it, mediaType, ::onRepositoryRemoved) })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun addRepoWarning(context: Context, onRepositoryAdded: () -> Unit) {
            context.customAlertDialog()
                .setTitle(R.string.warning)
                .setMessage(R.string.add_repository_warning)
                .setPosButton(R.string.ok) {
                    onRepositoryAdded.invoke()
                }
                .setNegButton(R.string.cancel) { }
                .show()
        }

        fun addRepo(input: String, mediaType: MediaType) {
            val validLink = if (input.contains("github.com") && input.contains("blob")) {
                input.replace("github.com", "raw.githubusercontent.com")
                    .replace("/blob/", "/")
            } else input

            when (mediaType) {
                MediaType.ANIME -> {
                    val anime =
                        PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos)
                            .plus(validLink)
                    PrefManager.setVal(PrefName.AnimeExtensionRepos, anime)
                    CoroutineScope(Dispatchers.IO).launch {
                        ExtensionRepoMetaHelper.getMeta(validLink)
                        Injekt.get<AnimeExtensionManager>().findAvailableExtensions()
                    }
                }

                MediaType.MANGA -> {
                    val manga =
                        PrefManager.getVal<Set<String>>(PrefName.MangaExtensionRepos)
                            .plus(validLink)
                    PrefManager.setVal(PrefName.MangaExtensionRepos, manga)
                    CoroutineScope(Dispatchers.IO).launch {
                        ExtensionRepoMetaHelper.getMeta(validLink)
                        Injekt.get<MangaExtensionManager>().findAvailableExtensions()
                    }
                }

                MediaType.NOVEL -> {
                    val novel =
                        PrefManager.getVal<Set<String>>(PrefName.NovelExtensionRepos)
                            .plus(validLink)
                    PrefManager.setVal(PrefName.NovelExtensionRepos, novel)
                    CoroutineScope(Dispatchers.IO).launch {
                        ExtensionRepoMetaHelper.getMeta(validLink)
                        Injekt.get<NovelExtensionManager>().findAvailableExtensions()
                    }
                }
            }
        }

        fun removeRepo(input: String, mediaType: MediaType) {
            when (mediaType) {
                MediaType.ANIME -> {
                    val anime =
                        PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos)
                            .minus(input)
                    PrefManager.setVal(PrefName.AnimeExtensionRepos, anime)
                    CoroutineScope(Dispatchers.IO).launch {
                        Injekt.get<AnimeExtensionManager>().findAvailableExtensions()
                    }
                }

                MediaType.MANGA -> {
                    val manga =
                        PrefManager.getVal<Set<String>>(PrefName.MangaExtensionRepos)
                            .minus(input)
                    PrefManager.setVal(PrefName.MangaExtensionRepos, manga)
                    CoroutineScope(Dispatchers.IO).launch {
                        Injekt.get<MangaExtensionManager>().findAvailableExtensions()
                    }
                }

                MediaType.NOVEL -> {
                    val novel =
                        PrefManager.getVal<Set<String>>(PrefName.NovelExtensionRepos)
                            .minus(input)
                    PrefManager.setVal(PrefName.NovelExtensionRepos, novel)
                    CoroutineScope(Dispatchers.IO).launch {
                        Injekt.get<NovelExtensionManager>().findAvailableExtensions()
                    }
                }
            }
        }

        fun newInstance(
            mediaType: MediaType,
            repositories: List<String>,
            onRepositoryAdded: (String, MediaType) -> Unit,
            onRepositoryRemoved: (String, MediaType) -> Unit
        ): AddRepositoryBottomSheet {
            return AddRepositoryBottomSheet().apply {
                this.mediaType = mediaType
                this.repositories.addAll(repositories)
                this.onRepositoryAdded = onRepositoryAdded
                this.onRepositoryRemoved = onRepositoryRemoved
            }
        }
    }
}
