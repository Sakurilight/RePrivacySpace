package com.reprivacyspace.app.hook.impl

import android.app.ActivityThread
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import com.reprivacyspace.app.BuildConfig
import com.reprivacyspace.app.hook.HookMain
import com.reprivacyspace.app.hook.Hooker
import com.reprivacyspace.app.util.XLog
import com.reprivacyspace.app.util.loadClassSafe
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * "Quick launch" from PrivacySpace 1.3.11: while the screen is on, pressing Volume-Up,
 * Volume-Down and Power in quick succession opens the module's UI.
 *
 * It is implemented in system_server on top of PhoneWindowManager#interceptKeyBeforeQueueing,
 * which is still the key entry point on Android 17.  The Power key is only consumed when the
 * full sequence matched, so normal volume/power behaviour is untouched.
 */
object PhoneWindowManagerHooker : Hooker {

    private const val TAG = "QuickLaunch"
    private const val SEQUENCE_TIMEOUT_MS = 1500L
    private const val LAUNCHER_ACTIVITY = "com.reprivacyspace.app.ui.main.MainActivity"

    private var step = 0
    private var lastKeyAt = 0L
    private var lastEventTime = -1L
    private var lastEventKeyCode = -1

    override fun start(classLoader: ClassLoader) {
        val policyClass = classLoader.loadClassSafe("com.android.server.policy.PhoneWindowManager")
        if (policyClass == null) {
            XLog.e("$TAG: PhoneWindowManager not found.")
            return
        }
        try {
            XposedHelpers.findAndHookMethod(
                policyClass,
                "interceptKeyBeforeQueueing",
                KeyEvent::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val event = param.args.getOrNull(0) as? KeyEvent ?: return
                        if (onKeyEvent(event)) {
                            // Swallow the Power key so the screen does not turn off.
                            param.result = 0
                        }
                    }
                })
            XLog.i("$TAG: PhoneWindowManager#interceptKeyBeforeQueueing hooked.")
        } catch (t: Throwable) {
            XLog.e(t, "$TAG: hook failed.")
        }
    }

    @Synchronized
    private fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        if (HookMain.configData.quickLaunch != true) return false
        if (!isInteractive()) return false

        // The framework can deliver the same event through more than one policy entry.
        if (event.eventTime == lastEventTime && event.keyCode == lastEventKeyCode) return false
        lastEventTime = event.eventTime
        lastEventKeyCode = event.keyCode

        val now = SystemClock.uptimeMillis()
        if (now - lastKeyAt > SEQUENCE_TIMEOUT_MS) {
            step = 0
        }
        lastKeyAt = now

        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                step = 1
                false
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                step = if (step == 1) 2 else 0
                false
            }
            KeyEvent.KEYCODE_POWER -> {
                val matched = step == 2
                step = 0
                matched && launch()
            }
            else -> {
                step = 0
                false
            }
        }
    }

    private fun isInteractive(): Boolean {
        return try {
            val context: Context =
                ActivityThread.currentActivityThread()?.systemContext ?: return true
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            power?.isInteractive ?: true
        } catch (t: Throwable) {
            true
        }
    }

    private fun launch(): Boolean {
        return try {
            val context: Context =
                ActivityThread.currentActivityThread()?.systemContext ?: return false
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClassName(BuildConfig.APPLICATION_ID, LAUNCHER_ACTIVITY)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
            }
            context.startActivity(intent)
            XLog.i("$TAG: launch PrivacySpace")
            true
        } catch (t: Throwable) {
            XLog.e(t, "$TAG: launch PrivacySpace failed.")
            false
        }
    }
}
