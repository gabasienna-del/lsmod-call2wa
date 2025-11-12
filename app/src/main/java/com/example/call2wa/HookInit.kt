package com.example.call2wa

import android.content.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
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

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // список пакетов, в которых разрешаем работу модуля
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
            // В телефонной стороне ловим состояния и исходящие номера
            if (lpparam.packageName == "com.android.phone" ||
                lpparam.packageName == "com.samsung.android.dialer" ||
                lpparam.packageName == "com.samsung.android.incallui" ||
                lpparam.packageName == "com.samsung.android.incall.contentprovider" ||
                lpparam.packageName == "com.samsung.android.smartcallprovider") {
                hookPhoneSide(lpparam)
            }

            // В стороне серверной телефонии (telecom) — ловим DisconnectCause через Call
            if (lpparam.packageName == "com.android.server.telecom" ||
                lpparam.packageName == "com.samsung.android.server.telecom") {
                hookTelecomSide(lpparam)
            }
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA handleLoadPackage error: ${e.message}")
        }
    }

    // ---------------- phone side ----------------
    private fun hookPhoneSide(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val tmClass = XposedHelpers.findClass("android.telephony.TelephonyManager", lpparam.classLoader)
            XposedHelpers.findAndHookConstructor(tmClass, Context::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val tm = param.thisObject as TelephonyManager
                    val ctx = appContext()

                    val listener = object : PhoneStateListener() {
                        override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                            // Сохранение входящего (если нужно) — здесь не основной сценарий
                            if (!incomingNumber.isNullOrBlank()) {
                                // не перезаписываем если уже есть lastOutgoing (мы ориентируемся на исходящие)
                            }

                            // OFFHOOK -> IDLE трактуем как «Вызов завершен.»
                            if (lastCallState == TelephonyManager.CALL_STATE_OFFHOOK &&
                                state == TelephonyManager.CALL_STATE_IDLE) {
                                finishCallAndOpenWA(ctx, "Вызов завершен.")
                            }
                            lastCallState = state
                        }

                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun onCallForwardingIndicatorChanged(cfi: Boolean) {
                            if (cfi) {
                                val ctx2 = appContext()
                                finishCallAndOpenWA(ctx2, "Вызов переадресован.")
                            }
                        }
                    }

                    @Suppress("DEPRECATION")
                    tm.listen(
                        listener,
                        PhoneStateListener.LISTEN_CALL_STATE or
                                PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR
                    )

                    // Ловим последний исходящий номер
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

    // ---------------- telecom side ----------------
    private fun hookTelecomSide(lpparam: XC_LoadPackage.LoadPackageParam) {
        val ctx by lazy { appContext() }

        // Попробуем найти возможные имена класса Call (AOSP и Samsung)
        val callClassNames = listOf(
            "com.android.server.telecom.Call",
            "com.samsung.android.server.telecom.Call",
            "com.android.server.telecom.Call$Call", // на всякий случай
        )

        var hookedAny = false

        for (clazz in callClassNames) {
            try {
                val callClass = XposedHelpers.findClass(clazz, lpparam.classLoader)

                // Вариант A: setDisconnectCause(DisconnectCause)
                runCatching {
                    XposedHelpers.findAndHookMethod(
                        callClass, "setDisconnectCause",
                        XposedHelpers.findClass("android.telecom.DisconnectCause", lpparam.classLoader),
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    handleDisconnectCause(param.args.getOrNull(0), ctx, lpparam)
                                } catch (t: Throwable) {
                                    XposedBridge.log("Call2WA: setDisconnectCause hook error: ${t.message}")
                                }
                            }
                        }
                    )
                    hookedAny = true
                    XposedBridge.log("Call2WA: hooked $clazz#setDisconnectCause")
                }.onFailure {
                    // ignore
                }

                // Вариант B: disconnect() — после него пробуем взять getDisconnectCause()
                runCatching {
                    XposedHelpers.findAndHookMethod(callClass, "disconnect", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val cause = runCatching { XposedHelpers.callMethod(param.thisObject, "getDisconnectCause") }.getOrNull()
                                handleDisconnectCause(cause, ctx, lpparam)
                            } catch (_: Throwable) { /* ignore */ }
                        }
                    })
                    hookedAny = true
                    XposedBridge.log("Call2WA: hooked $clazz#disconnect")
                }.onFailure { /* ignore */ }

            } catch (e: Throwable) {
                // пробуем следующий класс
            }
        }

        if (!hookedAny) {
            XposedBridge.log("Call2WA: Telecom Call hook NOT attached (Call class not found in ${lpparam.packageName})")
        } else {
            XposedBridge.log("Call2WA: Telecom side hooks installed for ${lpparam.packageName}")
        }
    }

    private fun handleDisconnectCause(causeObj: Any?, ctx: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        if (causeObj == null) return
        try {
            val dcClass = XposedHelpers.findClass("android.telecom.DisconnectCause", lpparam.classLoader)
            val code = XposedHelpers.callMethod(causeObj, "getCode") as? Int ?: return
            val BUSY = XposedHelpers.getStaticIntField(dcClass, "BUSY")
            val CONGESTION = XposedHelpers.getStaticIntField(dcClass, "CONGESTION")

            when (code) {
                BUSY -> {
                    XposedBridge.log("Call2WA: DisconnectCause=BUSY")
                    finishCallAndOpenWA(ctx, "Номер занят.")
                }
                CONGESTION -> {
                    XposedBridge.log("Call2WA: DisconnectCause=CONGESTION")
                    finishCallAndOpenWA(ctx, "Линия занята.")
                }
                else -> {
                    // другие причины игнорируем тут
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA handleDisconnectCause error: ${e.message}")
        }
    }

    // ---------------- helpers ----------------
    private fun appContext(): Context {
        val at = XposedHelpers.findClass("android.app.ActivityThread", null)
        val app = XposedHelpers.callStaticMethod(at, "currentApplication") as? android.app.Application
        return app?.applicationContext ?: throw IllegalStateException("No application")
    }

    /**
     * Попытаться завершить текущий вызов (TelecomManager.endCall() и/или ITelephony.endCall()), показать Toast,
     * и через небольшую паузу открыть чат WhatsApp по lastOutgoing.
     */
    private fun finishCallAndOpenWA(ctx: Context, toastText: String) {
        try { Toast.makeText(ctx, toastText, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}

        // Попытка 1: TelecomManager.endCall()
        try {
            val tmgr = ctx.getSystemService(Context.TELECOM_SERVICE)
            if (tmgr != null) {
                try {
                    XposedHelpers.callMethod(tmgr, "endCall")
                    XposedBridge.log("Call2WA: TelecomManager.endCall() invoked")
                } catch (e: Throwable) {
                    XposedBridge.log("Call2WA: TelecomManager.endCall() failed: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA: Telecom endCall attempt error: ${e.message}")
        }

        // Попытка 2: ITelephony.endCall() через ServiceManager
        try {
            val smClass = XposedHelpers.findClass("android.os.ServiceManager", null)
            val binder = XposedHelpers.callStaticMethod(smClass, "getService", "phone") as? android.os.IBinder
            if (binder != null) {
                try {
                    val iTelStub = XposedHelpers.findClass("com.android.internal.telephony.ITelephony\$Stub", null)
                    val iTel = XposedHelpers.callStaticMethod(iTelStub, "asInterface", binder)
                    XposedHelpers.callMethod(iTel, "endCall")
                    XposedBridge.log("Call2WA: ITelephony.endCall() invoked")
                } catch (e: Throwable) {
                    XposedBridge.log("Call2WA: ITelephony endCall failed: ${e.message}")
                }
            } else {
                XposedBridge.log("Call2WA: ServiceManager.getService(\"phone\") returned null")
            }
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA: ITelephony attempt error: ${e.message}")
        }

        // Открываем WA чат с небольшой задержкой чтобы интерфейс звонка успел освободиться
        mainHandler.postDelayed({
            val number = lastOutgoing?.replace("\\D+".toRegex(), "")
            if (number.isNullOrBlank()) {
                XposedBridge.log("Call2WA: lastOutgoing is null/blank, won't open WA")
                return@postDelayed
            }
            try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage("com.whatsapp")
                }
                ctx.startActivity(i)
                XposedBridge.log("Call2WA: Opening WhatsApp chat for $number")
            } catch (e: Throwable) {
                XposedBridge.log("Call2WA openWhatsAppChat error: ${e.message}")
            }
        }, 200)
    }
}
