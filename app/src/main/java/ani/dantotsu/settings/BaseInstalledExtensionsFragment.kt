package ani.dantotsu.settings

import android.app.NotificationManager
import android.content.Context
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.R
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.InstallStep
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

open class BaseInstalledExtensionsFragment : Fragment() {

    fun changeExtensionsUIVisibility(show: Boolean, titleName: String? = null) {
        val extActivity = activity as? ExtensionsActivity
        if (extActivity != null && !extActivity.isFinishing && !extActivity.isDestroyed) {
            extActivity.findViewById<ViewPager2>(R.id.viewPager)?.isVisible = show
            extActivity.findViewById<TabLayout>(R.id.tabLayout)?.isVisible = show
            extActivity.findViewById<TextInputLayout>(R.id.searchView)?.isVisible = show
            extActivity.findViewById<ImageView>(R.id.languageselect)?.isVisible = show
            extActivity.findViewById<TextView>(R.id.extensions)?.text =
                if (show) extActivity.getString(R.string.extensions) else titleName ?: ""
            extActivity.findViewById<FrameLayout>(R.id.fragmentExtensionsContainer)?.isGone = show
        }
    }

    fun <T> setupExtensionsRecyclerView(
        recyclerView: RecyclerView,
        adapter: ListAdapter<T, *>,
        updatePref: () -> Unit
    ) {
        recyclerView.layoutManager = SafeLinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        setupExtensionsDragAndDrop(
            recyclerView,
            onMoveItem = { fromPosition, toPosition ->
                val newList = adapter.currentList.toMutableList().apply {
                    add(toPosition, removeAt(fromPosition))
                }
                adapter.submitList(newList)
            },
            onClearView = updatePref
        )
    }

    fun setupExtensionsDragAndDrop(
        recyclerView: RecyclerView,
        onMoveItem: (Int, Int) -> Unit,
        onClearView: () -> Unit
    ) {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.absoluteAdapterPosition
                val toPosition = target.absoluteAdapterPosition
                onMoveItem(fromPosition, toPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.elevation = 8f
                    viewHolder?.itemView?.translationZ = 8f
                }
            }

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                onClearView()
                viewHolder.itemView.elevation = 0f
                viewHolder.itemView.translationZ = 0f
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recyclerView)
    }

    fun subscribeExtensionUpdate(
        context: Context,
        observable: Observable<InstallStep>,
        completeIcon: Int = R.drawable.ic_circle_check
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        observable.observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { installStep ->
                    val builder = NotificationCompat.Builder(
                        context,
                        Notifications.CHANNEL_DOWNLOADER_PROGRESS
                    )
                        .setSmallIcon(R.drawable.ic_round_sync_24)
                        .setContentTitle("Updating extension")
                        .setContentText("Step: $installStep")
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                    notificationManager.notify(1, builder.build())
                },
                { error ->
                    Injekt.get<CrashlyticsInterface>().logException(error)
                    Logger.log(error)
                    val builder = NotificationCompat.Builder(
                        context,
                        Notifications.CHANNEL_DOWNLOADER_ERROR
                    )
                        .setSmallIcon(R.drawable.ic_round_info_24)
                        .setContentTitle("Update failed: ${error.message}")
                        .setContentText("Error: ${error.message}")
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                    notificationManager.notify(1, builder.build())
                    snackString("Update failed: ${error.message}")
                },
                {
                    val builder = NotificationCompat.Builder(
                        context,
                        Notifications.CHANNEL_DOWNLOADER_PROGRESS
                    )
                        .setSmallIcon(completeIcon)
                        .setContentTitle("Update complete")
                        .setContentText("The extension has been successfully updated.")
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                    notificationManager.notify(1, builder.build())
                    snackString("Extension updated")
                }
            )
    }

    fun <T> showConfigurableSourceSelection(
        context: Context,
        allSettings: List<T>,
        getLangName: (T) -> String,
        openPrefFragment: (T) -> Unit,
        onDismissCancel: () -> Unit
    ) {
        if (allSettings.size > 1) {
            val names = allSettings.map { getLangName(it) }.toTypedArray()
            var itemSelected = false
            context.customAlertDialog().apply {
                setTitle("Select a Source")
                singleChoiceItems(names, 0) { which ->
                    itemSelected = true
                    openPrefFragment(allSettings[which])
                }
                onDismiss {
                    if (!itemSelected) {
                        onDismissCancel()
                    }
                }
                show()
            }
        } else {
            openPrefFragment(allSettings[0])
        }
    }
}
