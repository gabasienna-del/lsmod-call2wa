package com.example.call2wa

import android.content.Intent
import android.net.Uri
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookInit : IXposedHookLoadPackage {
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
                        val listener = object : PhoneStateListener() {
                            override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                                if (state == TelephonyManager.CALL_STATE_OFFHOOK && !incomingNumber.isNullOrBlank()) {
                                    val number = incomingNumber.replace("\\D+".toRegex(), "")
                                    openWhatsApp(number)
                                }
                            }
                        }
                        @Suppress("DEPRECATION")
                        tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA failed: ${e.message}")
        }
    }

    private fun openWhatsApp(number: String) {
        try {
            val uri = Uri.parse("https://wa.me/$number")
            val i = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.whatsapp")
            }
            val ctxClass = XposedHelpers.findClass("android.app.ActivityThread", null)
            val app = XposedHelpers.callStaticMethod(ctxClass, "currentApplication") as android.app.Application
            app.startActivity(i)
            Log.d("Call2WA", "Opening WhatsApp for number $number")
        } catch (e: Throwable) {
            XposedBridge.log("Call2WA openWhatsApp error: ${e.message}")
        }
    }
}
