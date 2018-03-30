package com.gh0u1l5.wechatmagician.backend

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.XModuleResources
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Environment.MEDIA_MOUNTED
import android.support.v4.app.Fragment
import android.view.MotionEvent
import android.view.View
import android.widget.*
import android.widget.Toast.LENGTH_SHORT
import com.gh0u1l5.wechatmagician.BuildConfig
import com.gh0u1l5.wechatmagician.Global.ACTION_REQUIRE_HOOK_STATUS
import com.gh0u1l5.wechatmagician.Global.ACTION_REQUIRE_REPORTS
import com.gh0u1l5.wechatmagician.Global.MAGICIAN_PACKAGE_NAME
import com.gh0u1l5.wechatmagician.Global.PREFERENCE_NAME_DEVELOPER
import com.gh0u1l5.wechatmagician.Global.PREFERENCE_NAME_SETTINGS
import com.gh0u1l5.wechatmagician.backend.plugins.*
import com.gh0u1l5.wechatmagician.backend.storage.Preferences
import com.gh0u1l5.wechatmagician.backend.storage.list.ChatroomHideList
import com.gh0u1l5.wechatmagician.backend.storage.list.SecretFriendList
import com.gh0u1l5.wechatmagician.spellbook.C
import com.gh0u1l5.wechatmagician.spellbook.SpellBook
import com.gh0u1l5.wechatmagician.spellbook.SpellBook.getApplicationApkPath
import com.gh0u1l5.wechatmagician.spellbook.SpellBook.isImportantWechatProcess
import com.gh0u1l5.wechatmagician.spellbook.WechatGlobal.wxVersion
import com.gh0u1l5.wechatmagician.spellbook.WechatStatus
import com.gh0u1l5.wechatmagician.spellbook.util.BasicUtil.tryAsynchronously
import com.gh0u1l5.wechatmagician.spellbook.util.BasicUtil.tryVerbosely
import com.gh0u1l5.wechatmagician.spellbook.util.MirrorUtil.collectMirrorReports
import com.gh0u1l5.wechatmagician.spellbook.util.MirrorUtil.findAllMirrorObjects
import com.gh0u1l5.wechatmagician.util.FileUtil
import com.gh0u1l5.wechatmagician.util.FileUtil.createTimeTag
import dalvik.system.PathClassLoader
import de.robv.android.xposed.*
import de.robv.android.xposed.XposedBridge.log
import de.robv.android.xposed.XposedHelpers.findAndHookMethod
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method

// WechatHook is the entry point of the module, here we load all the plugins.
class WechatHook : IXposedHookLoadPackage {

    companion object {
        @Volatile var resources: XModuleResources? = null

        val settings = Preferences(PREFERENCE_NAME_SETTINGS)
        val developer = Preferences(PREFERENCE_NAME_DEVELOPER)

        private val plugins = listOf (
                AdBlock,
                Alert,
                AntiRevoke,
                AntiSnsDelete,
                AutoLogin,
                ChatroomHider,
                Donate,
                Limits,
                MarkAllAsRead,
                ObjectsHunter,
                SecretFriend,
                SnsBlock,
                SnsForward
        )

        private val requireHookStatusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                setResultExtras(Bundle().apply {
                    putIntArray("status", WechatStatus.report())
                })
            }
        }

        private val requireMagicianReportReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val state = Environment.getExternalStorageState()
                if (state != MEDIA_MOUNTED) {
                    val message = "Error: SD card is not presented! (state: $state)"
                    Toast.makeText(context, message, LENGTH_SHORT).show()
                }

                val storage = Environment.getExternalStorageDirectory().absolutePath
                val reportPath = "$storage/WechatMagician/reports/report-${createTimeTag()}.txt"

                tryAsynchronously {
                    val apkPath = getApplicationApkPath(MAGICIAN_PACKAGE_NAME)
                    val reportHead = listOf (
                            "Device: SDK${Build.VERSION.SDK_INT}-${Build.PRODUCT}",
                            "Xposed Version: ${XposedBridge.XPOSED_BRIDGE_VERSION}",
                            "Wechat Version: $wxVersion",
                            "Module Version: ${BuildConfig.VERSION_NAME}"
                    ).joinToString("\n")
                    val reportBody = collectMirrorReports(findAllMirrorObjects(apkPath))
                            .joinToString("\n") { "${it.first} -> ${it.second}" }
                    FileUtil.writeBytesToDisk(reportPath, "$reportHead\n$reportBody".toByteArray())
                }

                resultData = reportPath
            }
        }
    }

    // hookAttachBaseContext is a stable way to get current application on all the platforms.
    private inline fun hookAttachBaseContext(loader: ClassLoader, crossinline callback: (Context) -> Unit) {
        findAndHookMethod(
                "android.content.ContextWrapper", loader, "attachBaseContext",
                Context::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                callback(param.thisObject as? Application ?: return)
            }
        })
    }

    // NOTE: Remember to catch all the exceptions here, otherwise you may get boot loop.
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        tryVerbosely {
            when (lpparam.packageName) {
                MAGICIAN_PACKAGE_NAME ->
                    hookAttachBaseContext(lpparam.classLoader, { _ ->
                        handleLoadMagician(lpparam.classLoader)
                    })
                else -> if (isImportantWechatProcess(lpparam)) {
                    log("Wechat Magician: process = ${lpparam.processName}, version = ${BuildConfig.VERSION_NAME}")
                    hookAttachBaseContext(lpparam.classLoader, { context ->
                        if (!BuildConfig.DEBUG) {
                            handleLoadWechat(lpparam, context)
                        } else {
                            handleLoadWechatOnFly(lpparam, context)
                        }
                    })
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun handleLoadMagician(loader: ClassLoader) {
        findAndHookMethod(
                "$MAGICIAN_PACKAGE_NAME.frontend.fragments.StatusFragment", loader,
                "isModuleLoaded", object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: MethodHookParam): Any = true
        })
        findAndHookMethod(
                "$MAGICIAN_PACKAGE_NAME.frontend.fragments.StatusFragment", loader,
                "getXposedVersion", object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: MethodHookParam): Any = XposedBridge.XPOSED_BRIDGE_VERSION
        })
    }

    // handleLoadWechat is the entry point for Wechat hooking logic.
    private fun handleLoadWechat(lpparam: XC_LoadPackage.LoadPackageParam, context: Context) {
        // Register receivers for frontend communications
        tryVerbosely {
            context.registerReceiver(requireHookStatusReceiver, IntentFilter(ACTION_REQUIRE_HOOK_STATUS))
            context.registerReceiver(requireMagicianReportReceiver, IntentFilter(ACTION_REQUIRE_REPORTS))
        }

        // Load module resources to current process
        tryAsynchronously {
            val path = getApplicationApkPath(MAGICIAN_PACKAGE_NAME)
            resources = XModuleResources.createInstance(path, null)
            WechatStatus.toggle(WechatStatus.StatusFlag.STATUS_FLAG_RESOURCES)
        }

        // Initialize the shared preferences
        // TODO: check why it no longer works after restarting Wechat
        settings.listen(context)
        settings.load(context)
        developer.listen(context)
        developer.load(context)

        // Initialize the localized strings and the lists generated by plugins
        SecretFriendList.load(context)
        ChatroomHideList.load(context)

        // Launch Wechat SpellBook
        if (!BuildConfig.DEBUG) {
            SpellBook.startup(lpparam, plugins, listOf(Limits))
        } else {
            SpellBook.startup(lpparam, plugins, listOf(Limits, Developer))
        }

        custom(lpparam)

    }

    private fun isViewShown(view: View?): Boolean {
        val isShown = view?.isShown ?: false
        return isShown
    }

    private fun custom(lpparam: XC_LoadPackage.LoadPackageParam){

//        // 1. 对话列表页创建的时候，保存this引用；每次onResume时候，直接调用onItemClick方法
//        val conversationUI = "com.tencent.mm.ui.conversation.ConversationWithAppBrandListView"
//        val conversationUIClass = XposedHelpers.findClass(conversationUI, lpparam.classLoader)
//        findAndHookMethod(conversationUIClass, "onDraw", Canvas::class.java, object : XC_MethodHook() {
//            override fun afterHookedMethod(param: MethodHookParam) {
//                // TODO: 再次进入微信调用方法会卡死, 關閉微信，再進入，這個方法會重複進入多次
//                val conversationListView = param.thisObject as? ListView
//                val isShown = conversationListView?.isShown
//                XposedBridge.log("isShown:$isShown")
//                val canvas: Canvas? = param.args[0] as? Canvas
//                XposedBridge.log(canvas?.toString())
////                val firstVisiblePosition:Int? = conversationListView?.firstVisiblePosition
////                val firstChild = conversationListView?.getChildAt(firstVisiblePosition!!)
////                if (firstVisiblePosition != null) {
////                    conversationListView.onItemClickListener.onItemClick(conversationListView, firstChild, firstVisiblePosition, 0)
////                }
//            }
//        })


        // dollar 符号在Java中会被转为 .
        val chattingUIName = "com.tencent.mm.ui.chatting.ChattingUI\$a"
        val chattingUI = XposedHelpers.findClass(chattingUIName, lpparam.classLoader)
        val chatFooterName = "com.tencent.mm.ui.chatting.b.j"
        findAndHookMethod(chatFooterName, lpparam.classLoader, "cup", object : XC_MethodHook() {
            // 在cup的cuq方法会隐藏键盘，所以在方法执行完成后，我们唤起键盘
            override fun afterHookedMethod(param: MethodHookParam) {
                XposedBridge.log("开始HOOK调用" + param.thisObject.javaClass.canonicalName)
                // 显示键盘
                val ctpMethod = chattingUI.getMethod("ctp")
                val chattingUIInstance = XposedHelpers.findField(param.thisObject.javaClass, "fhH").get(param.thisObject)

                val chatFooter = ctpMethod.invoke(chattingUIInstance) as LinearLayout
                if(!isViewShown(chatFooter) || chatFooter.visibility != View.VISIBLE){
                    XposedBridge.log("not visible to user, just return")
                    return
                }

                val textContent = "看甜美可爱的阳毅哥" + System.currentTimeMillis()
                val chatFooterSetContent = XposedHelpers.findMethodExact(chatFooter.javaClass, "Td", C.String)
                chatFooterSetContent.invoke(chatFooter, textContent)

                val textInput = XposedHelpers.findField(chatFooter.javaClass, "oqa").get(chatFooter) as EditText
                textInput.setText(textContent)
                textInput.setSelection(textContent.length)

//                val showVKBMethod = chatFooter.javaClass.getMethod("showVKB")
//                showVKBMethod.isAccessible = true
//                showVKBMethod.invoke(chatFooter)
                // 获取发送按钮
                val sendMsgButton: Button = XposedHelpers.findField(chatFooter.javaClass, "oqb").get(chatFooter) as Button
//                if(isViewShown(sendMsgButton) && sendMsgButton.visibility == View.VISIBLE){
                    sendMsgButton.performClick()

                val goBackAfterSent = true
                if(goBackAfterSent){
                    val goBackMethod = chattingUI.getDeclaredMethod("goBack")
                    goBackMethod.isAccessible = true
//                    val goBackMethod = XposedHelpers.findMethodExact(chattingUI, "goBack", null)
                    goBackMethod.invoke(chattingUIInstance)
                }
//                }else{
//                    XposedBridge.log("button没有显示${sendMsgButton.visibility}")
//                }

            }})
    }

    private fun sendMessage(lpparam: XC_LoadPackage.LoadPackageParam, message: String, exitAfterComplete: Boolean, chatFooter: LinearLayout){

    }

    // handleLoadWechatOnFly uses reflection to load updated module without reboot.
    private fun handleLoadWechatOnFly(lpparam: XC_LoadPackage.LoadPackageParam, context: Context) {
        val path = getApplicationApkPath(MAGICIAN_PACKAGE_NAME)
        if (!File(path).exists()) {
            log("Cannot load module on fly: APK not found")
            return
        }
        val pathClassLoader = PathClassLoader(path, ClassLoader.getSystemClassLoader())
        val clazz = Class.forName("$MAGICIAN_PACKAGE_NAME.backend.WechatHook", true, pathClassLoader)
        val method = clazz.getDeclaredMethod("handleLoadWechat", lpparam::class.java, Context::class.java)
        method.isAccessible = true
        method.invoke(clazz.newInstance(), lpparam, context)
    }
}
