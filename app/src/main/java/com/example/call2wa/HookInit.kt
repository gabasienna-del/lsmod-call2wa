package com.example.call2wa

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.CallLog
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

    // Строго: первая буква заглавная + точка
    private val BAD_TOASTS = setOf(
        "Вызов завершен.",
        "Вызов переадресован.",
        "Линия занята.",
        "Номер занят."
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            if (lpparam.packageName == TELECOM_PKG) {
                hookTelecomOutgoingStrong(lpparam)
            }
            if (lpparam.packageName in TOAST_PKGS) {
                hookToastStrict(lpparam)
            }
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA: handleLoadPackage error: ${e.message}")
        }
    }

    // ===== 1) Надёжный захват исходящего номера =====
    private fun hookTelecomOutgoingStrong(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("Call2WA: loaded in $TELECOM_PKG")

        // a) CallsManager: цепляемся ко ВСЕМ вариантам методов
        val cmClass = try { XposedHelpers.findClass("com.android.server.telecom.CallsManager", lpparam.classLoader) } catch (_: Throwable) { null }
        if (cmClass != null) {
            hookAllMethodsGrabUri(cmClass, "startOutgoingCall")
            hookAllMethodsGrabUri(cmClass, "placeOutgoingCall")
            hookAllMethodsGrabUri(cmClass, "startCall")
        } else {
            XposedBridge.log("Call2WA: CallsManager not found")
        }

        // b) Call: ловим установку handle напрямую
        val callClass = try { XposedHelpers.findClass("com.android.server.telecom.Call", lpparam.classLoader) } catch (_: Throwable) { null }
        if (callClass != null) {
            // setHandle(Uri)
            try {
                XposedHelpers.findAndHookMethod(callClass, "setHandle", Uri::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val uri = param.args[0] as? Uri ?: return
                        saveDigitsFromUri("setHandle", uri)
                    }
                })
                XposedBridge.log("Call2WA: hooked Call#setHandle")
            } catch (_: Throwable) {}

            // конструктор Call(..., Uri handle, ...)
            try {
                XposedBridge.hookAllConstructors(callClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.args?.forEach { a ->
                            if (a is Uri) saveDigitsFromUri("Call::<init>", a)
                        }
                    }
                })
                XposedBridge.log("Call2WA: hooked Call::<init>")
            } catch (_: Throwable) {}
        }
    }

    private fun hookAllMethodsGrabUri(clazz: Class<*>, method: String) {
        try {
            XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // ищем Uri среди любых аргументов
                    param.args?.forEach { a ->
                        when (a) {
                            is Uri -> saveDigitsFromUri(method, a)
                            else -> {
                                // иногда номер спрятан в объекте с getHandle()
                                try {
                                    val h = XposedHelpers.callMethod(a, "getHandle") as? Uri
                                    if (h != null) saveDigitsFromUri("$method#getHandle", h)
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                }
            })
            XposedBridge.log("Call2WA: hookAllMethods $method OK")
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA: hookAllMethods $method failed: ${e.message}")
        }
    }

    private fun saveDigitsFromUri(tag: String, uri: Uri?) {
        val sp = uri?.schemeSpecificPart ?: return
        val digits = sp.replace("\\D+".toRegex(), "")
        if (digits.isNotEmpty()) {
            State.lastDialedDigits = digits
            State.lastPlacedAt = System.currentTimeMillis()
            State.waTriedForThisCall = false
            XposedBridge.log("Call2WA: captured number=$digits via $tag (uri=$uri)")
        }
    }

    // ===== 2) Перехватываем Toast (makeText и show) =====
    private fun hookToastStrict(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val toastClass = XposedHelpers.findClass("android.widget.Toast", lpparam.classLoader)

            // makeText
            XposedHelpers.findAndHookMethod(
                toastClass, "makeText",
                Context::class.java, CharSequence::class.java, Integer.TYPE,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val msg = (param.args[1] as? CharSequence)?.toString() ?: return
                        if (msg in BAD_TOASTS) {
                            XposedBridge.log("Call2WA: matched makeText '$msg' in ${lpparam.packageName}")
                            maybeTriggerWhatsApp(lpparam)
                        }
                    }
                }
            )

            // show
            XposedHelpers.findAndHookMethod(
                toastClass, "show",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val vg = XposedHelpers.getObjectField(param.thisObject, "mNextView") as? ViewGroup ?: return
                            val tv: TextView? = vg.findViewById(android.R.id.message)
                            val msg = tv?.text?.toString() ?: return
                            if (msg in BAD_TOASTS) {
                                XposedBridge.log("Call2WA: matched show() '$msg' in ${lpparam.packageName}")
                                maybeTriggerWhatsApp(lpparam)
                            }
                        } catch (_: Throwable) {}
                    }
                }
            )

            XposedBridge.log("Call2WA: Toast hooks set for ${lpparam.packageName}")
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA: Toast hook failed in ${lpparam.packageName}: ${e.message}")
        }
    }

    // ===== 3) Запуск WhatsApp сразу; если номера нет — берём из CallLog =====
    private fun maybeTriggerWhatsApp(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            var number = State.lastDialedDigits
            val now = System.currentTimeMillis()
            val recentDial = (now - State.lastPlacedAt) <= 60_000 // минуту держим «актуальным»
            val cooldown = (now - State.waTriggeredAt) >= 3_000

            if ((number.isNullOrBlank() || !recentDial) && lpparam.packageName == "com.android.phone") {
                // попробуем вытащить из CallLog последний исходящий за 2 минуты
                number = tryFetchLastOutgoingFromCallLog()
                if (!number.isNullOrBlank()) {
                    State.lastDialedDigits = number
                    State.lastPlacedAt = now
                    XposedBridge.log("Call2WA: number from CallLog = $number")
                }
            }

            if (number.isNullOrBlank()) {
                XposedBridge.log("Call2WA: skip — number unknown")
                return
            }
            if (State.waTriedForThisCall && !cooldown) {
                XposedBridge.log("Call2WA: skip — already tried recently")
                return
            }

            State.waTriedForThisCall = true
            State.waTriggeredAt = now
            openWhatsAppAudioCall(number!!)
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA: maybeTriggerWhatsApp error: ${e.message}")
        }
    }

    private fun tryFetchLastOutgoingFromCallLog(): String? {
        return try {
            val ctxClass = XposedHelpers.findClass("android.app.ActivityThread", null)
            val app = XposedHelpers.callStaticMethod(ctxClass, "currentApplication") as Application
            val cr = app.contentResolver
            val uri = CallLog.Calls.CONTENT_URI
            val proj = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE)
            val sel = "${CallLog.Calls.TYPE}=? AND ${CallLog.Calls.DATE}>=?"
            val twoMinAgo = System.currentTimeMillis() - 2 * 60_000
            val args = arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), twoMinAgo.toString())
            val sort = "${CallLog.Calls.DATE} DESC LIMIT 1"
            val c: Cursor? = cr.query(uri, proj, sel, args, sort)
            c?.use {
                if (it.moveToFirst()) {
                    val num = it.getString(0) ?: return null
                    val digits = num.replace("\\D+".toRegex(), "")
                    return if (digits.isNotEmpty()) digits else null
                }
            }
            null
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA: CallLog query failed: ${e.message}")
            null
        }
    }

    private fun openWhatsAppAudioCall(numberDigits: String) {
        try {
            val normalized = numberDigits.replace("[^0-9]".toRegex(), "")
            if (normalized.isEmpty()) return
            val jid = "$normalized@s.whatsapp.net"

            val ctxClass = XposedHelpers.findClass("android.app.ActivityThread", null)
            val app = XposedHelpers.callStaticMethod(ctxClass, "currentApplication") as Application

            // пробуем сразу VoIP активность (несколько вариантов)
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

            if (!ok) {
                // откроем чат
                try {
                    val conv = Intent(Intent.ACTION_MAIN).apply {
                        component = ComponentName("com.whatsapp", "com.whatsapp.Conversation")
                        putExtra("jid", jid)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    app.startActivity(conv)
                    XposedBridge.log("Call2WA: opened Conversation -> $jid")
                } catch (_: Throwable) {}

                // и ещё один запасной способ
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
}
