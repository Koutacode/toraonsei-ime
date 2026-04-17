package com.toraonsei.floating

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.toraonsei.session.AppContextProvider

class TextInjectionAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                AppContextProvider.updateFromAccessibility(applicationContext, event.packageName)
            }
        }
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        AppContextProvider.clear()
        super.onDestroy()
    }

    private fun inject(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = findFocusedEditableNode(root)
        if (focused != null) {
            if (isSensitiveField(focused)) {
                focused.recycle()
                root.recycle()
                android.widget.Toast.makeText(
                    this,
                    "パスワード/認証欄には挿入できません",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return false
            }
            val result = performTextInjection(focused, text)
            focused.recycle()
            root.recycle()
            return result
        }
        root.recycle()
        return false
    }

    private fun isSensitiveField(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        val inputType = node.inputType
        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        val textPasswordVariations = setOf(
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        )
        if ((inputType and android.text.InputType.TYPE_MASK_CLASS) ==
            android.text.InputType.TYPE_CLASS_TEXT &&
            variation in textPasswordVariations
        ) {
            return true
        }
        if ((inputType and android.text.InputType.TYPE_MASK_CLASS) ==
            android.text.InputType.TYPE_CLASS_NUMBER &&
            variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        ) {
            return true
        }
        val hint = node.hintText?.toString()?.lowercase().orEmpty()
        val contentDesc = node.contentDescription?.toString()?.lowercase().orEmpty()
        val sensitiveHints = listOf("password", "パスワード", "暗証", "pin", "認証コード", "verification")
        return sensitiveHints.any { hint.contains(it) || contentDesc.contains(it) }
    }

    private fun findFocusedEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            return focused
        }
        focused?.recycle()
        return findEditableNodeRecursive(root)
    }

    private fun findEditableNodeRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNodeRecursive(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun performTextInjection(node: AccessibilityNodeInfo, text: String): Boolean {
        val existing = node.text?.toString().orEmpty()
        val combined = if (existing.isBlank()) text else "$existing$text"
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return true
        }

        val clipboard = android.content.ClipData.newPlainText("toraonsei", text)
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(clipboard)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    companion object {
        @Volatile
        private var instance: TextInjectionAccessibilityService? = null

        fun isAvailable(): Boolean = instance != null

        fun injectText(text: String): Boolean {
            return instance?.inject(text) ?: false
        }
    }
}
