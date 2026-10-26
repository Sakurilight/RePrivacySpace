package com.reprivacyspace.app.hook.impl

import android.app.ActivityManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.pm.*
import android.os.Build
import com.reprivacyspace.app.hook.HookMain
import com.reprivacyspace.app.hook.Hooker
import com.reprivacyspace.app.util.XLog
import com.reprivacyspace.app.util.tryLoadClass
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

object SettingsAppHookImpl : Hooker, XC_MethodHook() {

    override fun start(classLoader: ClassLoader) {
        val packageManagerProxyClass = try {
            classLoader.tryLoadClass("android.content.pm.IPackageManager\$Stub\$Proxy")
        } catch (e: ClassNotFoundException) {
            XLog.e(e, "SettingsAppHookImpl start failed.")
            null
        }
        if (packageManagerProxyClass != null) {
            for (method in packageManagerProxyClass.declaredMethods) {
                when (method.name) {
                    "getInstalledPackages", "getInstalledApplications", "getInstalledModules", "queryIntentActivities" -> {
                        try {
                            XposedBridge.hookMethod(method, this)
                        } catch (t: Throwable) {
                            XLog.e(t, "Hook ${method.name} failed.")
                        }
                    }
                    else -> {}
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                UsageStatsManager::class.java,
                "queryUsageStats",
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                this
            )
        } catch (t: Throwable) {
            XLog.e(t, "queryUsageStats hook failed.")
        }

        try {
            XposedHelpers.findAndHookMethod(
                ActivityManager::class.java,
                "getRunningAppProcesses",
                this
            )
        } catch (t: Throwable) {
            XLog.e(t, "getRunningAppProcesses hook failed.")
        }
    }

    override fun afterHookedMethod(param: MethodHookParam) {
        val hiddenAppList = HookMain.configData.hiddenAppList
        if (hiddenAppList.isEmpty()) return
        try {
            when (param.method.name) {
                "getInstalledPackages" -> filterPackageList(param, hiddenAppList)
                "getInstalledApplications" -> filterPackageList(param, hiddenAppList)
                "getInstalledModules" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    filterModuleList(param, hiddenAppList)
                }
                "queryIntentActivities" -> filterResolveList(param, hiddenAppList)
                "queryUsageStats" -> filterUsageStats(param, hiddenAppList)
                "getRunningAppProcesses" -> filterProcesses(param, hiddenAppList)
                else -> {}
            }
        } catch (t: Throwable) {
            XLog.e(t, "filtering ${param.method.name} failed.")
        }
    }

    private fun filterPackageList(param: MethodHookParam, hidden: Set<String>) {
        val slice = param.result as? ParceledListSlice<*> ?: return
        @Suppress("UNCHECKED_CAST")
        val list = slice.list as? MutableList<Any?> ?: return
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            val packageName = when (item) {
                is PackageInfo -> item.packageName
                is ApplicationInfo -> item.packageName
                else -> null
            } ?: continue
            if (hidden.contains(packageName)) {
                iterator.remove()
                XLog.d("settings: hid $packageName")
            }
        }
    }

    private fun filterModuleList(param: MethodHookParam, hidden: Set<String>) {
        val list = param.result as? MutableList<*> ?: return
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item is ModuleInfo && hidden.contains(item.packageName)) {
                iterator.remove()
                XLog.d("settings: hid module ${item.packageName}")
            }
        }
    }

    private fun filterResolveList(param: MethodHookParam, hidden: Set<String>) {
        val slice = param.result as? ParceledListSlice<*> ?: return
        @Suppress("UNCHECKED_CAST")
        val list = slice.list as? MutableList<Any?> ?: return
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item !is ResolveInfo) continue
            val packageName = item.activityInfo?.packageName
                ?: item.serviceInfo?.packageName
                ?: item.providerInfo?.packageName
                ?: continue
            if (hidden.contains(packageName)) {
                iterator.remove()
                XLog.d("settings: hid $packageName")
            }
        }
    }

    private fun filterUsageStats(param: MethodHookParam, hidden: Set<String>) {
        val list = param.result as? MutableList<*> ?: return
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item is UsageStats && hidden.contains(item.packageName)) {
                iterator.remove()
                XLog.d("settings: hid ${item.packageName}")
            }
        }
    }

    private fun filterProcesses(param: MethodHookParam, hidden: Set<String>) {
        val result = param.result as? List<*> ?: return
        param.result = result.filter { item ->
            val process = item as? ActivityManager.RunningAppProcessInfo ?: return@filter true
            process.pkgList.none { hidden.contains(it) }
        }
    }
}
