package com.studyontv.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.webkit.WebView

/**
 * 把遥控器方向键 / OK / 返回转发给学习页里的 window.TV。
 *
 * 隐藏的家长入口：焦点已在最上方（↑ 无法再移动）时连续短按 ↑ 5 次。
 * 以前只要连按 5 次 ↑ 就会触发，孩子在选项之间上移时很容易误开家长入口。
 * 页面没加载出来（JS 返回 null）时也计数，保证始终能进入家长入口。
 */
class RemoteKeys(private val openParent: () -> Unit) {
    private var upCount = 0
    private var lastUp = 0L

    /** @return true 表示已处理该按键 */
    fun handle(web: WebView?, event: KeyEvent): Boolean {
        val code = event.keyCode
        val js = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> "move('left')"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "move('right')"
            KeyEvent.KEYCODE_DPAD_UP -> "move('up')"
            KeyEvent.KEYCODE_DPAD_DOWN -> "move('down')"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "select()"
            KeyEvent.KEYCODE_BACK -> "back()"
            // fork 定制：菜单键 / 麦克风语音键重读当前题目
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_VOICE_ASSIST -> "readAloud()"
            else -> return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        // 长按 OK / 返回 / 菜单不重复触发（多选题长按会来回勾选、提交按钮会重复提交、朗读会不断重启）
        if (event.repeatCount > 0 && (js == "select()" || js == "back()" || js == "readAloud()")) return true
        if (code != KeyEvent.KEYCODE_DPAD_UP && event.repeatCount == 0) upCount = 0
        if (web == null) {
            if (code == KeyEvent.KEYCODE_DPAD_UP) countUp(event)
            return true
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            web.evaluateJavascript("window.TV?TV.move('up'):null") { result -> if (result != "true") countUp(event) }
        } else {
            web.evaluateJavascript("window.TV&&TV.$js", null)
        }
        return true
    }

    private fun countUp(event: KeyEvent) {
        if (event.repeatCount > 0) return
        val now = SystemClock.elapsedRealtime()
        upCount = if (now - lastUp < 900) upCount + 1 else 1
        lastUp = now
        if (upCount >= 5) { upCount = 0; openParent() }
    }
}
