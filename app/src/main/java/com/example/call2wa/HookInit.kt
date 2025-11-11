package com.example.call2wa

import android.content.*
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

    companion object {
        @Volatile private var lastOutgoing: String? = null
        @Volatile private var lastCallState: Int = TelephonyManager.CALL_STATE_IDLE
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // только системная телефония
        if (lpparam.packageName != "com.android.phone" &&
            lpparam.packageName != "com.android.dialer" &&
            lpparam.packageName != "com.android.server.telecom") return

        XposedBridge.log("Call2WA: loaded in ${lpparam.packageName}")

        // =============================
        // 1. хук для обычных состояний
        // =============================
        if (lpparam.packageName == "com.android.phone" ||
            lpparam.packageName == "com.android.dialer") {
            try {
                val tmClass = XposedHelpers.findClass("android.telephony.TelephonyManager", lpparam.classLoader)
                XposedHelpers.findAndHookConstructor(tmClass, Context::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val tm = param.thisObject as TelephonyManager
                        val ctx = (XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("android.app.ActivityThread", null),
                            "currentApplication"
                        ) as android.app.Application).applicationContext

                        val listener = object : PhoneStateListener() {
                            override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                                if (lastCallState == TelephonyManager.CALL_STATE_OFFHOOK &&
                                    state == TelephonyManager.CALL_STATE_IDLE) {
                                    showToast(ctx, "Вызов завершен.")
                                    endCurrentCall(ctx)
                                    lastOutgoing?.let { openWhatsAppChat(ctx, it) }
                                }
                                lastCallState = state
                            }

                            @Suppress("OVERRIDE_DEPRECATION")
                            override fun onCallForwardingIndicatorChanged(cfi: Boolean) {
                                if (cfi) {
                                    showToast(ctx, "Вызов переадресован.")
                                    endCurrentCall(ctx)
                                    lastOutgoing?.let { openWhatsAppChat(ctx, it) }
                                }
                            }
                        }

                        @Suppress("DEPRECATION")
                        tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE or
                                PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR)

                        val outFilter = IntentFilter(Intent.ACTION_NEW_OUTGOING_CALL)
                        val outReceiver = object : BroadcastReceiver() {
                            override fun onReceive(context: Context, intent: Intent) {
                                val num = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
                                if (!num.isNullOrBlank()) {
                                    lastOutgoing = num.replace("\\D+".toRegex(), "")
                                    Log.d("Call2WA", "Last outgoing set to $lastOutgoing")
                                }
                            }
                        }
                        ctx.registerReceiver(outReceiver, outFilter)
                    }
                })
            } catch (e: Throwable) {
                XposedBridge.log("Call2WA phone hook failed: ${e.message}")
            }
        }

        // =============================
        // 2. Хук через com.android.server.telecom.Call
        //    ловим реальные причины разъединения
        // =============================
        if (lpparam.packageName == "com.android.server.telecom") {
            try {
                val callClass = XposedHelpers.findClass("com.android.server.telecom.Call", lpparam.classLoader)
                XposedHelpers.findAndHookMethod(callClass, "setDisconnectCause",
                    XposedHelpers.findClass("android.telecom.DisconnectCause", lpparam.classLoader),
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val cause = param.args[0]
                            val code = XposedHelpers.callMethod(cause, "getCode") as Int
                            val dcClass = XposedHelpers.findClass("android.telecom.DisconnectCause", lpparam.classLoader)
                            val busy = XposedHelpers.getStaticIntField(dcClass, "BUSY")
                            val congestion = XposedHelpers.getStaticIntField(dcClass, "CONGESTION")

                            val app = XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass("android.app.ActivityThread", null),
                                "currentApplication"
                            ) as android.app.Application
                            val ctx = app.applicationContext

                            when (code) {
                                busy -> {
                                    showToast(ctx, "Номер занят.")
                                    endCurrentCall(ctx)
                                    lastOutgoing?.let { openWhatsAppChat(ctx, it) }
                                }
                                congestion -> {
                                    showToast(ctx, "Линия занята.")
                                    endCurrentCall(ctx)
                                    lastOutgoing?.let { openWhatsAppChat(ctx, it) }
                                }
                            }
                        }
                    })
                XposedBridge.log("Call2WA: Telecom Call hook active ✅")
            } catch (e: Throwable) {
                XposedBridge.log("Call2WA: Telecom Call hook failed: ${e.message}")
            }
        }
    }

    // ===== Helpers =====

    private fun showToast(ctx: Context, text: String) {
        try { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() }
        catch (e: Throwable) { XposedBridge.log("Call2WA toast error: ${e.message}") }
    }

    private fun endCurrentCall(ctx: Context) {
        try {
            val sm = XposedHelpers.findClass("android.os.ServiceManager", null)
            val binder = XposedHelpers.callStaticMethod(sm, "getService", "phone") as android.os.IBinder
            val iTelStub = XposedHelpers.findClass("com.android.internal.telephony.ITelephony\$Stub", null)
            val iTel = XposedHelpers.callStaticMethod(iTelStub, "asInterface", binder)
            XposedHelpers.callMethod(iTel, "endCall")
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA endCurrentCall error: ${e.message}")
        }
    }

    private fun openWhatsAppChat(ctx: Context, number: String) {
        val clean = number.replace("\\D+".toRegex(), "")
        try {
            val uri = Uri.parse("https://wa.me/$clean")
            val i = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.whatsapp")
            }
            ctx.startActivity(i)
            Log.d("Call2WA", "Opening WhatsApp chat for $clean")
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA openWhatsAppChat error: ${e.message}")
        }
    }
}
