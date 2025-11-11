package com.example.call2wa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
        if (lpparam.packageName != "com.android.phone" &&
            lpparam.packageName != "com.android.dialer") return

        XposedBridge.log("Call2WA: loaded in ${lpparam.packageName}")

        try {
            val tmClass = XposedHelpers.findClass("android.telephony.TelephonyManager", lpparam.classLoader)
            XposedHelpers.findAndHookConstructor(
                tmClass,
                android.content.Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val tm = param.thisObject as TelephonyManager
                        val app = XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("android.app.ActivityThread", null),
                            "currentApplication"
                        ) as android.app.Application
                        val ctx = app.applicationContext

                        // === Состояния вызова + переадресация ===
                        val listener = object : PhoneStateListener() {

                            override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                                // «Вызов завершен.» — переход OFFHOOK -> IDLE
                                if (lastCallState == TelephonyManager.CALL_STATE_OFFHOOK &&
                                    state == TelephonyManager.CALL_STATE_IDLE) {
                                    showToast(ctx, "Вызов завершен.")
                                    endCurrentCall(ctx) // на всякий случай
                                    lastOutgoing?.let { openWhatsAppChat(ctx, it) }
                                }
                                lastCallState = state
                            }

                            // «Вызов переадресован.» — если стек отдает индикатор
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
                        tm.listen(
                            listener,
                            PhoneStateListener.LISTEN_CALL_STATE or
                                    PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR
                        )

                        // === Последний исходящий номер ===
                        try {
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
                        } catch (e: Throwable) {
                            XposedBridge.log("Call2WA: NEW_OUTGOING_CALL receiver failed: ${e.message}")
                        }

                        // === Причины разъединения: «Номер занят.» / «Линия занята.» ===
                        try {
                            val telecomClass = XposedHelpers.findClass("android.telecom.TelecomManager", null)
                            val actionDisc = XposedHelpers.getStaticObjectField(telecomClass, "ACTION_CALL_DISCONNECTED") as String
                            val extraCause = XposedHelpers.getStaticObjectField(telecomClass, "EXTRA_DISCONNECT_CAUSE") as String

                            val discFilter = IntentFilter(actionDisc)
                            val discReceiver = object : BroadcastReceiver() {
                                override fun onReceive(context: Context, intent: Intent) {
                                    try {
                                        val causeObj = intent.extras?.get(extraCause)
                                        if (causeObj != null) {
                                            val code = XposedHelpers.callMethod(causeObj, "getCode") as Int
                                            val dcClass = XposedHelpers.findClass("android.telecom.DisconnectCause", null)
                                            val BUSY = XposedHelpers.getStaticIntField(dcClass, "BUSY")
                                            val CONGESTION = XposedHelpers.getStaticIntField(dcClass, "CONGESTION")

                                            when (code) {
                                                BUSY -> {
                                                    showToast(ctx, "Номер занят.")
                                                    endCurrentCall(ctx)
                                                    lastOutgoing?.let { openWhatsAppChat(ctx, it) }
                                                }
                                                CONGESTION -> {
                                                    showToast(ctx, "Линия занята.")
                                                    endCurrentCall(ctx)
                                                    lastOutgoing?.let { openWhatsAppChat(ctx, it) }
                                                }
                                            }
                                        }
                                    } catch (e: Throwable) {
                                        XposedBridge.log("Call2WA: disconnect cause read failed: ${e.message}")
                                    }
                                }
                            }
                            ctx.registerReceiver(discReceiver, discFilter)
                        } catch (e: Throwable) {
                            XposedBridge.log("Call2WA: disconnect receiver not attached: ${e.message}")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA failed: ${e.message}")
        }
    }

    // ===== Helpers

    private fun showToast(ctx: Context, text: String) {
        try { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() }
        catch (e: Throwable) { XposedBridge.log("Call2WA toast error: ${e.message}") }
    }

    /** Сбросить текущий звонок */
    private fun endCurrentCall(ctx: Context) {
        try {
            val tmgr = ctx.getSystemService(Context.TELECOM_SERVICE)
            if (tmgr != null) {
                try {
                    val ok = XposedHelpers.callMethod(tmgr, "endCall") as? Boolean
                    Log.d("Call2WA", "TelecomManager.endCall() -> $ok")
                    return
                } catch (_: Throwable) { /* фоллбек ниже */ }
            }
            try {
                val sm = XposedHelpers.findClass("android.os.ServiceManager", null)
                val binder = XposedHelpers.callStaticMethod(sm, "getService", "phone") as android.os.IBinder
                val iTelStub = XposedHelpers.findClass("com.android.internal.telephony.ITelephony\$Stub", null)
                val iTel = XposedHelpers.callStaticMethod(iTelStub, "asInterface", binder)
                XposedHelpers.callMethod(iTel, "endCall")
                Log.d("Call2WA", "ITelephony.endCall() invoked")
            } catch (e: Throwable) {
                XposedBridge.log("Call2WA: ITelephony.endCall failed: ${e.message}")
            }
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA endCurrentCall error: ${e.message}")
        }
    }

    /** Открыть чат WhatsApp (без VoIP) по последнему исходящему номеру */
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
