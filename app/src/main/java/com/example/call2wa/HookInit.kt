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
        if (lpparam.packageName !in listOf("com.android.phone", "com.android.dialer")) return
        XposedBridge.log("Call2WA: loaded in ${lpparam.packageName}")

        try {
            // Отслеживание исходящего номера
            XposedHelpers.findAndHookMethod(
                "android.content.ContextWrapper",
                lpparam.classLoader,
                "startActivity",
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
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
                                    XposedBridge.log("Call2WA: outgoing attempt -> $lastOutgoingNumber")
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

        try {
            // Отслеживание состояний звонка
            val tmClass = XposedHelpers.findClass("android.telephony.TelephonyManager", lpparam.classLoader)
            XposedHelpers.findAndHookConstructor(
                tmClass,
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
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
                                            if (lastOutgoingNumber != null &&
                                                dialStartTime != 0L &&
                                                now - dialStartTime < OFFHOOK_WAIT_MS &&
                                                !wentOffhook
                                            ) {
                                                XposedBridge.log("Call2WA: call failed (timeout 1s) -> opening WhatsApp for $lastOutgoingNumber")
                                                endCall()
                                                openWhatsApp(lastOutgoingNumber!!)
                                                clearLastDialInfo()
                                            } else {
                                                if (now - dialStartTime >= OFFHOOK_WAIT_MS || wentOffhook) {
                                                    clearLastDialInfo()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            @Suppress("DEPRECATION")
                            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                        } catch (t: Throwable) {
                            XposedBridge.log("Call2WA listener error: ${t.message}")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA Telephony hook failed: ${e.message}")
        }

        try {
            // Перехват Toast'ов (точные строки)
            XposedHelpers.findAndHookMethod(
                Toast::class.java,
                "show",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
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
                                XposedBridge.log("Call2WA: detected toast \"$text\"")
                                endCall()
                                lastOutgoingNumber?.let {
                                    openWhatsApp(it)
                                    XposedBridge.log("Call2WA: opened WhatsApp for $it (toast)")
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

    private fun endCall() {
        try {
            val tm = getAppContext().getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val getITelephony = XposedHelpers.getDeclaredMethod(tm.javaClass, "getITelephony")
            getITelephony.isAccessible = true
            val telephonyStub = getITelephony.invoke(tm)
            XposedHelpers.callMethod(telephonyStub, "endCall")
            XposedBridge.log("Call2WA: endCall invoked")
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA endCall error: ${e.message}")
        }
    }

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
