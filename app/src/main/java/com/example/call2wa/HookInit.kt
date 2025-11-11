package com.example.call2wa

import android.app.Application
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookInit : IXposedHookLoadPackage {

    // храним последний исходящий номер
    @Volatile private var lastNumber: String? = null
    private val ui = Handler(Looper.getMainLooper())

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            // Процессы телефонии/звонилки (под разные прошивки)
            "com.android.phone",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer" -> {
                hookDialer(lpparam)
                hookToastTrigger(lpparam) // ловим тосты «Вызов переадресован/завершен»
            }
            // В WhatsApp автокликаем кнопку аудиозвонка, если пришли с ?call=1
            "com.whatsapp" -> hookWhatsAppAutoclick(lpparam)
        }
    }

    // ====== Dialer: сохраняем номер при OFFHOOK ======
    private fun hookDialer(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("Call2WA: loaded in ${lpparam.packageName}")
        try {
            val tmClass = XposedHelpers.findClass("android.telephony.TelephonyManager", lpparam.classLoader)
            XposedHelpers.findAndHookConstructor(
                tmClass, Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val tm = param.thisObject as TelephonyManager
                        val listener = object : PhoneStateListener() {
                            override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                                if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                                    // пробуем взять из API; если пусто — из CallLog
                                    val raw = (incomingNumber ?: "").ifBlank {
                                        try { CallLog.Calls.getLastOutgoingCall((param.args[0] as Context)) } catch (_: Throwable) { "" }
                                    }
                                    lastNumber = sanitize(raw)
                                    if (!lastNumber.isNullOrEmpty())
                                        XposedBridge.log("Call2WA: lastNumber=$lastNumber")
                                }
                            }
                        }
                        @Suppress("DEPRECATION")
                        tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA dialer hook error: ${e.message}")
        }
    }

    // ====== Dialer: триггер по Toast "Вызов переадресован/завершен" ======
    private fun hookToastTrigger(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                Toast::class.java, "makeText",
                Context::class.java, CharSequence::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val msg = (param.args[1] as? CharSequence)?.toString()?.lowercase() ?: return
                        val hit = msg.contains("переадресован") || msg.contains("заверш") ||
                                  msg.contains("forwarded") || msg.contains("ended")
                        if (!hit) return

                        val num = lastNumber
                        if (num.isNullOrEmpty()) return

                        // Небольшая задержка — даём звонилке освободиться
                        ui.postDelayed({ openWhatsApp(num) }, 300)
                        XposedBridge.log("Call2WA: toast '$msg' -> open WhatsApp for $num")
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA toast hook error: ${e.message}")
        }
    }

    private fun sanitize(raw: String?): String {
        val d = (raw ?: "").replace("\\D+".toRegex(), "")
        // Пример нормализации для KZ: 8XXXXXXXXXX -> 7XXXXXXXXXX, 10 цифр -> +7
        return when {
            d.length == 11 && d.startsWith("8") -> "7" + d.substring(1)
            d.length == 10 -> "7$d"
            else -> d
        }
    }

    private fun openWhatsApp(number: String) {
        try {
            val uri = Uri.parse("https://wa.me/$number?call=1") // ?call=1 — для автоклика в WA
            val i = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.whatsapp")
            }
            val at = XposedHelpers.findClass("android.app.ActivityThread", null)
            val app = XposedHelpers.callStaticMethod(at, "currentApplication") as Application
            app.startActivity(i)
            Log.d("Call2WA", "Opening WhatsApp for $number")
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA openWhatsApp error: ${e.message}")
        }
    }

    // ====== WhatsApp: автоклик по кнопке voice call ======
    private fun hookWhatsAppAutoclick(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val at = XposedHelpers.findClass("android.app.ActivityThread", null)
            val app = XposedHelpers.callStaticMethod(at, "currentApplication") as Application
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    val data = activity.intent?.dataString ?: return
                    if (!data.contains("wa.me") || !data.contains("call=1")) return
                    // Дадим UI нарисоваться
                    ui.postDelayed({
                        val btn = findVoiceCallButton(activity.window?.decorView)
                        if (btn != null && btn.isClickable) {
                            try { btn.performClick() } catch (e: Throwable) {
                                XposedBridge.log("Call2WA performClick error: ${e.message}")
                            }
                        } else {
                            Log.d("Call2WA", "Voice button not found")
                        }
                    }, 600)
                }
                override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityPaused(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            })
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA WA hook error: ${e.message}")
        }
    }

    private fun findVoiceCallButton(root: View?): View? {
        if (root == null) return null
        val keys = listOf("Аудиозвонок", "Голосовой звонок", "Голосовой вызов",
            "Voice call", "Audio call")
        fun match(v: View): Boolean {
            val cd = v.contentDescription?.toString()?.trim() ?: ""
            if (keys.any { cd.equals(it, true) }) return true
            val id = v.id
            if (id != View.NO_ID) {
                val name = try { v.resources.getResourceEntryName(id) } catch (_: Throwable) { "" }
                if (name.contains("voice", true) || name.contains("audio_call", true)) return true
            }
            return false
        }
        fun dfs(v: View): View? {
            if (match(v)) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) {
                val r = dfs(v.getChildAt(i)); if (r != null) return r
            }
            return null
        }
        return dfs(root)
    }
}
