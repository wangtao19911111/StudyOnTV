package com.studyontv.tv

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.util.Locale

open class DeviceBridge(protected val activity: Activity, private val connectionAllowed: Boolean = false, private val web: android.webkit.WebView? = null) {
    @JavascriptInterface
    fun answerInput(voice: Boolean) {
        activity.runOnUiThread { web?.let { InputSupport.open(activity, it, voice) } }
    }

    @JavascriptInterface
    fun answerText(draft: String, prompt: String) {
        activity.runOnUiThread { web?.let { InputSupport.openText(activity, it, draft, prompt) } }
    }

    @JavascriptInterface
    fun apiToken(): String = AdminAuth.deviceToken

    /** 连接页（家长已验证）里的按钮：权限设置 / 修改密码 */
    @JavascriptInterface
    fun openPermissions() {
        if (!connectionAllowed) return
        activity.runOnUiThread { activity.startActivity(Intent(activity, OnboardingActivity::class.java)) }
    }

    @JavascriptInterface
    fun changePassword() {
        if (!connectionAllowed) return
        activity.runOnUiThread { (activity as? ParentActivity)?.changePassword() }
    }

    @JavascriptInterface
    fun deviceInfo(): String = JSONObject().put("platform", if (isTv(activity)) "android-tv" else "android")
        .put("model", Build.MODEL).put("version", BuildConfig.VERSION_NAME).put("night", TvUi.systemNight(activity)).toString()

    @JavascriptInterface
    fun lanInfo(): String = if (connectionAllowed) DeviceRuntime.connection(activity).toString() else ""

    @JavascriptInterface
    fun openParent() {
        activity.runOnUiThread {
            activity.startActivity(Intent(activity, ParentActivity::class.java))
        }
    }

    @JavascriptInterface
    fun exitToLauncher() {
        val status = DeviceRuntime.learning.status()
        if (status.getString("reason").isNotEmpty() || (status.optLong("remainingSeconds") <= 0 && !status.optBoolean("free"))) return
        activity.runOnUiThread {
            GateService.hideGates()
            // 答题结束后优先 moveTaskToBack，让之前的应用（如腾讯视频）重新回到前台。
            // 仅在 GateActivity 场景（全屏管控页）才主动跳桌面，因为它本身就是管控页不应保留在栈里。
            if (activity is GateActivity) {
                activity.finish()
                try {
                    activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Diag.error("UI", "HOME intent rejected", e)
                }
            } else {
                // MainActivity / 普通场景：退到后台，之前的视频 App 自动回到前台
                activity.moveTaskToBack(true)
            }
        }
    }

    @JavascriptInterface
    fun backToQuiz() {
        activity.runOnUiThread {
            activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            if (activity is ParentActivity) activity.finish()
        }
    }

    @JavascriptInterface
    fun updateInfo(): String {
        val info = Updater.newerThanCurrent(activity, Updater.cachedInfo(activity)) ?: return ""
        return Updater.toJson(info).put("current", Updater.currentVersion(activity)).toString()
    }

    @JavascriptInterface
    fun installUpdate(url: String, latest: String, notes: String) {
        val cached = Updater.newerThanCurrent(activity, Updater.cachedInfo(activity))
        val payload = if (cached != null && cached.url == url) Updater.toJson(cached) else JSONObject()
            .put("latest", latest).put("notes", notes).put("url", url)
        payload.put("current", BuildConfig.VERSION_NAME)
        activity.runOnUiThread {
            activity.startActivity(Intent(activity, UpdateActivity::class.java).putExtra("payload", payload.toString()))
        }
    }

    /**
     * 朗读题目。lang："en" 用英语发音，其它走中文。
     * 题干可能中英混排，语言选择以科目为准，引擎不支持该语言时再互相回退。
     */
    @JavascriptInterface
    fun readAloud(text: String, lang: String) {
        if (text.isBlank()) return
        val locale = if (lang.startsWith("en", ignoreCase = true)) Locale.US else Locale.CHINESE
        activity.runOnUiThread { speak(text, locale) }
    }

    @JavascriptInterface
    fun stopReading() {
        activity.runOnUiThread {
            pendingText = null
            tts?.stop()
        }
    }

    /** 系统里是否装了 TTS 引擎，网页据此决定要不要显示朗读相关 UI */
    @JavascriptInterface
    fun ttsAvailable(): Boolean =
        activity.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()

    private fun speak(text: String, locale: Locale) {
        val engine = tts
        if (engine == null) {
            // 初始化是异步的：先把要读的内容挂起，onInit 成功后立刻读
            pendingText = text
            pendingLocale = locale
            ttsReady = false
            tts = TextToSpeech(activity.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts?.setSpeechRate(0.9f) // 给孩子读题，稍慢一点
                    val queued = pendingText
                    if (!queued.isNullOrBlank()) {
                        tts?.let { applyLocale(it, pendingLocale ?: Locale.CHINESE) }
                        tts?.speak(queued, TextToSpeech.QUEUE_FLUSH, null, "sotv_read")
                    }
                    pendingText = null
                } else {
                    // 初始化失败要释放实例，否则后续调用会永远挂在 pending 上
                    tts?.shutdown()
                    tts = null
                    ttsReady = false
                    pendingText = null
                }
            }
            return
        }
        if (!ttsReady) {
            pendingText = text
            pendingLocale = locale
            return
        }
        applyLocale(engine, locale)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sotv_read")
    }

    private fun applyLocale(engine: TextToSpeech, locale: Locale) {
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // 只装了英文引擎时中文题也尽量读，反之亦然
            engine.setLanguage(if (locale.language == "en") Locale.CHINESE else Locale.US)
        }
    }

    companion object {
        fun isTv(context: Context): Boolean = BuildConfig.FLAVOR == "tv"

        // 进程级共享引擎：TV 端 WebView 在 onStop 会销毁重建，DeviceBridge 跟着重建，
        // 引擎若实例化在 bridge 上会反复创建并泄漏 TextToSpeech。
        private var tts: TextToSpeech? = null
        private var ttsReady = false
        private var pendingText: String? = null
        private var pendingLocale: Locale? = null
    }
}
