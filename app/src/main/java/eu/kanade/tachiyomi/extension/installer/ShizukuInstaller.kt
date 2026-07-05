package eu.kanade.tachiyomi.extension.installer

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import ani.dantotsu.BuildConfig
import ani.dantotsu.R
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.extension.InstallStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ani.dantotsu.shizuku.IShellInterface
import ani.dantotsu.shizuku.ShellInterface
import rikka.shizuku.Shizuku

class ShizukuInstaller(private val service: Service) : Installer(service) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var shellInterface: IShellInterface? = null

    private val shizukuArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(service, ShellInterface::class.java),
        )
            .tag("shizuku_service")
            .processNameSuffix("shizuku_service")
            .debuggable(BuildConfig.DEBUG)
            .daemon(false)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shellInterface = IShellInterface.Stub.asInterface(service)
            ready = true
            checkQueue()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellInterface = null
            ready = false
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)

            if (status == PackageInstaller.STATUS_SUCCESS) {
                continueQueue(InstallStep.Installed)
            } else {
                Logger.log("Failed to install extension $packageName: $message")
                continueQueue(InstallStep.Error)
            }
        }
    }

    private val shizukuDeadListener = Shizuku.OnBinderDeadListener {
        Logger.log("Shizuku was killed prematurely")
        service.stopSelf()
    }

    private val shizukuPermissionListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Shizuku.bindUserService(shizukuArgs, connection)
                } else {
                    service.stopSelf()
                }
                Shizuku.removeRequestPermissionResultListener(this)
            }
        }
    }

    fun initShizuku() {
        if (ready) return
        if (!Shizuku.pingBinder()) {
            Logger.log("Shizuku is not ready to use")
            toast(service.getString(R.string.ext_installer_shizuku_stopped))
            service.stopSelf()
            return
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            Shizuku.bindUserService(shizukuArgs, connection)
        } else {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
    }

    override var ready = false

    override fun processEntry(entry: Entry) {
        super.processEntry(entry)
        try {
            service.contentResolver.openAssetFileDescriptor(entry.uri, "r")?.use {
                shellInterface?.install(it)
                    ?: throw Exception("Shell interface is not available")
            } ?: throw Exception("Failed to open asset file descriptor")
            service.contentResolver.delete(entry.uri, null, null)
        } catch (e: Exception) {
            Logger.log("Failed to install extension ${entry.downloadId} ${entry.uri}\n$e")
            continueQueue(InstallStep.Error)
        }
    }

    override fun cancelEntry(entry: Entry): Boolean = getActiveEntry() != entry

    override fun onDestroy() {
        Shizuku.removeBinderDeadListener(shizukuDeadListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        if (Shizuku.pingBinder()) {
            try {
                Shizuku.unbindUserService(shizukuArgs, connection, true)
            } catch (e: Exception) {
                Logger.log("Failed to unbind shizuku service\n$e")
            }
        }
        try {
            service.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Logger.log("Failed to unregister receiver\n$e")
        }
        scope.cancel()
        super.onDestroy()
    }

    init {
        Shizuku.addBinderDeadListener(shizukuDeadListener)

        ContextCompat.registerReceiver(
            service,
            receiver,
            IntentFilter(ACTION_INSTALL_RESULT),
            ContextCompat.RECEIVER_EXPORTED,
        )

        initShizuku()
    }
}

private const val SHIZUKU_PERMISSION_REQUEST_CODE = 14045
const val ACTION_INSTALL_RESULT = "${BuildConfig.APPLICATION_ID}.ACTION_INSTALL_RESULT"
