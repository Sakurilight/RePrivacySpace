package com.reprivacyspace.app.hook

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.FileObserver
import com.reprivacyspace.app.BuildConfig
import com.reprivacyspace.app.bean.ConfigData
import com.reprivacyspace.app.constant.ConfigConstant
import com.reprivacyspace.app.hook.impl.*
import com.reprivacyspace.app.util.*
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File

class HookMain : IXposedHookLoadPackage {

    companion object {
        private lateinit var classLoader: ClassLoader
        private var packageName: String? = null
        private var fileObserver: FileObserver? = null
        private val configServer = ConfigServer()

        @Volatile
        var configData: ConfigData = ConfigData.EMPTY
            private set

        fun updateConfigData(data: ConfigData) {
            val sharedUserIdMap = data.sharedUserIdMap
            val whitelistTmp = data.whitelist.toMutableSet()
            val connectedAppsTpm = data.connectedApps.toMutableMap()
            if (!sharedUserIdMap.isNullOrEmpty()) {
                sharedUserIdMap.forEach {
                    if (whitelistTmp.contains(it.key)) {
                        whitelistTmp.add(it.value)
                    }
                }

                val connectedAppsNew = mutableMapOf<String, Set<String>>()
                connectedAppsTpm.forEach {
                    val newSet = it.value.toMutableSet()
                    it.value.forEach { pkg ->
                        val sharedUserId = sharedUserIdMap[pkg]
                        if (!sharedUserId.isNullOrEmpty()) {
                            newSet.add(sharedUserId)
                        }
                    }
                    connectedAppsNew[it.key] = newSet

                    val keySharedUserId = sharedUserIdMap[it.key]
                    if (!keySharedUserId.isNullOrEmpty()) {
                        connectedAppsNew[keySharedUserId] = newSet
                    }
                }
                connectedAppsTpm.putAll(connectedAppsNew)
            }

            configData = data.copy(
                whitelist = whitelistTmp,
                connectedApps = connectedAppsTpm
            )
            XLog.enableLog = data.enableDetailLog
            // uid -> package names can change when apps are installed/removed
            FrameworkHookerApi30Impl.clearCache()
            XLog.i(
                "config loaded: hidden=${configData.hiddenAppList.size} " +
                        "whitelist=${configData.whitelist.size} blind=${configData.blind?.size ?: 0}"
            )
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        packageName = lpparam.packageName
        classLoader = lpparam.classLoader
        val appInfo = lpparam.appInfo
        try {
            when {
                lpparam.packageName == ConfigConstant.ANDROID_FRAMEWORK -> handleSystemServer(lpparam)
                lpparam.packageName == "com.android.settings" -> handleSettings(lpparam)
                appInfo != null && AppHelper.isSystemApp(appInfo) -> handleSystemApp(lpparam)
            }
        } catch (t: Throwable) {
            XLog.e(t, "handleLoadPackage(${lpparam.packageName}) failed.")
        }
    }

    private fun handleSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        XLog.i("PrivacySpace ${BuildConfig.VERSION_NAME} loading into system_server (API ${Build.VERSION.SDK_INT})")

        // 1. The control channel must be installed first: even if a visibility hook below
        //    fails on a future Android release, the module can still report itself active and
        //    the UI keeps working.
        safe("config") { loadConfigDataAndParse() }
        safe("configServer") { configServer.start(lpparam.classLoader) }

        // 2. Package visibility filtering.
        safe("visibility") {
            when {
                Build.VERSION.SDK_INT >= 30 -> FrameworkHookerApi30Impl.start(lpparam.classLoader)
                Build.VERSION.SDK_INT >= 28 -> FrameworkHookerApi28Impl.start(lpparam.classLoader)
                else -> FrameworkHookerApi26Impl.start(lpparam.classLoader)
            }
        }

        // 3. Volume+/Volume-/Power quick launch (1.3.11 feature).
        safe("quickLaunch") { PhoneWindowManagerHooker.start(lpparam.classLoader) }
    }

    private fun handleSettings(lpparam: XC_LoadPackage.LoadPackageParam) {
        safe("settingsHooks") { SettingsAppHookImpl.start(lpparam.classLoader) }
        safe("settingsConfig") {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val context = param.thisObject as? Context ?: return
                        val configClient = ConfigClient(context)
                        MainScope().launch {
                            val config = configClient.queryConfig()
                            if (null != config) {
                                updateConfigData(config)
                            }
                        }
                    }
                })
        }
    }

    private fun handleSystemApp(lpparam: XC_LoadPackage.LoadPackageParam) {
        safe("appConfig") {
            loadConfigDataAndParse()
            startWatchingConfigFiles()
        }
        safe("appHooks") { SpecialAppsHookerImpl.start(lpparam.classLoader) }
    }

    private fun startWatchingConfigFiles() {
        if (null == fileObserver) {
            val file = File(ConfigConstant.CONFIG_FILE_FOLDER)
            file.mkdirs()
            fileObserver =
                object : FileObserver(ConfigConstant.CONFIG_FILE_FOLDER) {
                    override fun onEvent(event: Int, path: String?) {
                        if (event == CLOSE_WRITE && path == ConfigConstant.CONFIG_FILE_JSON) {
                            safe("reloadConfig") { loadConfigDataAndParse() }
                            XLog.i("$packageName reloaded config.json")
                        }
                    }
                }
            fileObserver?.startWatching()
        }
    }

    private fun loadConfigDataAndParse() {
        val configDataNew =
            ConfigHelper.loadConfigWithSystemApp(packageName ?: "") ?: ConfigData.EMPTY
        if (configDataNew != configData) {
            updateConfigData(configDataNew)
        }
    }

    private inline fun <T> safe(tag: String, block: () -> T): T? {
        return try {
            block()
        } catch (t: Throwable) {
            XLog.e(t, "step '$tag' failed (${packageName ?: "?"}).")
            null
        }
    }
}
