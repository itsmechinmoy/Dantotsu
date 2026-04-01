package ani.dantotsu.media.comments

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context.INPUT_METHOD_SERVICE
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.PopupMenu
import androidx.core.animation.doOnEnd
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.buildMarkwon
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.comments.Comment
import ani.dantotsu.connections.comments.CommentResponse
import ani.dantotsu.connections.comments.CommentsAPI
import ani.dantotsu.databinding.DialogEdittextBinding
import ani.dantotsu.databinding.FragmentCommentsBinding
import ani.dantotsu.loadImage
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.setBaseline
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.Section
import io.noties.markwon.editor.MarkwonEditor
import io.noties.markwon.editor.MarkwonEditorTextWatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@SuppressLint("ClickableViewAccessibility")
class CommentsFragment : Fragment() {
    lateinit var binding: FragmentCommentsBinding
    lateinit var activity: MediaDetailsActivity
    private var interactionState = InteractionState.NONE
    private var commentWithInteraction: CommentItem? = null
    private val section = Section()
    private val adapter = GroupieAdapter()
    private var tag: Int? = null
    private var filterTag: Int? = null
    private var mediaId: Int = -1
    var mediaName: String = ""
    private var backgroundColor: Int = 0
    var pagesLoaded = 1
    var totalPages = 1
    private var userProgress: Int? = null
    private var totalEpisodesOrChapters: Int? = null
    private var isAnime: Boolean = true
    private var commentsLoaded = false
    private var isAutoFilterOn = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentCommentsBinding.inflate(inflater, container, false)
        binding.commentsLayout.isNestedScrollingEnabled = true
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity = requireActivity() as MediaDetailsActivity

        binding.commentsListContainer.setBaseline(
            activity.navBar,
            0
        )
        //get the media id from the intent
        val mediaId = arguments?.getInt("mediaId") ?: -1
        mediaName = arguments?.getString("mediaName") ?: "unknown"
        if (mediaId == -1) {
            snackString("Invalid Media ID")
            return
        }
        this.mediaId = mediaId
        backgroundColor = (binding.root.background as? ColorDrawable)?.color ?: 0

        val markwon = buildMarkwon(activity, fragment = this@CommentsFragment)

        activity.binding.commentUserAvatar.loadImage(Anilist.avatar)
        val markwonEditor = MarkwonEditor.create(markwon)
        activity.binding.commentInput.addTextChangedListener(
            MarkwonEditorTextWatcher.withProcess(
                markwonEditor
            )
        )

        val isOfflineOrLocal = !ani.dantotsu.isOnline(activity)

        binding.commentsRefresh.setOnRefreshListener {
            val refreshOffline = !ani.dantotsu.isOnline(activity)
            if (refreshOffline) {
                binding.commentsRefresh.isRefreshing = false
                return@setOnRefreshListener
            }
            binding.commentsOfflineText.visibility = View.GONE
            binding.commentsListContainer.visibility = View.VISIBLE
            binding.openRules.visibility = View.VISIBLE
            binding.commentFilter.visibility = View.VISIBLE
            binding.commentSort.visibility = View.VISIBLE
            activity.binding.commentMessageContainer.visibility =
                if (CommentsAPI.authToken != null) View.VISIBLE else View.GONE

            lifecycleScope.launch {
                loadAndDisplayComments()
                binding.commentsRefresh.isRefreshing = false
            }
            activity.binding.commentReplyToContainer.visibility = View.GONE
        }

        binding.commentsList.adapter = adapter
        binding.commentsList.layoutManager = LinearLayoutManager(activity)
        adapter.add(section)

        val model: MediaDetailsViewModel by activityViewModels()
        model.getMedia().observe(viewLifecycleOwner) { newMedia ->
            if (newMedia != null && newMedia.id != 0) {
                isAnime = newMedia.anime != null
                userProgress = newMedia.userProgress
                totalEpisodesOrChapters = if (isAnime)
                    newMedia.anime?.totalEpisodes
                else
                    newMedia.manga?.totalChapters
                updateCurrentProgressButton()

                if (!commentsLoaded || newMedia.id != this.mediaId) {
                    this.mediaId = newMedia.id
                    commentsLoaded = true

                    if (isOfflineOrLocal) {
                        binding.commentsOfflineText.visibility = View.VISIBLE
                        binding.commentsListContainer.visibility = View.GONE
                        binding.openRules.visibility = View.GONE
                        binding.commentFilter.visibility = View.GONE
                        binding.commentSort.visibility = View.GONE
                        binding.commentCurrentProgress.visibility = View.GONE
                        binding.commentsProgressBar.visibility = View.GONE
                        activity.binding.commentMessageContainer.visibility = View.GONE
                    } else if (CommentsAPI.authToken != null) {
                        lifecycleScope.launch {
                            val commentId = arguments?.getInt("commentId")
                            if (commentId != null && commentId > 0) {
                                loadSingleComment(commentId)
                            } else {
                                loadAndDisplayComments()
                            }
                        }
                    } else {
                        activity.binding.commentMessageContainer.visibility = View.GONE
                    }
                }
            }
        }

        binding.commentSort.setOnClickListener { sortView ->
            fun sortComments(sortOrder: String) {
                val groups = section.groups
                when (sortOrder) {
                    "newest" -> groups.sortByDescending { CommentItem.timestampToMillis((it as CommentItem).comment.timestamp) }
                    "oldest" -> groups.sortBy { CommentItem.timestampToMillis((it as CommentItem).comment.timestamp) }
                    "highest_rated" -> groups.sortByDescending { (it as CommentItem).comment.upvotes - it.comment.downvotes }
                    "lowest_rated" -> groups.sortBy { (it as CommentItem).comment.upvotes - it.comment.downvotes }
                }
                section.update(groups)
            }

            val popup = PopupMenu(activity, sortView)
            popup.setOnMenuItemClickListener { item ->
                val sortOrder = when (item.itemId) {
                    R.id.comment_sort_newest -> "newest"
                    R.id.comment_sort_oldest -> "oldest"
                    R.id.comment_sort_highest_rated -> "highest_rated"
                    R.id.comment_sort_lowest_rated -> "lowest_rated"
                    else -> return@setOnMenuItemClickListener false
                }
                PrefManager.setVal(PrefName.CommentSortOrder, sortOrder)
                if (totalPages > pagesLoaded) {
                    lifecycleScope.launch {
                        loadAndDisplayComments()
                        activity.binding.commentReplyToContainer.visibility = View.GONE
                    }
                } else {
                    sortComments(sortOrder)
                }
                binding.commentsList.scrollToPosition(0)
                true
            }
            popup.inflate(R.menu.comments_sort_menu)
            popup.show()
        }
        binding.openRules.setOnClickListener {
            activity.customAlertDialog().apply {
                setTitle("Commenting Rules")
                    .setMessage(
                        "🚨 BREAK ANY RULE = YOU'RE GONE\n\n" +
                                "1. NO RACISM, DISCRIMINATION, OR HATE SPEECH\n" +
                                "2. NO SPAMMING OR SELF-PROMOTION\n" +
                                "3. ABSOLUTELY NO NSFW CONTENT\n" +
                                "4. ENGLISH ONLY – NO EXCEPTIONS\n" +
                                "5. NO IMPERSONATION, HARASSMENT, OR ABUSE\n" +
                                "6. NO ILLEGAL CONTENT OR EXTREME DISRESPECT TOWARDS ANY FANDOM\n" +
                                "7. DO NOT REQUEST OR SHARE REPOSITORIES/EXTENSIONS\n" +
                                "8. SPOILERS ALLOWED ONLY WITH SPOILER TAGS AND A WARNING\n" +
                                "9. NO SEXUALIZING OR INAPPROPRIATE COMMENTS ABOUT MINOR CHARACTERS\n" +
                                "10. IF IT'S WRONG, DON'T POST IT!\n\n"
                    )
                setNegButton("I Understand") {}
                show()
            }
        }

        binding.commentFilter.setOnClickListener {
            activity.customAlertDialog().apply {
                val customView = DialogEdittextBinding.inflate(layoutInflater)
                setTitle("Enter a chapter/episode number tag")
                setCustomView(customView.root)
                setPosButton("OK") {
                    val text = customView.dialogEditText.text.toString()
                    filterTag = text.toIntOrNull()
                    updateCurrentProgressButton()
                    lifecycleScope.launch {
                        loadAndDisplayComments()
                    }
                }
                setNeutralButton("Clear") {
                    filterTag = null
                    updateCurrentProgressButton()
                    lifecycleScope.launch {
                        loadAndDisplayComments()
                    }
                }
                setNegButton("Cancel") {}
                show()
            }
        }

        binding.commentCurrentProgress.setOnClickListener {
            val progress = userProgress ?: return@setOnClickListener
            if (progress <= 0) return@setOnClickListener
            if (filterTag != null && filterTag != progress) {
                filterTag = null
                isAutoFilterOn = false
            } else {
                isAutoFilterOn = !isAutoFilterOn
            }
            updateCurrentProgressButton()
            lifecycleScope.launch {
                loadAndDisplayComments()
            }
        }

        binding.commentCurrentProgress.setOnLongClickListener {
            val progress = userProgress ?: return@setOnLongClickListener false
            if (progress <= 0) return@setOnLongClickListener false
            val total = totalEpisodesOrChapters ?: progress
            val maxEp = maxOf(total, progress)
            val label = if (isAnime) "Ep" else "Ch"

            val items = Array(maxEp) { i -> "$label ${i + 1}" }
            val currentSelection = if (filterTag != null) filterTag!! - 1 else progress - 1
            activity.customAlertDialog().apply {
                setTitle("Filter by ${if (isAnime) "Episode" else "Chapter"}")
                singleChoiceItems(items, currentSelection) { selected ->
                    filterTag = selected + 1
                    isAutoFilterOn = true
                    updateCurrentProgressButton()
                    lifecycleScope.launch {
                        loadAndDisplayComments()
                    }
                }
                show()
            }
            true
        }

        var isFetching = false
        binding.commentsList.setOnTouchListener(
            object : View.OnTouchListener {
                override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                    if (event?.action == MotionEvent.ACTION_UP) {
                        if (!binding.commentsList.canScrollVertically(1) && !isFetching &&
                            (binding.commentsList.layoutManager as LinearLayoutManager).findLastVisibleItemPosition() == (binding.commentsList.adapter!!.itemCount - 1)
                        ) {
                            if (pagesLoaded < totalPages && totalPages > 1) {
                                binding.commentBottomRefresh.visibility = View.VISIBLE
                                loadMoreComments()
                                lifecycleScope.launch {
                                    kotlinx.coroutines.delay(1000)
                                    withContext(Dispatchers.Main) {
                                        binding.commentBottomRefresh.visibility = View.GONE
                                    }
                                }
                            } else {
                                //snackString("No more comments") fix spam?
                                Logger.log("No more comments")
                            }
                        }
                    }
                    return false
                }

                private fun loadMoreComments() {
                    isFetching = true
                    lifecycleScope.launch {
                        val comments = fetchComments()
                        comments?.comments?.forEach { comment ->
                            updateUIWithComment(comment)
                        }
                        totalPages = comments?.totalPages ?: 1
                        pagesLoaded++
                        isFetching = false
                    }
                }

                private suspend fun fetchComments(): CommentResponse? {
                    return withContext(Dispatchers.IO) {
                        CommentsAPI.getCommentsForId(
                            mediaId,
                            pagesLoaded + 1,
                            getEffectiveFilter(),
                            PrefManager.getVal(PrefName.CommentSortOrder, "newest")
                        )
                    }
                }
                //adds additional comments to the section
                private suspend fun updateUIWithComment(comment: Comment) {
                    withContext(Dispatchers.Main) {
                        section.add(
                            CommentItem(
                                comment,
                                buildMarkwon(activity, fragment = this@CommentsFragment),
                                section,
                                this@CommentsFragment,
                                backgroundColor,
                                0
                            )
                        )
                    }
                }
            })

        activity.binding.commentInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }

            override fun afterTextChanged(s: android.text.Editable?) {
                if ((activity.binding.commentInput.text.length) > 300) {
                    activity.binding.commentInput.text.delete(
                        300,
                        activity.binding.commentInput.text.length
                    )
                    snackString("Comment cannot be longer than 300 characters")
                }
            }
        })

        activity.binding.commentInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val targetWidth = activity.binding.commentInputLayout.width -
                        activity.binding.commentLabel.width -
                        activity.binding.commentSend.width -
                        activity.binding.commentUserAvatar.width - 12 + 16
                val anim = ValueAnimator.ofInt(activity.binding.commentInput.width, targetWidth)
                anim.addUpdateListener { valueAnimator ->
                    val layoutParams = activity.binding.commentInput.layoutParams
                    layoutParams.width = valueAnimator.animatedValue as Int
                    activity.binding.commentInput.layoutParams = layoutParams
                }
                anim.duration = 300

                anim.start()
                anim.doOnEnd {
                    activity.binding.commentLabel.visibility = View.VISIBLE
                    activity.binding.commentSend.visibility = View.VISIBLE
                    activity.binding.commentLabel.animate().translationX(0f).setDuration(300)
                        .start()
                    activity.binding.commentSend.animate().translationX(0f).setDuration(300).start()

                }
            }

            activity.binding.commentLabel.setOnClickListener {
                //alert dialog to enter a number, with a cancel and ok button
                activity.customAlertDialog().apply {
                    val customView = DialogEdittextBinding.inflate(layoutInflater)
                    setTitle("Enter a chapter/episode number tag")
                    setCustomView(customView.root)
                    setPosButton("OK") {
                        val text = customView.dialogEditText.text.toString()
                        tag = text.toIntOrNull()
                        if (tag == null) {
                            activity.binding.commentLabel.background = ResourcesCompat.getDrawable(
                                resources,
                                R.drawable.ic_label_off_24,
                                null
                            )
                        } else {
                            activity.binding.commentLabel.background = ResourcesCompat.getDrawable(
                                resources,
                                R.drawable.ic_label_24,
                                null
                            )
                        }
                    }
                    setNeutralButton("Clear") {
                        tag = null
                        activity.binding.commentLabel.background = ResourcesCompat.getDrawable(
                            resources,
                            R.drawable.ic_label_off_24,
                            null
                        )
                    }
                    setNegButton("Cancel") {
                        tag = null
                        activity.binding.commentLabel.background = ResourcesCompat.getDrawable(
                            resources,
                            R.drawable.ic_label_off_24,
                            null
                        )
                    }
                    show()
                }
            }
        }

        activity.binding.commentSend.setOnClickListener {
            if (CommentsAPI.isBanned) {
                snackString("You are banned from commenting :(")
                return@setOnClickListener
            }

            if (PrefManager.getVal(PrefName.FirstComment)) {
                showCommentRulesDialog()
            } else {
                showTagDialogThenProcess()
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onResume() {
        super.onResume()
        tag = null
        section.groups.forEach {
            if (it is CommentItem && it.containsGif()) {
                it.notifyChanged()
            }
        }
    }

    enum class InteractionState {
        NONE, EDIT, REPLY
    }
    /**
     * Loads and displays the comments
     * Called when the activity is created
     * Or when the user refreshes the comments
     */
    private fun getEffectiveFilter(): Int? = when {
        filterTag != null -> filterTag
        isAutoFilterOn && userProgress != null && userProgress!! > 0 -> userProgress
        else -> null
    }

    private fun updateCurrentProgressButton() {
        val progress = userProgress ?: 0
        if (progress <= 0) {
            binding.commentCurrentProgress.visibility = View.GONE
            return
        }
        val label = if (isAnime) "Ep" else "Ch"
        val isManualFilter = filterTag != null && filterTag != progress
        val activeFilter = filterTag ?: progress

        val badge = binding.commentCurrentProgress
        when {
            isManualFilter -> {
                badge.text = "$label $activeFilter  ✕"
                badge.alpha = 1f
                val primaryColor = resolveColorAttr(com.google.android.material.R.attr.colorPrimary)
                badge.setTextColor(
                    resolveColorAttr(com.google.android.material.R.attr.colorOnPrimary)
                )
                badge.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 16f * resources.displayMetrics.density
                    setColor(primaryColor)
                }
            }
            isAutoFilterOn -> {
                badge.text = "$label $progress"
                badge.alpha = 1f
                badge.setTextColor(
                    resolveColorAttr(android.R.attr.textColorPrimary)
                )
                badge.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 16f * resources.displayMetrics.density
                    setColor(android.graphics.Color.TRANSPARENT)
                    setStroke(
                        (1f * resources.displayMetrics.density).toInt(),
                        android.graphics.Color.WHITE
                    )
                }
            }
            else -> {
                badge.text = "$label $progress"
                badge.alpha = 0.33f
                badge.setTextColor(
                    resolveColorAttr(android.R.attr.textColorPrimary)
                )
                badge.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 16f * resources.displayMetrics.density
                    setColor(android.graphics.Color.TRANSPARENT)
                    setStroke(
                        (1f * resources.displayMetrics.density).toInt(),
                        android.graphics.Color.WHITE
                    )
                }
            }
        }
        badge.visibility = View.VISIBLE
    }

    private fun resolveColorAttr(attr: Int): Int {
        val typedArray = activity.obtainStyledAttributes(intArrayOf(attr))
        val color = typedArray.getColor(0, 0)
        typedArray.recycle()
        return color
    }

    private fun showTagDialogThenProcess() {
        if (interactionState == InteractionState.EDIT) {
            processComment()
            return
        }

        val commentText = activity.binding.commentInput.text.toString()
        if (commentText.isEmpty()) {
            snackString("Comment cannot be empty")
            return
        }

        val label = if (isAnime) "episode" else "chapter"
        val total = totalEpisodesOrChapters
        val defaultProgress = userProgress ?: 0

        activity.customAlertDialog().apply {
            val customView = DialogEdittextBinding.inflate(layoutInflater)
            if (defaultProgress > 0) {
                customView.dialogEditText.setText(defaultProgress.toString())
            }
            customView.dialogEditText.hint = if (total != null && total > 0)
                "1–$total"
            else
                "$label number"
            setTitle("Tag $label (optional)")
            setCustomView(customView.root)
            setPosButton("Send") {
                val entered = customView.dialogEditText.text.toString().toIntOrNull()
                if (entered != null && total != null && total > 0 && entered > total) {
                    snackString("Tag cannot exceed total ${label}s ($total)")
                    tag = null
                } else {
                    tag = entered
                }
                activity.binding.commentLabel.background = if (tag != null)
                    ResourcesCompat.getDrawable(resources, R.drawable.ic_label_24, null)
                else
                    ResourcesCompat.getDrawable(resources, R.drawable.ic_label_off_24, null)
                processComment()
            }
            setNeutralButton("No tag") {
                tag = null
                activity.binding.commentLabel.background =
                    ResourcesCompat.getDrawable(resources, R.drawable.ic_label_off_24, null)
                processComment()
            }
            setNegButton(R.string.cancel) {}
            show()
        }
    }

    private suspend fun loadAndDisplayComments() {
        binding.commentsProgressBar.visibility = View.VISIBLE
        binding.commentsList.visibility = View.GONE
        section.clear()
        pagesLoaded = 1

        val sortOrder = PrefManager.getVal(PrefName.CommentSortOrder, "newest")
        val effectiveFilter = getEffectiveFilter()
        
        val comments = withContext(Dispatchers.IO) {
            CommentsAPI.getCommentsForId(
                mediaId,
                page = 1,
                tag = effectiveFilter,
                sort = null
            )
        }

        comments?.comments?.forEach { comment ->
            withContext(Dispatchers.Main) {
                section.add(
                    CommentItem(
                        comment,
                        buildMarkwon(activity, fragment = this@CommentsFragment),
                        section,
                        this@CommentsFragment,
                        backgroundColor,
                        0
                    )
                )
            }
        }

        totalPages = comments?.totalPages ?: 1
        binding.commentsProgressBar.visibility = View.GONE
        binding.commentsList.visibility = View.VISIBLE
    }

    private suspend fun loadSingleComment(commentId: Int) {
        binding.commentsProgressBar.visibility = View.VISIBLE
        binding.commentsList.visibility = View.GONE
        section.clear()

        val comment = withContext(Dispatchers.IO) {
            CommentsAPI.getSingleComment(commentId)
        }
        if (comment != null) {
            withContext(Dispatchers.Main) {
                section.add(
                    CommentItem(
                        comment,
                        buildMarkwon(activity, fragment = this@CommentsFragment),
                        section,
                        this@CommentsFragment,
                        backgroundColor,
                        0
                    )
                )
            }
        }

        binding.commentsProgressBar.visibility = View.GONE
        binding.commentsList.visibility = View.VISIBLE
    }

    private fun sortComments(comments: List<Comment>?): List<Comment> {
        if (comments == null) return emptyList()
        return when (PrefManager.getVal(PrefName.CommentSortOrder, "newest")) {
            "newest" -> comments.sortedByDescending { CommentItem.timestampToMillis(it.timestamp) }
            "oldest" -> comments.sortedBy { CommentItem.timestampToMillis(it.timestamp) }
            "highest_rated" -> comments.sortedByDescending { it.upvotes - it.downvotes }
            "lowest_rated" -> comments.sortedBy { it.upvotes - it.downvotes }
            else -> comments
        }
    }

    /**
     * Resets the old state of the comment input
     * @return the old state
     */
    private fun resetOldState(): InteractionState {
        val oldState = interactionState
        interactionState = InteractionState.NONE
        return when (oldState) {
            InteractionState.EDIT -> {
                activity.binding.commentReplyToContainer.visibility = View.GONE
                activity.binding.commentInput.setText("")
                val imm = activity.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(activity.binding.commentInput.windowToken, 0)
                commentWithInteraction?.editing(false)
                InteractionState.EDIT
            }

            InteractionState.REPLY -> {
                activity.binding.commentReplyToContainer.visibility = View.GONE
                activity.binding.commentInput.setText("")
                val imm = activity.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(activity.binding.commentInput.windowToken, 0)
                commentWithInteraction?.replying(false)
                InteractionState.REPLY
            }

            else -> {
                InteractionState.NONE
            }
        }
    }

    /**
     * Callback from the comment item to edit the comment
     * Called every time the edit button is clicked
     * @param comment the comment to edit
     */
    fun editCallback(comment: CommentItem) {
        if (resetOldState() == InteractionState.EDIT) return
        commentWithInteraction = comment
        activity.binding.commentInput.setText(comment.comment.content)
        activity.binding.commentInput.requestFocus()
        activity.binding.commentInput.setSelection(activity.binding.commentInput.text.length)
        val imm = activity.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(activity.binding.commentInput, InputMethodManager.SHOW_IMPLICIT)
        interactionState = InteractionState.EDIT
    }

    /**
     * Callback from the comment item to reply to the comment
     * Called every time the reply button is clicked
     * @param comment the comment to reply to
     */
    fun replyCallback(comment: CommentItem) {
        if (resetOldState() == InteractionState.REPLY) return
        commentWithInteraction = comment
        activity.binding.commentReplyToContainer.visibility = View.VISIBLE
        activity.binding.commentInput.requestFocus()
        activity.binding.commentInput.setSelection(activity.binding.commentInput.text.length)
        val imm = activity.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(activity.binding.commentInput, InputMethodManager.SHOW_IMPLICIT)
        interactionState = InteractionState.REPLY

    }

    fun replyTo(comment: CommentItem, username: String) {
        if (comment.isReplying) {
            activity.binding.commentReplyToContainer.visibility = View.VISIBLE
            activity.binding.commentReplyTo.text = getString(R.string.replying_to, username)
            activity.binding.commentReplyToCancel.setOnClickListener {
                comment.replying(false)
                replyCallback(comment)
                activity.binding.commentReplyToContainer.visibility = View.GONE
            }
        } else {
            activity.binding.commentReplyToContainer.visibility = View.GONE
        }
    }

    /**
     * Callback from the comment item to view the replies to the comment
     * @param comment the comment to view the replies of
     */
    fun viewReplyCallback(comment: CommentItem) {
        lifecycleScope.launch {
            val replies = withContext(Dispatchers.IO) {
                CommentsAPI.getRepliesFromId(comment.comment.commentId)
            }

            replies?.comments?.forEach {
                val depth =
                    if (comment.commentDepth + 1 > comment.MAX_DEPTH) comment.commentDepth else comment.commentDepth + 1
                val section =
                    if (comment.commentDepth + 1 > comment.MAX_DEPTH) comment.parentSection else comment.repliesSection
                if (depth >= comment.MAX_DEPTH) comment.registerSubComment(it.commentId)
                val newCommentItem = CommentItem(
                    it,
                    buildMarkwon(activity, fragment = this@CommentsFragment),
                    section,
                    this@CommentsFragment,
                    backgroundColor,
                    depth
                )

                section.add(newCommentItem)
            }
        }
    }

    /**
     * Shows the comment rules dialog
     * Called when the user tries to comment for the first time
     */
    private fun showCommentRulesDialog() {
        activity.customAlertDialog().apply {
            setTitle("Commenting Rules")
                .setMessage(
                    "🚨 BREAK ANY RULE = YOU'RE GONE\n\n" +
                            "1. NO RACISM, DISCRIMINATION, OR HATE SPEECH\n" +
                            "2. NO SPAMMING OR SELF-PROMOTION\n" +
                            "3. ABSOLUTELY NO NSFW CONTENT\n" +
                            "4. ENGLISH ONLY – NO EXCEPTIONS\n" +
                            "5. NO IMPERSONATION, HARASSMENT, OR ABUSE\n" +
                            "6. NO ILLEGAL CONTENT OR EXTREME DISRESPECT TOWARDS ANY FANDOM\n" +
                            "7. DO NOT REQUEST OR SHARE REPOSITORIES/EXTENSIONS\n" +
                            "8. SPOILERS ALLOWED ONLY WITH SPOILER TAGS AND A WARNING\n" +
                            "9. NO SEXUALIZING OR INAPPROPRIATE COMMENTS ABOUT MINOR CHARACTERS\n" +
                            "10. IF IT'S WRONG, DON'T POST IT!\n\n"
                )
            setPosButton("I Understand") {
                PrefManager.setVal(PrefName.FirstComment, false)
                showTagDialogThenProcess()
            }
            setNegButton(R.string.cancel)
            show()
        }
    }

    private fun processComment() {
        val commentText = activity.binding.commentInput.text.toString()
        if (commentText.isEmpty()) {
            snackString("Comment cannot be empty")
            return
        }

        activity.binding.commentInput.text.clear()
        lifecycleScope.launch {
            if (interactionState == InteractionState.EDIT) {
                handleEditComment(commentText)
            } else {
                handleNewComment(commentText)
                tag = null
                activity.binding.commentLabel.background = ResourcesCompat.getDrawable(
                    resources,
                    R.drawable.ic_label_off_24,
                    null
                )
            }
            resetOldState()
        }
    }

    private suspend fun handleEditComment(commentText: String) {
        val success = withContext(Dispatchers.IO) {
            CommentsAPI.editComment(
                commentWithInteraction?.comment?.commentId ?: return@withContext false, commentText
            )
        }
        if (success) {
            updateCommentInSection(commentText)
        }
    }

    private fun updateCommentInSection(commentText: String) {
        val groups = section.groups
        groups.forEach { item ->
            if (item is CommentItem && item.comment.commentId == commentWithInteraction?.comment?.commentId) {
                updateCommentItem(item, commentText)
                snackString("Comment edited")
            }
        }
    }

    private fun updateCommentItem(item: CommentItem, commentText: String) {
        item.comment.content = commentText
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        item.comment.timestamp = dateFormat.format(System.currentTimeMillis())
        item.notifyChanged()
    }

    /**
     * Handles the new user-added comment
     * @param commentText the text of the comment
     */
    private suspend fun handleNewComment(commentText: String) {
        val success = withContext(Dispatchers.IO) {
            CommentsAPI.comment(
                mediaId,
                if (interactionState == InteractionState.REPLY) commentWithInteraction?.comment?.commentId else null,
                commentText,
                tag
            )
        }
        success?.let {
            if (interactionState == InteractionState.REPLY) {
                if (commentWithInteraction == null) return@let
                val section =
                    if (commentWithInteraction!!.commentDepth + 1 > commentWithInteraction!!.MAX_DEPTH) commentWithInteraction?.parentSection else commentWithInteraction?.repliesSection
                val depth =
                    if (commentWithInteraction!!.commentDepth + 1 > commentWithInteraction!!.MAX_DEPTH) commentWithInteraction!!.commentDepth else commentWithInteraction!!.commentDepth + 1
                if (depth >= commentWithInteraction!!.MAX_DEPTH) commentWithInteraction!!.registerSubComment(
                    it.commentId
                )
                section?.add(
                    if (commentWithInteraction!!.commentDepth + 1 > commentWithInteraction!!.MAX_DEPTH) 0 else section.itemCount,
                    CommentItem(
                        it,
                        buildMarkwon(activity, fragment = this@CommentsFragment),
                        section,
                        this@CommentsFragment,
                        backgroundColor,
                        depth
                    )
                )
            } else {
                section.add(
                    0,
                    CommentItem(
                        it,
                        buildMarkwon(activity, fragment = this@CommentsFragment),
                        section,
                        this@CommentsFragment,
                        backgroundColor,
                        0
                    )
                )
            }
        }
    }
}
