package com.example.call2wa

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.CallLog
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookInit : IXposedHookLoadPackage {

    private object State {
        @Volatile var lastDialedDigits: String? = null
        @Volatile var waTriggeredAt: Long = 0L
        @Volatile var waTriedRecently: Boolean = false
    }

    private val TELECOM_PKG = "com.android.server.telecom"
    private val WATCH_PKGS = setOf(
        TELECOM_PKG,
        "com.android.phone",
        "com.samsung.android.incallui",
        "com.samsung.android.dialer",
        "com.android.dialer"
    )

    private val BAD_TOASTS = setOf(
        "Вызов завершен.",
        "Вызов переадресован.",
        "Линия занята.",
        "Номер занят."
    )

    private val END_CALL_TOASTS = setOf(
        "Вызов переадресован.",
        "Линия занята.",
        "Номер занят."
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            if (lpparam.packageName in WATCH_PKGS) {
                hookToastStrict(lpparam)
                hookExecStartActivityForCalls(lpparam)
            }
        } catch (_: Throwable) { }
    }

    private fun hookExecStartActivityForCalls(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val instr = XposedHelpers.findClass("android.app.Instrumentation", lpparam.classLoader)
            XposedHelpers.hookAllMethods(instr, "execStartActivity", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val intent = param.args?.firstOrNull { it is Intent } as? Intent ?: return
                    val action = intent.action ?: return
                    if (action == Intent.ACTION_CALL || action == "android.intent.action.CALL_PRIVILEGED") {
                        val data = intent.data
                        if (data != null && data.scheme == "tel") {
                            val digits = data.schemeSpecificPart?.replace("\\D+".toRegex(), "") ?: ""
                            if (digits.isNotEmpty()) {
                                State.lastDialedDigits = digits
                                State.waTriedRecently = false
                            }
                        }
                    }
                }
            })
        } catch (_: Throwable) { }
    }

    private fun hookToastStrict(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val toast = XposedHelpers.findClass("android.widget.Toast", lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                toast, "makeText",
                Context::class.java, CharSequence::class.java, Integer.TYPE,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val msg = (param.args[1] as? CharSequence)?.toString() ?: return
                        if (msg in BAD_TOASTS) onBadToast(msg)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                toast, "show",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val vg = XposedHelpers.getObjectField(param.thisObject, "mNextView") as? ViewGroup ?: return
                            val tv: TextView? = vg.findViewById(android.R.id.message)
                            val msg = tv?.text?.toString() ?: return
                            if (msg in BAD_TOASTS) onBadToast(msg)
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) { }
    }

    private fun onBadToast(msg: String) {
        try {
            val now = System.currentTimeMillis()
            if (State.waTriedRecently && now - State.waTriggeredAt < 2000) return
            State.waTriedRecently = true
            State.waTriggeredAt = now

            if (msg in END_CALL_TOASTS) endGsmCall()

            val number = State.lastDialedDigits ?: tryFetchLastOutgoingFromCallLog(3000)
            if (!number.isNullOrBlank()) {
                openWhatsAppFast(number)
                State.lastDialedDigits = null
            }
        } catch (_: Throwable) { }
    }

    private fun endGsmCall() {
        try {
            val app = currentApp()
            val tm = app.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
            if (tm != null && tm.endCall()) return
        } catch (_: Throwable) {}

        try {
            val smClz = Class.forName("android.os.ServiceManager")
            val getSvc = smClz.getMethod("getService", String::class.java)
            val binder = getSvc.invoke(null, "phone") as IBinder
            val stubClz = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            val asIf = stubClz.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val end = asIf.javaClass.getMethod("endCall")
            end.invoke(asIf)
        } catch (_: Throwable) {}
    }

    private fun openWhatsAppFast(numberDigits: String) {
        try {
            val normalized = numberDigits.replace("[^0-9]".toRegex(), "")
            if (normalized.isEmpty()) return
            val app = currentApp()

            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK

            try {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName("com.whatsapp", "com.whatsapp.HomeActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                app.startActivity(homeIntent)
            } catch (_: Throwable) {}

            try {
                val smsto = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$normalized")).apply {
                    addFlags(flags)
                    setPackage("com.whatsapp")
                }
                app.startActivity(smsto)
            } catch (_: Throwable) {}

            try {
                val token = System.nanoTime().toString()
                val waCallUri = Uri.parse("https://wa.me/$normalized")
                    .buildUpon()
                    .appendQueryParameter("call", "")
                    .appendQueryParameter("source", "call2wa")
                    .appendQueryParameter("t", token)
                    .build()

                val waCall = Intent(Intent.ACTION_VIEW, waCallUri).apply {
                    addFlags(flags)
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    setPackage("com.whatsapp")
                }
                app.startActivity(waCall)
                return
            } catch (_: Throwable) {}

            try {
                val jid = "$normalized@s.whatsapp.net"
                val conv = Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName("com.whatsapp", "com.whatsapp.Conversation")
                    putExtra("jid", jid)
                    addFlags(flags)
                }
                app.startActivity(conv)
            } catch (_: Throwable) {}

        } catch (_: Throwable) { }
    }

    private fun currentApp(): Application {
        val ctxClass = XposedHelpers.findClass("android.app.ActivityThread", null)
        return XposedHelpers.callStaticMethod(ctxClass, "currentApplication") as Application
    }

    private fun tryFetchLastOutgoingFromCallLog(windowMillis: Long): String? {
        return try {
            val app = currentApp()
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
        } catch (_: Throwable) { null }
    }
}
