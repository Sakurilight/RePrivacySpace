package com.reprivacyspace.app.util

import android.app.ActivityThread
import android.content.pm.PackageManager
import android.content.pm.UserInfo
import android.os.Binder
import android.os.Build
import android.os.Parcel
import android.os.Process
import android.os.ServiceManager
import android.os.SystemProperties
import com.reprivacyspace.app.BuildConfig
import com.reprivacyspace.app.bean.SystemUserInfo
import com.reprivacyspace.app.constant.ConfigConstant
import com.reprivacyspace.app.hook.HookMain
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.lang.reflect.Method

/**
 * The app <-> system_server control channel.
 *
 * The original implementation hooked PackageManagerService#getInstallerPackageName(String).
 * That method stopped existing in the AOSP PackageManagerService years ago (Android 13 moved
 * it to ComputerEngine, Android 15/16/17 keep it in IPackageManagerBase and route the rest
 * through ComputerEngine), so on Android 15+ the hook threw NoSuchMethodError *before any
 * hook was installed*, which is exactly what makes the module report "not active".
 *
 * On top of that, HMA-OSS (a Zygisk module which also runs inside system_server) hooks
 * ComputerEngine#getInstallerPackageName and AppsFilterImpl#shouldFilterApplication with
 * its own native ART hooking engine.  Hooking the same ART methods from LSPosed makes the
 * two engines fight over the same entry point, so the module must not touch those methods.
 *
 * Instead we answer the binder transaction itself:
 *
 *   client: PackageManager#getInstallerPackageName("<command>")
 *   server: IPackageManager$Stub#onTransact(TRANSACTION_getInstallerPackageName, ...)
 *
 * The hook sits *above* every Java-level method, so it works on every Android version that
 * still has the AIDL transaction, cannot be shadowed by HMA-OSS, and never calls into PMS.
 * A Java-level hook on getInstallerPackageName is kept as a fallback for the (rare) ROMs
 * where the generated Stub/tables are unavailable.
 */
class ConfigServer : XC_MethodHook() {

    companion object {
        const val QUERY_SERVER_VERSION = "serverVersion"
        const val MIGRATE_OLD_CONFIG_FILE = "migrateOldConfigFile"
        const val QUERY_CONFIG = "queryConfig"
        const val UPDATE_CONFIG = "updateConfig:"
        const val REBOOT_THE_SYSTEM = "rebootTheSystem"
        const val GET_USERS = "getUsers"
        const val FORCE_STOP = "forceStop:"
        const val LAUNCH_APP = "launchApp:"

        const val EXEC_SUCCEED = "1"
        const val EXEC_FAILED = "0"

        private const val IPM_DESCRIPTOR = "android.content.pm.IPackageManager"
        private const val MAX_PAYLOAD = 512 * 1024
        private const val CONTROL_PERMISSION = "com.reprivacyspace.app.permission.CONTROL"
    }

    @Volatile
    private var ownUid = -1

    @Volatile
    private var settingsUid = -1

    @Volatile
    private var userInfoListCache: Collection<*>? = null

    @Volatile
    private var installerTransactCode = -1
    private var transactHooked = false

    /** Diagnostics counters so a single log dump shows which channel actually fired. */
    private val transactSeen = java.util.concurrent.atomic.AtomicInteger()
    private val methodSeen = java.util.concurrent.atomic.AtomicInteger()

    fun start(classLoader: ClassLoader) {
        // uid lookups are deferred: at handleLoadPackage() time the package manager may not
        // be published yet on some releases, and caching -1 would break the channel.
        XLog.i(
            "ConfigServer start: sdk=${Build.VERSION.SDK_INT} version=${BuildConfig.VERSION_CODE}"
        )

        transactHooked = hookTransact(classLoader)
        // Always install the Java-level fallback as well: if the binder transaction hook is
        // bypassed (OEM binder subclass, ART quirk), the AIDL implementation still answers.
        val methodHooks = hookInstallerMethods(classLoader)
        XLog.i("channel ready: transact=$transactHooked methodHooks=$methodHooks")
        hookUserManager(classLoader)
    }

    // ------------------------------------------------------------------ binder channel

    private fun hookTransact(classLoader: ClassLoader): Boolean {
        val stub = classLoader.loadClassSafe("android.content.pm.IPackageManager\$Stub")
            ?: run {
                XLog.e("IPackageManager\$Stub not found.")
                return false
            }
        val code = HookUtil.staticIntField(stub, "TRANSACTION_getInstallerPackageName")
        if (code == null) {
            XLog.e("TRANSACTION_getInstallerPackageName not found.")
            return false
        }
        installerTransactCode = code

        var count = 0
        // The AIDL impl overrides onTransact (PackageManagerService$IPackageManagerImpl) and
        // calls super; hooking both the override and the generated Stub covers both dispatch
        // paths, plus Binder itself for completeness.
        for (className in HookUtil.ipmImplClassNameArray) {
            var current: Class<*>? = classLoader.loadClassSafe(className) ?: continue
            while (current != null && current != Any::class.java) {
                for (method in current.declaredMethods) {
                    if (method.name == "onTransact" && method.parameterCount == 4) {
                        try {
                            XposedBridge.hookMethod(method, transactHook)
                            count++
                            XLog.i("hooked ${current.name}#onTransact")
                        } catch (t: Throwable) {
                            XLog.e(t, "Hook ${current.name}#onTransact failed.")
                        }
                    }
                }
                current = current.superclass
            }
        }
        XLog.i("onTransact hooks: $count (transaction code $code)")
        return count > 0
    }

    private val transactHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val code = param.args.getOrNull(0) as? Int ?: return
            if (code != installerTransactCode) return

            val data = param.args.getOrNull(1) as? Parcel ?: return
            val reply = param.args.getOrNull(2) as? Parcel ?: return

            val start = data.dataPosition()
            val command = try {
                data.enforceInterface(IPM_DESCRIPTOR)
                data.readString()
            } catch (t: Throwable) {
                null
            }
            // Any getInstallerPackageName transaction that reaches this hook is logged a few
            // times, so a log dump can tell "hook never fired" apart from "hook fired but the
            // caller was rejected".
            val callingUid = Binder.getCallingUid()
            val seen = transactSeen.incrementAndGet()
            if (seen <= 8) {
                XLog.i(
                    "transact hit #$seen: cmd=${command?.take(48)} uid=$callingUid " +
                            "ownUid=${ownUidOrResolve()} settingsUid=${settingsUidOrResolve()} " +
                            "perm=${holdsControlPermission()}"
                )
            }

            // Not one of our commands: it is a real installer query.  Put the parcel back
            // and let the framework (and HMA-OSS) handle it exactly as before.
            if (command.isNullOrEmpty() || !isInternalCommand(command)) {
                try {
                    data.setDataPosition(start)
                } catch (ignored: Throwable) {
                }
                return
            }

            val trusted = isTrustedCaller()
            if (seen <= 8) {
                XLog.i("transact #$seen trusted=$trusted")
            }
            if (!trusted) {
                try {
                    data.setDataPosition(start)
                } catch (ignored: Throwable) {
                }
                return
            }

            val payload = resolvePayload(command) ?: run {
                try {
                    data.setDataPosition(start)
                } catch (ignored: Throwable) {
                }
                return
            }

            try {
                reply.writeNoException()
                reply.writeString(payload)
                param.result = true
                XLog.i("served via transact: '$command' (${payload.length} chars)")
            } catch (t: Throwable) {
                XLog.e(t, "Reply for '$command' failed.")
                try {
                    data.setDataPosition(start)
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    // ------------------------------------------------------------- java-level fallback

    private fun hookInstallerMethods(classLoader: ClassLoader): Int {
        val candidates = mutableSetOf<Class<*>>()
        HookUtil.loadPms(classLoader)?.let { candidates.add(it) }
        classLoader.loadClassSafe("com.android.server.pm.IPackageManagerBase")?.let { candidates.add(it) }
        HookUtil.pmsClassNameArray.forEach { classLoader.loadClassSafe(it)?.let { c -> candidates.add(c) } }

        var count = 0
        for (candidate in candidates) {
            var current: Class<*>? = candidate
            while (current != null && current != Any::class.java) {
                for (method in current.declaredMethods) {
                    if (method.name == "getInstallerPackageName"
                        && method.parameterCount == 1
                        && method.parameterTypes[0] == String::class.java
                    ) {
                        try {
                            XposedBridge.hookMethod(method, methodHook)
                            count++
                            XLog.i("hooked ${current.name}#getInstallerPackageName(String)")
                        } catch (t: Throwable) {
                            XLog.e(t, "Hook ${current.name}#getInstallerPackageName failed.")
                        }
                    }
                }
                current = current.superclass
            }
        }
        XLog.i("installer method hooks: $count")
        return count
    }

    private val methodHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val command = param.args.getOrNull(0) as? String ?: return
            if (!isInternalCommand(command)) return
            val callingUid = Binder.getCallingUid()
            val trusted = isTrustedCaller()
            val seen = methodSeen.incrementAndGet()
            if (seen <= 8) {
                XLog.i(
                    "method hit #$seen: cmd=$command uid=$callingUid " +
                            "ownUid=${ownUidOrResolve()} trusted=$trusted"
                )
            }
            if (!trusted) return
            val payload = resolvePayload(command) ?: return
            param.result = payload
            XLog.d("served(method) '$command' (${payload.length} chars)")
        }
    }

    // ------------------------------------------------------------------- commands

    private fun resolvePayload(command: String): String? {
        return when {
            command == QUERY_SERVER_VERSION -> BuildConfig.VERSION_CODE.toString()
            command == MIGRATE_OLD_CONFIG_FILE -> {
                tryMigrateOldConfig()
                ""
            }
            command == QUERY_CONFIG -> queryConfig()
            command == REBOOT_THE_SYSTEM -> {
                try {
                    SystemProperties.set("sys.powerctl", "reboot")
                } catch (t: Throwable) {
                    XLog.e(t, "reboot failed.")
                }
                ""
            }
            command == GET_USERS -> usersJson()
            command.startsWith(UPDATE_CONFIG) -> {
                if (command.length <= MAX_PAYLOAD) {
                    updateConfig(command.substring(UPDATE_CONFIG.length))
                } else {
                    XLog.e("config payload too large: ${command.length}")
                }
                ""
            }
            command.startsWith(FORCE_STOP) -> forceStopPackage(command.substring(FORCE_STOP.length))
            command.startsWith(LAUNCH_APP) -> launchApp(command.substring(LAUNCH_APP.length))
            else -> null
        }
    }

    /**
     * Starts an app from system_server.  The UI uses this when its own
     * getLaunchIntentForPackage()/startActivity() was filtered, which happens on Android 15+
     * once the target is hidden from the caller by the visibility filter.
     */
    private fun launchApp(packageName: String): String {
        val identity = Binder.clearCallingIdentity()
        try {
            val context: android.content.Context =
                ActivityThread.currentActivityThread()?.systemContext ?: return EXEC_FAILED
            val pm = context.packageManager
            // Resolve the launcher activity as system_server.  Building an explicit component
            // mirrors the quick-launch path that is known to work, and avoids the caller-side
            // visibility filter that makes getLaunchIntentForPackage() return null.
            val query = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
            val activityInfo = pm.queryIntentActivities(query, 0)
                .firstOrNull()?.activityInfo
            if (activityInfo == null) {
                XLog.e("launchApp($packageName): no launcher activity")
                return EXEC_FAILED
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                component = android.content.ComponentName(activityInfo.packageName, activityInfo.name)
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
            }
            context.startActivity(intent)
            XLog.i("launched $packageName (${activityInfo.name}) from system_server")
            return EXEC_SUCCEED
        } catch (t: Throwable) {
            XLog.e(t, "launchApp($packageName) failed.")
            return EXEC_FAILED
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun usersJson(): String {
        val systemUsers = mutableListOf<SystemUserInfo>()
        userInfoListCache?.forEach { userInfo ->
            if (userInfo is UserInfo) {
                systemUsers.add(SystemUserInfo(id = userInfo.id, name = userInfo.name))
            }
        }
        return JsonHelper.systemUserInfoListAdapter().toJson(systemUsers)
    }

    // ---------------------------------------------------------------- user manager

    private fun hookUserManager(classLoader: ClassLoader) {
        val userManagerClass = classLoader.loadClassSafe("com.android.server.pm.UserManagerService")
        if (userManagerClass == null) {
            XLog.e("UserManagerService not found; multi-user list will be empty.")
            return
        }
        try {
            userManagerClass.declaredMethods
                .filter { it.checkIsGetUsersMethod() }
                .forEach { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            userInfoListCache = param.result as? Collection<*>?
                        }
                    })
                }
            XLog.i("UserManagerService hooks installed.")
        } catch (t: Throwable) {
            XLog.e(t, "UserManagerService hook failed.")
        }
    }

    private fun Method.checkIsGetUsersMethod(): Boolean {
        if (name != "getUsers") return false
        return parameterCount == 1 && parameterTypes[0] == Boolean::class.javaPrimitiveType ||
                parameterCount == 3 && parameterTypes.all { it == Boolean::class.javaPrimitiveType }
    }

    // ------------------------------------------------------------------- helpers

    private fun isTrustedCaller(): Boolean {
        // Preferred: a signature-level permission that only our own builds can hold.  This
        // does not depend on resolving our uid or the caller's package name at all.
        if (holdsControlPermission()) return true
        val uid = Binder.getCallingUid()
        if (uid == android.os.Process.myUid()) return true
        if (uid == ownUidOrResolve()) return true
        if (uid == settingsUidOrResolve()) return true
        // Last resort: resolve the calling uid's package names and compare, which does not
        // depend on our own uid being resolvable.
        val packages = packagesForUid(uid) ?: return false
        return packages.contains(BuildConfig.APPLICATION_ID) ||
                packages.contains("com.android.settings")
    }

    private fun holdsControlPermission(): Boolean {
        return try {
            val systemContext: android.content.Context =
                ActivityThread.currentActivityThread()?.systemContext ?: return false
            systemContext.checkCallingPermission(CONTROL_PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
    }

    private fun packagesForUid(uid: Int): List<String>? {
        return try {
            ActivityThread.getPackageManager()?.getPackagesForUid(uid)?.toList()
        } catch (t: Throwable) {
            null
        }
    }

    @Synchronized
    private fun ownUidOrResolve(): Int {
        if (ownUid <= 0) {
            ownUid = packageUid(BuildConfig.APPLICATION_ID)
        }
        return ownUid
    }

    @Synchronized
    private fun settingsUidOrResolve(): Int {
        if (settingsUid <= 0) {
            settingsUid = packageUid("com.android.settings")
        }
        return settingsUid
    }

    private fun isInternalCommand(command: String): Boolean {
        return command == QUERY_SERVER_VERSION
                || command == MIGRATE_OLD_CONFIG_FILE
                || command == QUERY_CONFIG
                || command == REBOOT_THE_SYSTEM
                || command == GET_USERS
                || command.startsWith(UPDATE_CONFIG)
                || command.startsWith(FORCE_STOP)
                || command.startsWith(LAUNCH_APP)
    }

    private fun packageUid(packageName: String): Int {
        return try {
            ActivityThread.getPackageManager().getPackageUid(packageName, 0, 0)
        } catch (t: Throwable) {
            -1
        }
    }

    private fun queryConfig(): String {
        val configFile = File("${ConfigConstant.CONFIG_FILE_FOLDER}${ConfigConstant.CONFIG_FILE_JSON}")
        return try {
            configFile.readText()
        } catch (t: Throwable) {
            ""
        }
    }

    private fun updateConfig(configJson: String) {
        val configFile = File("${ConfigConstant.CONFIG_FILE_FOLDER}${ConfigConstant.CONFIG_FILE_JSON}")
        configFile.parentFile?.mkdirs()
        try {
            val configData = JsonHelper.configAdapter().fromJson(configJson) ?: return
            HookMain.updateConfigData(configData)
            configFile.writeText(configJson)
            XLog.i("config updated (${configJson.length} chars)")
        } catch (t: Throwable) {
            XLog.e(t, "Update config error.")
        }
    }

    private fun tryMigrateOldConfig() {
        val newConfigFile =
            File("${ConfigConstant.CONFIG_FILE_FOLDER}${ConfigConstant.CONFIG_FILE_JSON}")
        if (newConfigFile.exists()) return
        val legacyFiles = listOf(
            "${ConfigConstant.CONFIG_FILE_FOLDER_ORIGINAL}${ConfigConstant.CONFIG_FILE_JSON}",
            "${ConfigConstant.CONFIG_FILE_FOLDER_LEGACY}${ConfigConstant.CONFIG_FILE_JSON}",
            "${ConfigConstant.CONFIG_FILE_FOLDER_LEGACY_RE}${ConfigConstant.CONFIG_FILE_JSON}"
        )
        for (legacy in legacyFiles) {
            val source = File(legacy)
            if (!source.exists()) continue
            newConfigFile.parentFile?.mkdirs()
            try {
                source.copyTo(newConfigFile)
                XLog.i("migrated config from $legacy")
                return
            } catch (t: Throwable) {
                XLog.e(t, "migrate config from $legacy failed.")
            }
        }
    }

    private fun forceStopPackage(packageName: String): String {
        XLog.d("forceStopPackage = $packageName")
        // ActivityManagerService#forceStopPackage() enforces FORCE_STOP_PACKAGES against
        // Binder.getCallingUid().  We are running inside the app's binder transaction, so the
        // call must be made with system_server's identity or it throws SecurityException.
        val identity = Binder.clearCallingIdentity()
        try {
            val am = activityManagerService()
            if (am != null) {
                // Invoke the AIDL interface method: it exists both on the local
                // ActivityManagerService and on a BinderProxy.
                val iface = Class.forName("android.app.IActivityManager")
                val method = iface.getMethod(
                    "forceStopPackage",
                    String::class.java,
                    Int::class.javaPrimitiveType
                )
                method.invoke(am, packageName, 0)
                XLog.i("force-stopped $packageName")
                restartHomeIfNeeded(packageName)
                return EXEC_SUCCEED
            }
            XLog.e("ActivityManager unavailable, cannot force-stop $packageName")
        } catch (t: Throwable) {
            XLog.e(t, "forceStopPackage $packageName via AMS failed.")
        } finally {
            Binder.restoreCallingIdentity(identity)
        }

        // Last resort: the "am" command runs as system, which owns FORCE_STOP_PACKAGES.
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("am", "force-stop", packageName))
            if (process.waitFor() == 0) EXEC_SUCCEED else EXEC_FAILED
        } catch (t: Throwable) {
            XLog.e(t, "forceStopPackage $packageName via am failed.")
            EXEC_FAILED
        }
    }

    /**
     * When the launcher is force-stopped the home screen goes blank until something asks for
     * it again; start HOME explicitly so "restart launcher" has a visible effect.
     */
    private fun restartHomeIfNeeded(packageName: String) {
        try {
            val context: android.content.Context =
                ActivityThread.currentActivityThread()?.systemContext ?: return
            val home = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_HOME)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val currentHome =
                context.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
            if (currentHome == packageName) {
                context.startActivity(home)
                XLog.i("restarted home: $packageName")
            }
        } catch (t: Throwable) {
            XLog.e(t, "restart home failed.")
        }
    }

    /**
     * Returns the local ActivityManagerService instance.  A plain ServiceManager.getService()
     * can hand back a BinderProxy, which has no forceStopPackage() method, so resolve the
     * AIDL interface first (ActivityManager.getService() does exactly that).
     */
    private fun activityManagerService(): Any? {
        try {
            val activityManager = Class.forName("android.app.ActivityManager")
            activityManager.getMethod("getService").invoke(null)?.let { return it }
        } catch (t: Throwable) {
            XLog.d("ActivityManager.getService() failed: ${t.message}")
        }
        return try {
            val binder = ServiceManager.getService("activity") ?: return null
            val stub = Class.forName("android.app.IActivityManager\$Stub")
            stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
        } catch (t: Throwable) {
            XLog.e(t, "asInterface(activity) failed.")
            null
        }
    }
}
