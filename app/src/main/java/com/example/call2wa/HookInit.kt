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
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookInit : IXposedHookLoadPackage {

    @Volatile private var lastNumber: String? = null
    @Volatile private var lastDialTs: Long = 0L
    @Volatile private var callWasOutgoing = false
    private val ui = Handler(Looper.getMainLooper())

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            // ловим состояние вызова в звонилке
            "com.android.phone",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer" -> hookDialerByStates(lpparam)
            // автоклик по кнопке аудиозвонка в WhatsApp
            "com.whatsapp" -> hookWhatsAppAutoclick(lpparam)
        }
    }

    // ===== Основной триггер: OFFHOOK → IDLE =====
    private fun hookDialerByStates(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val tmClass = XposedHelpers.findClass("android.telephony.TelephonyManager", lpparam.classLoader)
            XposedHelpers.findAndHookConstructor(
                tmClass, Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = param.args[0] as Context
                        val tm = param.thisObject as TelephonyManager
                        var prevState = TelephonyManager.CALL_STATE_IDLE

                        val listener = object : PhoneStateListener() {
                            override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                                try {
                                    // Запоминаем номер при исходящем вызове
                                    if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                                        val (num, ts) = getLastOutgoing(ctx)
                                        if (num.isNotEmpty()) {
                                            lastNumber = sanitize(num)
                                            lastDialTs = ts
                                            callWasOutgoing = true
                                            XposedBridge.log("Call2WA: OFFHOOK num=$lastNumber ts=$lastDialTs")
                                        }
                                    }
                                    // Конец звонка
                                    if (state == TelephonyManager.CALL_STATE_IDLE &&
                                        prevState == TelephonyManager.CALL_STATE_OFFHOOK &&
                                        callWasOutgoing
                                    ) {
                                        val age = System.currentTimeMillis() - lastDialTs
                                        val num = lastNumber
                                        if (num != null && age in 0..30000) {
                                            XposedBridge.log("Call2WA: IDLE -> open WhatsApp $num")
                                            ui.postDelayed({ openWhatsApp(num) }, 400)
                                        }
                                        callWasOutgoing = false
                                    }
                                    prevState = state
                                } catch (e: Throwable) {
                                    XposedBridge.log("Call2WA state hook err: ${e.message}")
                                }
                            }
                        }
                        @Suppress("DEPRECATION")
                        tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA dialer states hook error: ${e.message}")
        }
    }

    // читаем последний исходящий номер из журнала
    private fun getLastOutgoing(ctx: Context): Pair<String, Long> {
        return try {
            val uri = CallLog.Calls.CONTENT_URI
            val proj = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE)
            ctx.contentResolver.query(
                uri, proj,
                "${CallLog.Calls.TYPE}=?",
                arrayOf(CallLog.Calls.OUTGOING_TYPE.toString()),
                "${CallLog.Calls.DATE} DESC LIMIT 1"
            )?.use { c ->
                if (c.moveToFirst()) Pair(c.getString(0) ?: "", c.getLong(1))
                else Pair("", 0L)
            } ?: Pair("", 0L)
        } catch (_: Throwable) {
            Pair("", 0L)
        }
    }

    // открываем чат в WhatsApp
    private fun openWhatsApp(number: String) {
        try {
            val uri = Uri.parse("https://wa.me/$number?call=1") // ?call=1 — метка для автоклика
            val i = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.whatsapp")
            }
            val at = XposedHelpers.findClass("android.app.ActivityThread", null)
            val app = XposedHelpers.callStaticMethod(at, "currentApplication") as Application
            app.startActivity(i)
            Log.d("Call2WA", "Open WA for $number")
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA openWA error: ${e.message}")
        }
    }

    // ===== Автоклик кнопки “Аудиозвонок” =====
    private fun hookWhatsAppAutoclick(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val at = XposedHelpers.findClass("android.app.ActivityThread", null)
            val app = XposedHelpers.callStaticMethod(at, "currentApplication") as Application
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    val data = activity.intent?.dataString ?: return
                    if (!data.contains("wa.me") || !data.contains("call=1")) return

                    var tries = 0
                    fun findBtn(root: View?): View? {
                        if (root == null) return null
                        val keys = listOf(
                            "Аудиозвонок", "Голосовой звонок", "Voice call", "Audio call",
                            "Дауыстық қоңырау", "Дыбыстық қоңырау"
                        )
                        fun match(v: View): Boolean {
                            val cd = v.contentDescription?.toString()?.trim() ?: ""
                            if (keys.any { cd.equals(it, true) }) return true
                            val id = v.id
                            if (id != View.NO_ID) {
                                val name = try { v.resources.getResourceEntryName(id) } catch (_: Throwable) { "" }
                                if (name.contains("voice", true) || name.contains("audio_call", true))
                                    return true
                            }
                            return false
                        }
                        if (match(root)) return root
                        if (root is ViewGroup) for (i in 0 until root.childCount) {
                            val r = findBtn(root.getChildAt(i)); if (r != null) return r
                        }
                        return null
                    }
                    fun tick() {
                        val btn = findBtn(activity.window?.decorView)
                        if (btn != null && btn.isClickable) {
                            try {
                                btn.performClick()
                                Log.d("Call2WA", "Voice Call clicked!")
                            } catch (e: Throwable) {
                                XposedBridge.log("Call2WA click error: ${e.message}")
                            }
                        } else if (tries++ < 10) {
                            ui.postDelayed({ tick() }, 300)
                        }
                    }
                    ui.postDelayed({ tick() }, 700)
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

    // нормализация номера под KZ
    private fun sanitize(raw: String?): String {
        val d = (raw ?: "").replace("\\D+".toRegex(), "")
        return when {
            d.length == 11 && d.startsWith("8") -> "7" + d.substring(1)
            d.length == 10 -> "7$d"
            else -> d
        }
    }
}
