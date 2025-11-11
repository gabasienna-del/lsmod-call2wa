package com.example.call2wa

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.DisconnectCause
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookInit : IXposedHookLoadPackage {

    private object State {
        @Volatile var lastDialedDigits: String? = null
        @Volatile var lastPlacedAt: Long = 0L
        @Volatile var waTriggeredAt: Long = 0L
        @Volatile var waTriedForThisCall: Boolean = false
    }

    private val TELECOM_PKG = "com.android.server.telecom"
    private val TOAST_PKGS = setOf(
        TELECOM_PKG,
        "com.samsung.android.incallui",
        "com.samsung.android.dialer",
        "com.android.phone"
    )

    // Строго: Первая буква заглавная + точка в конце (без триммов/регистровых преобразований)
    private val BAD_TOASTS = setOf(
        "Вызов завершен.",
        "Вызов переадресован.",
        "Линия занята.",
        "Номер занят."
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            when (lpparam.packageName) {
                TELECOM_PKG -> hookTelecomOutgoing(lpparam)
            }
            if (lpparam.packageName in TOAST_PKGS) {
                hookToastStrict(lpparam)
            }
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA: handleLoadPackage error: ${e.message}")
        }
    }

    // ===== 1) Вытаскиваем исходящий номер из Telecom =====
    private fun hookTelecomOutgoing(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("Call2WA: loaded in $TELECOM_PKG")

        val callsManager = findFirstClass(
            listOf(
                "com.android.server.telecom.CallsManager",
                "com.android.server.telecom.CallsManager$Transaction"
            ),
            lpparam.classLoader
        ) ?: run {
            XposedBridge.log("Call2WA: CallsManager not found")
            return
        }

        val candidates = listOf("startOutgoingCall", "placeOutgoingCall", "startCall")
        for (m in candidates) {
            try {
                XposedHelpers.findAndHookMethod(
                    callsManager, m,
                    Uri::class.java,              // handle (tel:)
                    Any::class.java,              // PhoneAccountHandle
                    android.os.Bundle::class.java,// extras
                    String::class.java,           // initiating user / tag
                    Boolean::class.javaPrimitiveType!!,
                    Boolean::class.javaPrimitiveType!!,
                    Integer.TYPE,                 // videoState/callType
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val handle = param.args.firstOrNull { it is Uri } as? Uri
                            val digits = handle?.schemeSpecificPart?.replace("\\D+".toRegex(), "") ?: ""
                            if (digits.isNotEmpty()) {
                                State.lastDialedDigits = digits
                                State.lastPlacedAt = System.currentTimeMillis()
                                State.waTriedForThisCall = false
                                XposedBridge.log("Call2WA: outgoing to $digits via $m")
                            }
                        }
                    }
                )
                XposedBridge.log("Call2WA: hooked $m")
            } catch (_: Throwable) { /* try next */ }
        }

        // Не обязательно, просто лог причин
        try {
            val callClass = XposedHelpers.findClass("com.android.server.telecom.Call", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                callClass, "setDisconnectCause",
                DisconnectCause::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val dc = param.args[0] as? DisconnectCause ?: return
                        XposedBridge.log("Call2WA: disconnect code=${dc.code} label=${dc.label} desc=${dc.description}")
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    // ===== 2) Перехватываем Toast и из makeText, и из show() =====
    private fun hookToastStrict(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val toastClass = XposedHelpers.findClass("android.widget.Toast", lpparam.classLoader)

            // a) static Toast makeText(Context, CharSequence, int)
            XposedHelpers.findAndHookMethod(
                toastClass, "makeText",
                Context::class.java, CharSequence::class.java, Integer.TYPE,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val msg = (param.args[1] as? CharSequence)?.toString() ?: return
                        if (msg in BAD_TOASTS) {
                            maybeTriggerWhatsApp()
                            XposedBridge.log("Call2WA: matched makeText '$msg' in ${lpparam.packageName}")
                        }
                    }
                }
            )

            // b) Toast.show() — читаем текст из mNextView -> TextView(android.R.id.message)
            XposedHelpers.findAndHookMethod(
                toastClass, "show",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val toastObj = param.thisObject
                            val nextView = XposedHelpers.getObjectField(toastObj, "mNextView") as? ViewGroup ?: return
                            val tv: TextView? = nextView.findViewById(android.R.id.message)
                            val msg = tv?.text?.toString() ?: return
                            if (msg in BAD_TOASTS) {
                                maybeTriggerWhatsApp()
                                XposedBridge.log("Call2WA: matched show() '$msg' in ${lpparam.packageName}")
                            }
                        } catch (_: Throwable) { }
                    }
                }
            )

            XposedBridge.log("Call2WA: hooked Toast.makeText & show in ${lpparam.packageName}")
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA: Toast hook failed in ${lpparam.packageName}: ${e.message}")
        }
    }

    // ===== 3) Условия и мгновенный запуск WhatsApp аудиозвонка (без задержек) =====
    private fun maybeTriggerWhatsApp() {
        try {
            val now = System.currentTimeMillis()
            val recentDial = (now - State.lastPlacedAt) <= 30_000 // исходящий был за последние 30с
            val cooldown = (now - State.waTriggeredAt) >= 3_000   // анти-дубль 3с
            val number = State.lastDialedDigits

            if (!recentDial || number.isNullOrBlank()) return
            if (State.waTriedForThisCall && !cooldown) return

            State.waTriedForThisCall = true
            State.waTriggeredAt = now

            // СРАЗУ, без задержек:
            openWhatsAppAudioCall(number)
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA: maybeTriggerWhatsApp error: ${e.message}")
        }
    }

    private fun openWhatsAppAudioCall(numberDigits: String) {
        try {
            val normalized = numberDigits.replace("[^0-9]".toRegex(), "")
            if (normalized.isEmpty()) return
            val jid = "$normalized@s.whatsapp.net"

            val ctxClass = XposedHelpers.findClass("android.app.ActivityThread", null)
            val app = XposedHelpers.callStaticMethod(ctxClass, "currentApplication") as Application

            // 1) Пытаемся сразу VoIP-активности (несколько кандидатов)
            val voipCandidates = listOf(
                "com.whatsapp.voipcalling.VoipActivityV2",
                "com.whatsapp.voipcalling.VoipActivity",
                "com.whatsapp.voip.VoipActivity",
                "com.whatsapp.calling.CallActivity"
            )
            var ok = false
            for (cls in voipCandidates) {
                if (ok) break
                try {
                    val i = Intent(Intent.ACTION_MAIN).apply {
                        component = ComponentName("com.whatsapp", cls)
                        putExtra("jid", jid)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    app.startActivity(i)
                    ok = true
                    XposedBridge.log("Call2WA: WA VoIP via $cls -> $jid")
                } catch (_: Throwable) {}
            }

            // 2) Если не получилось — откроем чат
            if (!ok) {
                try {
                    val conv = Intent(Intent.ACTION_MAIN).apply {
                        component = ComponentName("com.whatsapp", "com.whatsapp.Conversation")
                        putExtra("jid", jid)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    app.startActivity(conv)
                    XposedBridge.log("Call2WA: opened Conversation -> $jid")
                } catch (_: Throwable) {}
            }

            // 3) Fallback через content-type (если контакт известен системе)
            if (!ok) {
                try {
                    val callIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("content://com.android.contacts/data")
                        type = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
                        putExtra("jid", jid)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        setPackage("com.whatsapp")
                    }
                    app.startActivity(callIntent)
                    XposedBridge.log("Call2WA: WA VoIP via content-type -> $jid")
                } catch (e: Throwable) {
                    XposedBridge.log("Call2WA: WA call failed: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA openWhatsAppAudioCall error: ${e.message}")
        }
    }

    // ===== helpers =====
    private fun findFirstClass(names: List<String>, cl: ClassLoader): Class<*>? {
        for (n in names) try { return XposedHelpers.findClass(n, cl) } catch (_: Throwable) {}
        return null
    }
}
