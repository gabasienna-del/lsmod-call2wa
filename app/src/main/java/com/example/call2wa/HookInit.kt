package com.example.call2wa

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookInit : IXposedHookLoadPackage {

    @Volatile
    private var lastOutgoingNumber: String? = null

    @Volatile
    private var dialStartTime: Long = 0

    @Volatile
    private var wentOffhook: Boolean = false

    // Таймаут ожидания перехода в OFFHOOK — 1000 мс (1 секунда)
    private val OFFHOOK_WAIT_MS: Long = 1000

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Только системные приложения дозвона
        if (lpparam.packageName !in listOf("com.android.phone", "com.android.dialer")) return
        XposedBridge.log("Call2WA: loaded in ${lpparam.packageName}")

        // 1) Перехват startActivity у ContextWrapper чтобы поймать ACTION_CALL / tel:
        try {
            XposedHelpers.findAndHookMethod(
                "android.content.ContextWrapper",
                lpparam.classLoader,
                "startActivity",
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            val intent = param.args[0] as? Intent ?: return
                            val data = intent.data
                            val action = intent.action

                            if (action == Intent.ACTION_CALL || (data != null && data.scheme == "tel")) {
                                val raw = data?.schemeSpecificPart ?: return
                                val number = raw.replace("\\D+".toRegex(), "")
                                if (number.isNotBlank()) {
                                    lastOutgoingNumber = number
                                    dialStartTime = System.currentTimeMillis()
                                    wentOffhook = false
                                    XposedBridge.log("Call2WA: outgoing attempt -> $lastOutgoingNumber at $dialStartTime")
                                }
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("Call2WA startActivity hook error: ${t.message}")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA startActivity hook failed: ${e.message}")
        }

        // 2) Хук TelephonyManager constructor -> регистрируем PhoneStateListener
        try {
            val tmClass = XposedHelpers.findClass("android.telephony.TelephonyManager", lpparam.classLoader)
            XposedHelpers.findAndHookConstructor(
                tmClass,
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            val tm = param.thisObject as TelephonyManager
                            val listener = object : PhoneStateListener() {
                                override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                                    val now = System.currentTimeMillis()
                                    when (state) {
                                        TelephonyManager.CALL_STATE_OFFHOOK -> {
                                            wentOffhook = true
                                            XposedBridge.log("Call2WA: CALL_STATE_OFFHOOK")
                                        }
                                        TelephonyManager.CALL_STATE_IDLE -> {
                                            // Если была попытка исходящего и в течение OFFHOOK_WAIT_MS не перешло в OFFHOOK -> считаем что не состоялся
                                            if (lastOutgoingNumber != null &&
                                                dialStartTime != 0L &&
                                                now - dialStartTime < OFFHOOK_WAIT_MS &&
                                                !wentOffhook
                                            ) {
                                                XposedBridge.log("Call2WA: CALL_STATE_IDLE after attempt -> call didn't happen for $lastOutgoingNumber")
                                                endCall()
                                                openWhatsApp(lastOutgoingNumber!!)
                                                clearLastDialInfo()
                                            } else {
                                                // Очистим, если прошло больше таймаута или вызов состоялся
                                                if (now - dialStartTime >= OFFHOOK_WAIT_MS || wentOffhook) {
                                                    clearLastDialInfo()
                                                }
                                            }
                                        }
                                        // TelephonyManager.CALL_STATE_RINGING не нужен для логики исходящего
                                    }
                                }
                            }
                            @Suppress("DEPRECATION")
                            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                        } catch (t: Throwable) {
                            XposedBridge.log("Call2WA PhoneStateListener error: ${t.message}")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA Telephony hook failed: ${e.message}")
        }

        // 3) Перехват Toast.show() — реагируем строго на данные строки (с заглавной и точкой)
        try {
            XposedHelpers.findAndHookMethod(
                Toast::class.java,
                "show",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            val toast = param.thisObject as Toast
                            val textField = XposedHelpers.getObjectField(toast, "mText") ?: return
                            val text = textField.toString().trim()

                            if (text in listOf(
                                    "Вызов переадресован.",
                                    "Вызов завершен.",
                                    "Линия занята.",
                                    "Номер занят."
                                )
                            ) {
                                XposedBridge.log("Call2WA: detected toast -> \"$text\"")
                                endCall()
                                lastOutgoingNumber?.let { num ->
                                    openWhatsApp(num)
                                    XposedBridge.log("Call2WA: opened WhatsApp for $num due to toast")
                                }
                                clearLastDialInfo()
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("Call2WA toast hook error: ${t.message}")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA Toast hook failed: ${e.message}")
        }
    }

    /**
     * Попытка программно завершить звонок.
     * Используем java-рефлексию, чтобы вызвать приватный getITelephony(), затем endCall() на stub-е.
     */
    private fun endCall() {
    try {
        val ctx = getAppContext()
        val telecomManager = ctx.getSystemService(Context.TELECOM_SERVICE)
        if (telecomManager != null) {
            val tmClass = Class.forName("android.telecom.TelecomManager")
            val endCallMethod = tmClass.getDeclaredMethod("endCall")
            endCallMethod.isAccessible = true
            endCallMethod.invoke(telecomManager)
            XposedBridge.log("Call2WA: endCall invoked via TelecomManager")
        } else {
            XposedBridge.log("Call2WA: TelecomManager is null")
        }
    } catch (e: Throwable) {
        XposedBridge.log("Call2WA endCall error: ${e.message}")
    }
    }

    // Открыть чат WhatsApp для номера (используется wa.me)
    private fun openWhatsApp(number: String) {
        try {
            val uri = Uri.parse("https://wa.me/$number")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.whatsapp")
            }
            getAppContext().startActivity(intent)
            Log.d("Call2WA", "Opening WhatsApp chat for $number")
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA openWhatsApp error: ${e.message}")
        }
    }

    private fun clearLastDialInfo() {
        lastOutgoingNumber = null
        dialStartTime = 0
        wentOffhook = false
    }

    private fun getAppContext(): Context {
        val ctxClass = XposedHelpers.findClass("android.app.ActivityThread", null)
        val app = XposedHelpers.callStaticMethod(ctxClass, "currentApplication") as Application
        return app.applicationContext
    }
}
