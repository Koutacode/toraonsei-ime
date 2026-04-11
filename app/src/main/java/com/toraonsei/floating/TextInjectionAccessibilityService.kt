package com.toraonsei.floating

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class TextInjectionAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: we only use this service for text injection on demand.
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
        super.onDestroy()
    }

    private fun inject(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = findFocusedEditableNode(root)
        if (focused != null) {
            val result = performTextInjection(focused, text)
            focused.recycle()
            root.recycle()
            return result
        }
        root.recycle()
        return false
    }

    private fun findFocusedEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            return focused
        }
        focused?.recycle()

        // Fallback: search for any focused editable node
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
        // Try ACTION_SET_TEXT first (API 21+)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return true
        }

        // Fallback: paste from clipboard
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
