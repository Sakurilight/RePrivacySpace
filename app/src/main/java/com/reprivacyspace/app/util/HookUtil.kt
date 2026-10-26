package com.reprivacyspace.app.util

/**
 * Class lookup helpers for the pieces of the system_server that this module needs.
 *
 * AOSP keeps moving these classes around (Android 15 moved package visibility into
 * AppsFilterBase/AppsFilterImpl/AppsFilterLocked, Android 16/17 route every query
 * through ComputerEngine.shouldFilterApplication).  Nothing here may throw: every
 * lookup returns null and the caller falls back to the next strategy.
 */
object HookUtil {

    /** PackageManagerService implementations: AOSP and the usual OEM renames. */
    val pmsClassNameArray = arrayOf(
        "com.android.server.pm.PackageManagerService",
        "com.android.server.pm.OplusPackageManagerService",
        "com.android.server.pm.OppoPackageManagerService",
        "com.android.server.pm.MiuiPackageManagerService"
    )

    /** Classes that own the "can this uid see that package" decision, newest first. */
    val computerEngineClassNameArray = arrayOf(
        "com.android.server.pm.ComputerEngine",
        "com.android.server.pm.PackageManagerService\$ComputerEngine"
    )

    /** Implementations of the package service binder (AIDL IPackageManager). */
    val ipmImplClassNameArray = arrayOf(
        "com.android.server.pm.PackageManagerService\$IPackageManagerImpl",
        "com.android.server.pm.IPackageManagerBase",
        "android.content.pm.IPackageManager\$Stub",
        "com.android.server.pm.PackageManagerService"
    )

    /** Classes that collect the package visibility allow list, newest first. */
    val appsFilterClassNameArray = arrayOf(
        "com.android.server.pm.AppsFilterBase",
        "com.android.server.pm.AppsFilterImpl",
        "com.android.server.pm.AppsFilterLocked",
        "com.android.server.pm.AppsFilter",
        "com.android.server.pm.AppsFilterSnapshotImpl"
    )

    fun loadPms(classLoader: ClassLoader): Class<*>? {
        for (name in pmsClassNameArray) {
            classLoader.loadClassSafe(name)?.let { return it }
        }
        return null
    }

    fun loadFirst(classLoader: ClassLoader, names: Array<String>): Class<*>? {
        for (name in names) {
            classLoader.loadClassSafe(name)?.let { return it }
        }
        return null
    }

    /** Reads a `public static final int` from a generated AIDL Stub class, or null. */
    fun staticIntField(clazz: Class<*>, name: String): Int? {
        return try {
            val f = clazz.getDeclaredField(name)
            f.isAccessible = true
            f.getInt(null)
        } catch (t: Throwable) {
            null
        }
    }
}
