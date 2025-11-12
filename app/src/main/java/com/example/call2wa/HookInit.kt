package com.example.call2wa

import android.content.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.telecom.Call as TelecomCall
import android.telecom.DisconnectCause
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.widget.Toast
import android.util.Log
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

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val allowed = setOf(
            "com.samsung.android.incallui",
            "com.samsung.android.incall.contentprovider",
            "com.samsung.android.smartcallprovider",
            "com.android.server.telecom",
            "com.samsung.android.dialer",
            "com.android.phone"
        )
        if (!allowed.contains(lpparam.packageName)) return

        XposedBridge.log("Call2WA: loaded in ${lpparam.packageName}")

        try {
            if (lpparam.packageName in listOf(
                    "com.android.phone",
                    "com.samsung.android.dialer",
                    "com.samsung.android.incallui",
                    "com.samsung.android.incall.contentprovider",
                    "com.samsung.android.smartcallprovider"
                )) {
                hookPhoneSide(lpparam)
            }

            if (lpparam.packageName in listOf(
                    "com.android.server.telecom",
                    "com.samsung.android.server.telecom"
                )) {
                hookTelecomSide(lpparam)
            }
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA handleLoadPackage error: ${e.message}")
        }
    }

    // ---------------- PHONE SIDE ----------------
    private fun hookPhoneSide(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val tmClass = XposedHelpers.findClass("android.telephony.TelephonyManager", lpparam.classLoader)
            XposedHelpers.findAndHookConstructor(tmClass, Context::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val tm = param.thisObject as TelephonyManager
                    val ctx = appContext()

                    val listener = object : PhoneStateListener() {
                        override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                            if (lastCallState == TelephonyManager.CALL_STATE_OFFHOOK &&
                                state == TelephonyManager.CALL_STATE_IDLE) {

                                // Основное событие OFFHOOK -> IDLE
                                finishCallAndOpenWA(ctx, "Вызов завершен.")

                                mainHandler.postDelayed({
                                    showToast(ctx, "Вызов переадресован.")
                                }, 350)
                                mainHandler.postDelayed({
                                    showToast(ctx, "Линия занята.")
                                }, 700)
                                mainHandler.postDelayed({
                                    showToast(ctx, "Номер занят.")
                                }, 1050)
                            }
                            lastCallState = state
                        }

                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun onCallForwardingIndicatorChanged(cfi: Boolean) {
                            if (cfi) finishCallAndOpenWA(ctx, "Вызов переадресован.")
                        }
                    }

                    @Suppress("DEPRECATION")
                    tm.listen(
                        listener,
                        PhoneStateListener.LISTEN_CALL_STATE or
                                PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR
                    )

                    // перехватываем последний исходящий номер
                    try {
                        val outFilter = IntentFilter(Intent.ACTION_NEW_OUTGOING_CALL)
                        val outReceiver = object : BroadcastReceiver() {
                            override fun onReceive(context: Context, intent: Intent) {
                                intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)?.let {
                                    lastOutgoing = it.replace("\\D+".toRegex(), "")
                                    XposedBridge.log("Call2WA: Last outgoing set to $lastOutgoing")
                                }
                            }
                        }
                        ctx.registerReceiver(outReceiver, outFilter)
                    } catch (e: Throwable) {
                        XposedBridge.log("Call2WA: NEW_OUTGOING_CALL receiver failed: ${e.message}")
                    }
                }
            })
            XposedBridge.log("Call2WA: phone side hook installed for ${lpparam.packageName}")
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA phone hook failed: ${e.message}")
        }
    }

    // ---------------- TELECOM SIDE ----------------
    private fun hookTelecomSide(lpparam: XC_LoadPackage.LoadPackageParam) {
        val ctx by lazy { appContext() }

        val callClassNames = listOf(
            "com.android.server.telecom.Call",
            "com.samsung.android.server.telecom.Call",
            "com.android.server.telecom.Call$Call"
        )

        var hooked = false

        for (clazz in callClassNames) {
            try {
                val callClass = XposedHelpers.findClass(clazz, lpparam.classLoader)

                runCatching {
                    XposedHelpers.findAndHookMethod(
                        callClass, "setDisconnectCause",
                        XposedHelpers.findClass("android.telecom.DisconnectCause", lpparam.classLoader),
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                handleDisconnectCause(param.args.getOrNull(0), ctx, lpparam)
                            }
                        }
                    )
                    hooked = true
                    XposedBridge.log("Call2WA: hooked $clazz#setDisconnectCause")
                }

                runCatching {
                    XposedHelpers.findAndHookMethod(callClass, "disconnect", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val cause = runCatching {
                                XposedHelpers.callMethod(param.thisObject, "getDisconnectCause")
                            }.getOrNull()
                            handleDisconnectCause(cause, ctx, lpparam)
                        }
                    })
                    hooked = true
                    XposedBridge.log("Call2WA: hooked $clazz#disconnect")
                }
            } catch (_: Throwable) {}
        }

        if (!hooked) XposedBridge.log("Call2WA: Telecom Call hook NOT attached in ${lpparam.packageName}")
    }

    private fun handleDisconnectCause(causeObj: Any?, ctx: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        if (causeObj == null) return
        try {
            val dcClass = XposedHelpers.findClass("android.telecom.DisconnectCause", lpparam.classLoader)
            val code = XposedHelpers.callMethod(causeObj, "getCode") as? Int ?: return
            val BUSY = XposedHelpers.getStaticIntField(dcClass, "BUSY")
            val CONGESTION = XposedHelpers.getStaticIntField(dcClass, "CONGESTION")

            when (code) {
                BUSY -> finishCallAndOpenWA(ctx, "Номер занят.")
                CONGESTION -> finishCallAndOpenWA(ctx, "Линия занята.")
            }
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA handleDisconnectCause error: ${e.message}")
        }
    }

    // ---------------- HELPERS ----------------
    private fun appContext(): Context {
        val at = XposedHelpers.findClass("android.app.ActivityThread", null)
        val app = XposedHelpers.callStaticMethod(at, "currentApplication") as? android.app.Application
        return app?.applicationContext ?: throw IllegalStateException("No application")
    }

    private fun showToast(ctx: Context, text: String) {
        try { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
    }

    private fun finishCallAndOpenWA(ctx: Context, toastText: String) {
        showToast(ctx, toastText)
        XposedBridge.log("Call2WA: toast -> \"$toastText\"")

        // Попытка 1: TelecomManager.endCall()
        try {
            val tmgr = ctx.getSystemService(Context.TELECOM_SERVICE)
            if (tmgr != null) {
                runCatching {
                    XposedHelpers.callMethod(tmgr, "endCall")
                    XposedBridge.log("Call2WA: endCall via TelecomManager")
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA: TelecomManager error: ${e.message}")
        }

        // Попытка 2: ITelephony.endCall()
        try {
            val sm = XposedHelpers.findClass("android.os.ServiceManager", null)
            val binder = XposedHelpers.callStaticMethod(sm, "getService", "phone") as? android.os.IBinder
            if (binder != null) {
                val stub = XposedHelpers.findClass("com.android.internal.telephony.ITelephony\$Stub", null)
                val iTel = XposedHelpers.callStaticMethod(stub, "asInterface", binder)
                XposedHelpers.callMethod(iTel, "endCall")
                XposedBridge.log("Call2WA: endCall via ITelephony")
            }
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA: ITelephony endCall failed: ${e.message}")
        }

        // Открываем WhatsApp через 300 мс
        mainHandler.postDelayed({
            val number = lastOutgoing?.replace("\\D+".toRegex(), "")
            if (number.isNullOrBlank()) {
                XposedBridge.log("Call2WA: lastOutgoing is null — skip WA open")
                return@postDelayed
            }
            try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage("com.whatsapp")
                }
                ctx.startActivity(i)
                XposedBridge.log("Call2WA: WhatsApp opened for $number")
            } catch (e: Throwable) {
                XposedBridge.log("Call2WA: openWA error: ${e.message}")
            }
        }, 300)
    }
}
