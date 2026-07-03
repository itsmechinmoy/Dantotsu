package ani.dantotsu.shizuku

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.res.AssetFileDescriptor
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.UserHandle
import android.util.Log
import ani.dantotsu.BuildConfig
import eu.kanade.tachiyomi.extension.installer.ACTION_INSTALL_RESULT
import rikka.shizuku.SystemServiceHelper
import java.io.OutputStream
import kotlin.system.exitProcess

class ShellInterface : IShellInterface.Stub() {

    private val context = createContext()
    private val userId = UserHandle::class.java
        .getMethod("myUserId")
        .invoke(null) as Int
    private val packageName = BuildConfig.APPLICATION_ID

    @SuppressLint("PrivateApi")
    override fun install(apk: AssetFileDescriptor) {
        val pmInterface = Class.forName("android.content.pm.IPackageManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, SystemServiceHelper.getSystemService("package"))

        val packageInstaller = Class.forName("android.content.pm.IPackageManager")
            .getMethod("getPackageInstaller")
            .invoke(pmInterface)

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            try {
                val installFlags = this::class.java.getField("installFlags")
                installFlags.set(
                    this,
                    installFlags.getInt(this) or REPLACE_EXISTING_INSTALL_FLAG,
                )
            } catch (e: ReflectiveOperationException) {
                Log.w("ShellInterface", "Unable to set installFlags via reflection; continuing without REPLACE_EXISTING_INSTALL_FLAG", e)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setInstallerPackageName(packageName)
            }
        }

        val sessionId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            packageInstaller::class.java.getMethod(
                "createSession",
                PackageInstaller.SessionParams::class.java,
                String::class.java,
                String::class.java,
                Int::class.java,
            ).invoke(packageInstaller, params, packageName, packageName, userId) as Int
        } else {
            packageInstaller::class.java.getMethod(
                "createSession",
                PackageInstaller.SessionParams::class.java,
                String::class.java,
                Int::class.java,
            ).invoke(packageInstaller, params, packageName, userId) as Int
        }

        val session = packageInstaller::class.java
            .getMethod("openSession", Int::class.java)
            .invoke(packageInstaller, sessionId)

        session::class.java.getMethod(
            "openWrite",
            String::class.java,
            Long::class.java,
            Long::class.java,
        )
            .invoke(session, "extension", 0L, apk.length)
            .let { it as ParcelFileDescriptor }
            .let { fd ->
                val outputStream = try {
                    val revocable = Class.forName("android.os.SystemProperties")
                        .getMethod("getBoolean", String::class.java, Boolean::class.java)
                        .invoke(null, "fw.revocable_fd", false) as Boolean

                    if (revocable) {
                        ParcelFileDescriptor.AutoCloseOutputStream(fd)
                    } else {
                        Class.forName("android.os.FileBridge\$FileBridgeOutputStream")
                            .getConstructor(ParcelFileDescriptor::class.java)
                            .newInstance(fd) as OutputStream
                    }
                } catch (e: Exception) {
                    fd.close()
                    throw e
                }

                outputStream.use { output ->
                    apk.createInputStream().use { input -> input.copyTo(output) }
                }
            }

        val statusIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_INSTALL_RESULT).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE,
        )

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
            session::class.java.getMethod("commit", IntentSender::class.java, Boolean::class.java)
                .invoke(session, statusIntent.intentSender, false)
        } else {
            session::class.java.getMethod("commit", IntentSender::class.java)
                .invoke(session, statusIntent.intentSender)
        }
    }

    override fun destroy() {
        exitProcess(0)
    }

    @SuppressLint("PrivateApi")
    private fun createContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
        val systemMain = activityThread.getMethod("systemMain").invoke(null)
        val systemContext = activityThread.getMethod("getSystemContext").invoke(systemMain) as Context

        val shellUserHandle = UserHandle::class.java
            .getConstructor(Int::class.java)
            .newInstance(userId)

        val shellContext = systemContext::class.java.getMethod(
            "createPackageContextAsUser",
            String::class.java,
            Int::class.java,
            UserHandle::class.java,
        ).invoke(
            systemContext,
            "com.android.shell",
            Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            shellUserHandle,
        ) as Context

        return shellContext.createPackageContext("com.android.shell", 0)
    }
}

private const val REPLACE_EXISTING_INSTALL_FLAG = 0x00000002
