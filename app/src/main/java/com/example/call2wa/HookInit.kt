package com.example.call2wa

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
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
        @Volatile var lastDialedDigits: String? = null     // последний набранный (tel:)
        @Volatile var lastPlacedAt: Long = 0L              // когда его поймали
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

    // Строго: первая буква заглавная, точка в конце
    private val BAD_TOASTS = setOf(
        "Вызов завершен.",
        "Вызов переадресован.",
        "Линия занята.",
        "Номер занят."
    )

    // Эти тосты → сначала сброс GSM, затем WA
    private val END_CALL_TOASTS = setOf(
        "Вызов переадресован.",
        "Линия занята.",
        "Номер занят."
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // 1) В системном telecom — ловим исходящий из внутренних вызовов
            if (lpparam.packageName == TELECOM_PKG) {
                hookTelecomOutgoingStrong(lpparam)
            }

            // 2) Во всех указанных пакетах:
            if (lpparam.packageName in TOAST_PKGS) {
                // 2a) Ловим Тосты (makeText + show)
                hookToastStrict(lpparam)
                // 2b) Самое важное: перехватываем запуск ACTION_CALL, чтобы вытащить номер сразу из Intent
                hookExecStartActivityForCalls(lpparam)
            }
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA: handleLoadPackage error: ${e.message}")
        }
    }

    // ===== Перехват запуска исходящего звонка (ACTION_CALL) — надёжно достаём номер из tel: =====
    private fun hookExecStartActivityForCalls(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val instrClass = XposedHelpers.findClass("android.app.Instrumentation", lpparam.classLoader)
            // На разных версиях много перегрузок. Перехватываем ВСЕ execStartActivity и ищем Intent среди аргументов.
            XposedBridge.hookAllMethods(instrClass, "execStartActivity", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val intent = param.args?.firstOrNull { it is Intent } as? Intent ?: return
                    val action = intent.action ?: return
                    if (action == Intent.ACTION_CALL || action == "android.intent.action.CALL_PRIVILEGED") {
                        val data = intent.data
                        if (data != null && data.scheme == "tel") {
                            val digits = data.schemeSpecificPart?.replace("\\D+".toRegex(), "") ?: ""
                            if (digits.isNotEmpty()) {
                                State.lastDialedDigits = digits
                                State.lastPlacedAt = System.currentTimeMillis()
                                State.waTriedForThisCall = false
                                XposedBridge.log("Call2WA: captured from ACTION_CALL -> $digits (pkg=${lpparam.packageName})")
                            }
                        }
                    }
                }
            })
            XposedBridge.log("Call2WA: hooked Instrumentation.execStartActivity in ${lpparam.packageName}")
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA: hook execStartActivity failed in ${lpparam.packageName}: ${e.message}")
        }
    }

    // ===== Исходящий номер из Telecom (доп. источник) =====
    private fun hookTelecomOutgoingStrong(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("Call2WA: loaded in $TELECOM_PKG")

        val cmClass = try { XposedHelpers.findClass("com.android.server.telecom.CallsManager", lpparam.classLoader) } catch (_: Throwable) { null }
        if (cmClass != null) {
            hookAllMethodsGrabUri(cmClass, "startOutgoingCall")
            hookAllMethodsGrabUri(cmClass, "placeOutgoingCall")
            hookAllMethodsGrabUri(cmClass, "startCall")
        }

        val callClass = try { XposedHelpers.findClass("com.android.server.telecom.Call", lpparam.classLoader) } catch (_: Throwable) { null }
        if (callClass != null) {
            try {
                XposedHelpers.findAndHookMethod(callClass, "setHandle", Uri::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val uri = param.args[0] as? Uri ?: return
                        saveDigitsFromUri("setHandle", uri)
                    }
                })
            } catch (_: Throwable) {}
            try {
                XposedBridge.hookAllConstructors(callClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.args?.forEach { a -> if (a is Uri) saveDigitsFromUri("Call::<init>", a) }
                    }
                })
            } catch (_: Throwable) {}
        }
    }

    private fun hookAllMethodsGrabUri(clazz: Class<*>, method: String) {
        try {
            XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args?.forEach { a ->
                        when (a) {
                            is Uri -> saveDigitsFromUri(method, a)
                            else -> {
                                try {
                                    val h = XposedHelpers.callMethod(a, "getHandle") as? Uri
                                    if (h != null) saveDigitsFromUri("$method#getHandle", h)
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                }
            })
        } catch (_: Throwable) {}
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

    // ===== Тосты (makeText + show) =====
    private fun hookToastStrict(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val toastClass = XposedHelpers.findClass("android.widget.Toast", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                toastClass, "makeText",
                Context::class.java, CharSequence::class.java, Integer.TYPE,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val msg = (param.args[1] as? CharSequence)?.toString() ?: return
                        if (msg in BAD_TOASTS) {
                            val needEnd = msg in END_CALL_TOASTS
                            XposedBridge.log("Call2WA: matched makeText '$msg' in ${lpparam.packageName}, endFirst=$needEnd")
                            maybeTriggerWhatsApp(lpparam, needEnd)
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                toastClass, "show",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val vg = XposedHelpers.getObjectField(param.thisObject, "mNextView") as? ViewGroup ?: return
                            val tv: TextView? = vg.findViewById(android.R.id.message)
                            val msg = tv?.text?.toString() ?: return
                            if (msg in BAD_TOASTS) {
                                val needEnd = msg in END_CALL_TOASTS
                                XposedBridge.log("Call2WA: matched show() '$msg' in ${lpparam.packageName}, endFirst=$needEnd")
                                maybeTriggerWhatsApp(lpparam, needEnd)
                            }
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    // ===== Триггер: при нужных тостах — сброс GSM и сразу WA =====
    private fun maybeTriggerWhatsApp(lpparam: XC_LoadPackage.LoadPackageParam, endFirst: Boolean) {
        try {
            val now = System.currentTimeMillis()
            val cooldown = (now - State.waTriggeredAt) >= 3_000
            if (State.waTriedForThisCall && !cooldown) return

            State.waTriedForThisCall = true
            State.waTriggeredAt = now

            if (endFirst) endGsmCall()

            // 1) Берём номер из State (пойман при ACTION_CALL)
            var candidate = State.lastDialedDigits

            // 2) Если вдруг пусто — попробуем CallLog:
            if (candidate.isNullOrBlank()) {
                candidate = tryFetchLastOutgoingFromCallLog(3_000) ?: tryFetchLastOutgoingFromCallLog(20_000)
            }

            if (candidate.isNullOrBlank()) {
                XposedBridge.log("Call2WA: skip — number unknown")
                return
            }

            XposedBridge.log("Call2WA: triggering WA for $candidate")
            openWhatsAppAudioCall(candidate)

            // Сбросим, чтобы не прилипал старый номер
            State.lastDialedDigits = null
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA: maybeTriggerWhatsApp error: ${e.message}")
        }
    }

    // Сброс текущего GSM-вызова
    private fun endGsmCall() {
        // 1) TelecomManager.endCall()
        try {
            val ctxClass = XposedHelpers.findClass("android.app.ActivityThread", null)
            val app = XposedHelpers.callStaticMethod(ctxClass, "currentApplication") as Application
            val tm = app.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
            if (tm != null) {
                val ok = tm.endCall()
                XposedBridge.log("Call2WA: TelecomManager.endCall() -> $ok")
                if (ok) return
            }
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA: endCall via TelecomManager failed: ${e.message}")
        }

        // 2) ITelephony.endCall()
        try {
            val smClz = Class.forName("android.os.ServiceManager")
            val getSvc = smClz.getMethod("getService", String::class.java)
            val binder = getSvc.invoke(null, "phone") as IBinder
            val stubClz = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            val asIf = stubClz.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val end = asIf.javaClass.getMethod("endCall")
            end.invoke(asIf)
            XposedBridge.log("Call2WA: ITelephony.endCall() invoked")
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA: endCall via ITelephony failed: ${e.message}")
        }
    }

    private fun tryFetchLastOutgoingFromCallLog(windowMillis: Long): String? {
        return try {
            val ctxClass = XposedHelpers.findClass("android.app.ActivityThread", null)
            val app = XposedHelpers.callStaticMethod(ctxClass, "currentApplication") as Application
            val cr = app.contentResolver
            val uri = CallLog.Calls.CONTENT_URI
            val proj = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE)
            val sel = "${CallLog.Calls.TYPE}=? AND ${CallLog.Calls.DATE}>=?"
            val since = System.currentTimeMillis() - windowMillis
            val args = arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), since.toString())
            val sort = "${CallLog.Calls.DATE} DESC LIMIT 1"
            cr.query(uri, proj, sel, args, sort)?.use { c ->
                if (c.moveToFirst()) {
                    val num = c.getString(0) ?: return null
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

    // ===== Запуск WhatsApp звонка/чата (без задержек) =====
    private fun openWhatsAppAudioCall(numberDigits: String) {
        try {
            val normalized = numberDigits.replace("[^0-9]".toRegex(), "")
            if (normalized.isEmpty()) return

            val ctxClass = XposedHelpers.findClass("android.app.ActivityThread", null)
            val app = XposedHelpers.callStaticMethod(ctxClass, "currentApplication") as Application

            // 1) Deeplink мгновенного звонка
            try {
                val waCall = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$normalized?call")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage("com.whatsapp")
                }
                app.startActivity(waCall)
                XposedBridge.log("Call2WA: WA deeplink call -> $normalized")
                return
            } catch (_: Throwable) { }

            // 2) Чат к конкретному номеру (устраняет «липкий чат»)
            var opened = false
            try {
                val smsto = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$normalized")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage("com.whatsapp")
                }
                app.startActivity(smsto)
                XposedBridge.log("Call2WA: WA smsto chat -> $normalized")
                opened = true
            } catch (_: Throwable) {}

            // 3) Fallback: явная Conversation по jid
            if (!opened) {
                val jid = "$normalized@s.whatsapp.net"
                try {
                    val conv = Intent(Intent.ACTION_MAIN).apply {
                        component = ComponentName("com.whatsapp", "com.whatsapp.Conversation")
                        putExtra("jid", jid)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    app.startActivity(conv)
                    XposedBridge.log("Call2WA: opened Conversation -> $jid")
                } catch (e: Throwable) {
                    XposedBridge.log("Call2WA: WA open chat failed: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA openWhatsAppAudioCall error: ${e.message}")
        }
    }
}
