package com.reprivacyspace.app.hook.impl

import android.os.Build
import android.os.UserHandle
import com.reprivacyspace.app.hook.HookMain
import com.reprivacyspace.app.hook.Hooker
import com.reprivacyspace.app.util.HookUtil
import com.reprivacyspace.app.util.XLog
import com.reprivacyspace.app.util.loadClassSafe
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Package visibility filtering for Android 11 (API 30) through Android 17 (API 37).
 *
 * Where the decision lives:
 *   11-14 : AppsFilter/AppsFilterImpl#shouldFilterApplication(SettingBase, PackageSetting, int)
 *   15    : com.android.server.pm.AppsFilter was split into AppsFilterBase/AppsFilterImpl,
 *           the class PrivacySpace used to load no longer exists.
 *   16/17 : every PackageManager query funnels through
 *           ComputerEngine#shouldFilterApplication(PackageStateInternal, int, ComponentName,
 *           int, int, boolean, boolean); AppsFilter is only consulted from there.
 *
 * HMA-OSS hooks AppsFilterImpl#shouldFilterApplication with its own native ART hook engine.
 * Hooking the very same ArtMethod from LSPosed makes the two engines overwrite each other's
 * trampolines, which is the "module stopped working next to HMA-OSS" report.  ComputerEngine's
 * funnel is a different method, sits above the AppsFilter cache, and is not touched by
 * HMA-OSS, so it is preferred; AppsFilter stays only as a fallback for ROMs that do not have
 * the funnel.
 */
object FrameworkHookerApi30Impl : XC_MethodHook(), Hooker {

    private const val TAG = "FrameworkHook"

    private lateinit var classLoader: ClassLoader

    /** uid -> first package name; the mapping is stable until reboot/reinstall. */
    private val uidToPackage = ConcurrentHashMap<Int, String>()

    private val lookupCache = ConcurrentHashMap<String, Any>()
    private val hookedSignatures = HashSet<String>()

    /** Guards uid resolution against re-entering the hooked filter (getPackagesForUid does). */
    private val resolvingUids = ThreadLocal<MutableSet<Int>>()

    private val NONE = Any()
    private val INT_CLASS: Class<*> = Int::class.javaPrimitiveType!!

    override fun start(classLoader: ClassLoader) {
        this.classLoader = classLoader
        var hooks = hookComputerEngine(classLoader)
        if (hooks == 0) {
            XLog.i("$TAG: ComputerEngine funnel not found, trying AppsFilter.")
            hooks = hookAppsFilter(classLoader)
        }
        if (hooks == 0) {
            XLog.e("$TAG: no visibility hook could be installed on API ${Build.VERSION.SDK_INT}!")
        } else {
            XLog.i("$TAG: installed $hooks visibility hook(s) on API ${Build.VERSION.SDK_INT}")
        }
    }

    fun clearCache() {
        uidToPackage.clear()
    }

    // ------------------------------------------------------------------- hook install

    private fun hookComputerEngine(classLoader: ClassLoader): Int {
        var count = 0
        for (name in HookUtil.computerEngineClassNameArray) {
            val clazz = classLoader.loadClassSafe(name) ?: continue
            var current: Class<*>? = clazz
            while (current != null && current != Any::class.java) {
                count += hookShouldFilterMethods(current)
                current = current.superclass
            }
        }
        return count
    }

    private fun hookAppsFilter(classLoader: ClassLoader): Int {
        var count = 0
        for (name in HookUtil.appsFilterClassNameArray) {
            val clazz = classLoader.loadClassSafe(name) ?: continue
            count += hookShouldFilterMethods(clazz)
        }
        return count
    }

    private fun hookShouldFilterMethods(clazz: Class<*>): Int {
        var count = 0
        for (method in clazz.declaredMethods) {
            if (method.name != "shouldFilterApplication") continue
            if (method.returnType != Boolean::class.javaPrimitiveType) continue
            if (method.parameterCount < 3) continue
            val signature = clazz.name + "#" + method.name +
                    method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.simpleName }
            if (!hookedSignatures.add(signature)) continue
            try {
                XposedBridge.hookMethod(method, this)
                count++
                XLog.i("$TAG: hooked $signature")
            } catch (t: Throwable) {
                XLog.e(t, "$TAG: hooking $signature failed.")
            }
        }
        return count
    }

    // ------------------------------------------------------------------- hook bodies

    override fun afterHookedMethod(param: MethodHookParam) {
        // Every overload is evaluated: they delegate to each other, and a guard that only
        // let the outermost call through would lose the per-package decisions when the
        // SharedUserSetting overload fans out to the PackageStateInternal ones.
        try {
            handle(param)
        } catch (t: Throwable) {
            XLog.e(t, "$TAG: filtering ${param.method.name} failed.")
        }
    }

    private fun handle(param: MethodHookParam) {
        if (param.result == true) return

        val config = HookMain.configData
        val hidden = config.hiddenAppList
        val blind = config.blind
        if (hidden.isEmpty() && (blind == null || blind.isEmpty())) return

        val args = param.args
        val target = targetPackageName(args) ?: return
        val targetHidden = hidden.contains(target)
        // Blacklisted ("blind") callers must be filtered even when the target is not hidden,
        // so the caller has to be resolved for them as well.
        if (!targetHidden && (blind == null || blind.isEmpty())) return

        val callingUid = args.firstOrNull { it is Int } as? Int ?: return
        val userId = args.lastOrNull { it is Int } as? Int ?: return
        // uid < FIRST_APPLICATION_UID means system / root / shell.  The framework never
        // filters those, and system components (launcher, freeform window manager, recents,
        // system_server itself) must always be able to enumerate and start hidden apps.
        if (android.os.UserHandle.getAppId(callingUid) < android.os.Process.FIRST_APPLICATION_UID) {
            return
        }
        val calling = packageForUid(param.thisObject, callingUid) ?: return
        if (!targetHidden && blind?.contains(calling) != true) return

        val shouldIntercept = HookChecker.shouldIntercept(
            classLoader = classLoader,
            userId = userId,
            targetPackageName = target,
            callingPackageName = calling
        )
        if (shouldIntercept) {
            param.result = true
            XLog.d("$TAG: blocked $calling -> $target (user $userId)")
        }
    }

    // ------------------------------------------------------------------- arg parsing

    private fun targetPackageName(args: Array<Any?>): String? {
        packageNameOf(args.getOrNull(0))?.let { return it }
        // Android 15+ AppsFilter layout: (snapshot, callingUid, callingSetting, target, userId)
        for (index in args.indices.reversed()) {
            val candidate = args[index] ?: continue
            if (candidate is Number || candidate is Boolean || candidate is CharSequence) continue
            val simpleName = candidate.javaClass.simpleName
            if (!simpleName.contains("PackageState") && !simpleName.contains("PackageSetting")) {
                continue
            }
            packageNameOf(candidate)?.let { return it }
        }
        return null
    }

    /**
     * Resolves the package of a calling uid through the Computer snapshot.  Never calls
     * PackageManagerService (and never relies on the filter itself for the common path), so
     * it is safe to run while the package manager lock is held.
     */
    private fun packageForUid(engine: Any?, uid: Int): String? {
        if (engine == null || uid < 0) return null
        uidToPackage[uid]?.let { return it }

        val resolving = resolvingUids.get() ?: mutableSetOf<Int>().also { resolvingUids.set(it) }
        if (!resolving.add(uid)) return null
        try {
            val appId = UserHandle.getAppId(uid)

            // 1. ComputerEngine#getSettingBase(int) - no filtering, no locks.
            var name = methodOf(engine.javaClass, "getSettingBase", INT_CLASS)
                ?.let { packageNameOfSetting(it.invoke(engine, appId)) }

            // 2. mSettings.getSettingLPr(int) on releases without the helper above.
            if (name == null) {
                val settings = fieldOf(engine.javaClass, "mSettings")?.get(engine)
                if (settings != null) {
                    val method = methodOf(settings.javaClass, "getSettingLPr", INT_CLASS)
                        ?: methodOf(settings.javaClass, "getSettingBase", INT_CLASS)
                    if (method != null) {
                        name = packageNameOfSetting(method.invoke(settings, appId))
                    }
                }
            }

            // 3. Last resort; the re-entrancy guard stops the filter from recursing.
            if (name == null) {
                val method = methodOf(engine.javaClass, "getPackagesForUid", INT_CLASS)
                if (method != null) {
                    val packages = method.invoke(engine, uid) as? Array<*>
                    name = packages?.firstOrNull { it is String && it.isNotEmpty() } as? String
                }
            }

            if (name != null) uidToPackage[uid] = name
            return name
        } catch (t: Throwable) {
            return null
        } finally {
            resolving.remove(uid)
        }
    }

    private fun packageNameOfSetting(setting: Any?): String? {
        if (setting == null) return null
        packageNameOf(setting)?.let { return it }
        val states = methodOf(setting.javaClass, "getPackageStates")?.invoke(setting) as? Iterable<*>
            ?: return null
        for (state in states) {
            packageNameOf(state)?.let { return it }
        }
        return null
    }

    private fun packageNameOf(target: Any?): String? {
        if (target == null) return null
        val method = methodOf(target.javaClass, "getPackageName") ?: return null
        return try {
            method.invoke(target) as? String
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------- reflection cache

    private fun methodOf(clazz: Class<*>, name: String, vararg params: Class<*>): Method? {
        val key = "m:" + clazz.name + "#" + name + params.joinToString(prefix = "(", postfix = ")") { it.name }
        val cached = lookupCache[key]
        if (cached === NONE) return null
        if (cached is Method) return cached
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                val method = current.getDeclaredMethod(name, *params)
                method.isAccessible = true
                lookupCache[key] = method
                return method
            } catch (t: Throwable) {
                current = current.superclass
            }
        }
        lookupCache[key] = NONE
        return null
    }

    private fun fieldOf(clazz: Class<*>, name: String): Field? {
        val key = "f:" + clazz.name + "#" + name
        val cached = lookupCache[key]
        if (cached === NONE) return null
        if (cached is Field) return cached
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                val field = current.getDeclaredField(name)
                field.isAccessible = true
                lookupCache[key] = field
                return field
            } catch (t: Throwable) {
                current = current.superclass
            }
        }
        lookupCache[key] = NONE
        return null
    }
}
