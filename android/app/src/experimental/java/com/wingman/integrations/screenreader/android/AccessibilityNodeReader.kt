package com.wingman.integrations.screenreader.android

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.wingman.integrations.screenreader.NodeBounds
import com.wingman.integrations.screenreader.ScreenNode
import com.wingman.integrations.screenreader.ScreenSnapshot

/*
 * THIN ANDROID ADAPTER (D-032, experimental build only). Compiled in CI; never run against a real
 * dating app: every device behaviour is UNVERIFIED.
 *
 * READ-ONLY: this file only calls getters of the node it is handed. It asks a node for nothing but
 * its class, view id, text, flags, bounds and children.
 */

/** Copies a window's node tree into the pure-Kotlin [ScreenSnapshot] model. */
internal object AccessibilityNodeReader {
    const val MAX_NODES = 1_200
    const val MAX_DEPTH = 40
    const val MAX_TEXT_CHARS = 2_000

    /**
     * @param readText false for inspector-only reading: no text is copied at all, only its length.
     *   Whatever the mode, an image's text, a password field's text and every content description
     *   are never copied (a description only as a length).
     */
    fun snapshot(root: AccessibilityNodeInfo, packageName: String, nowMillis: Long, readText: Boolean): ScreenSnapshot {
        val budget = intArrayOf(MAX_NODES)
        return ScreenSnapshot(packageName, nowMillis, convert(root, 0, budget, readText))
    }

    private fun convert(node: AccessibilityNodeInfo, depth: Int, budget: IntArray, readText: Boolean): ScreenNode {
        budget[0]--
        val className = node.className?.toString()
        val raw: CharSequence? = if (node.isPassword) null else node.text
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val shell = ScreenNode(className = className)
        val children = ArrayList<ScreenNode>()
        if (depth < MAX_DEPTH) {
            for (i in 0 until node.childCount) {
                if (budget[0] <= 0) break
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                children += convert(child, depth + 1, budget, readText)
            }
        }
        return ScreenNode(
            className = className,
            viewId = node.viewIdResourceName,
            text = if (readText && !shell.isImage) raw?.toString()?.take(MAX_TEXT_CHARS) else null,
            textLength = raw?.length ?: 0,
            descriptionLength = node.contentDescription?.length ?: 0,
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            editable = node.isEditable,
            heading = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && node.isHeading,
            bounds = NodeBounds(rect.left, rect.top, rect.right, rect.bottom),
            children = children,
        )
    }
}
