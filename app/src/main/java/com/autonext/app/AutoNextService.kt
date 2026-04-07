package com.autonext.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * AutoNextService - ad auto-skip engine.
 *
 * How it works:
 *  1. Listens for global window state/content change events across all foreground apps.
 *  2. Runs a BFS traversal on each event's node tree and uses combined text + view ID matching
 *     to locate Skip/Next buttons.
 *  3. Performs ACTION_CLICK on a match, or falls back to the nearest clickable parent when needed.
 *  4. Uses payment/purchase blacklist terms to avoid false taps, and throttles repeat clicks on
 *     the same screen.
 */
class AutoNextService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoNextService"

        /** Target text keywords matched case-insensitively by substring. */
        private val TARGET_TEXTS = listOf(
            "skip", "next", "跳过", "下一步", "关闭", "close"
        )

        /** Target view ID fragments matched against viewIdResourceName by case-insensitive substring. */
        private val TARGET_VIEW_IDS = listOf(
            "skip", "next", "btn_skip", "btn_next",
            "ad_skip", "ad_close", "skip_btn", "next_btn", "close_ad",
            "close", "btn_close", "iv_close", "img_close", "close_btn", "dialog_close", "popup_close"
        )

        /**
         * Blacklist text fragments. Even if the text or ID matches, any node containing one of
         * these terms will be rejected to avoid tapping payment or checkout buttons.
         */
        private val BLACKLIST_TEXTS = listOf(
            "支付", "购买", "下单", "确认支付", "立即购买", "去结算", "加入", "会员",
            "pay", "purchase", "buy", "checkout", "place order", "VIP", "Join"
        )

        private const val MIN_CLICK_INTERVAL_MS = 800L  // Minimum interval between clicks.
        private const val MAX_CLICKS_PER_WINDOW = 5     // Maximum auto-clicks allowed per window.
        private const val MAX_PARENT_WALK_DEPTH = 5     // Maximum parent depth to search for a clickable ancestor.
    }

    // ---- Runtime state ----------------------------------------------------

    @Volatile private var lastClickTimeMs = 0L

    /** Identifies the current top-level window by "package/class" so counters reset on window change. */
    private var currentWindowToken = ""
    private var clickCountThisWindow = 0

    /**
     * Tracks identity hash codes for clicked nodes in the current window to avoid duplicate taps.
     * AccessibilityNodeInfo instances are short-lived, so hashCode is sufficient here.
     */
    private val clickedNodeKeys = mutableSetOf<Int>()

    // ---- AccessibilityService callbacks -----------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        Log.v(TAG, "event type=${event.eventType} pkg=${event.packageName}")

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // A new window appeared: refresh the window token and reset counters.
                val token = "${event.packageName}/${event.className}"
                if (token != currentWindowToken) {
                    currentWindowToken = token
                    clickCountThisWindow = 0
                    clickedNodeKeys.clear()
                }
                scanAndClick(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Content updated dynamically, for example when a countdown reveals a skip button.
                scanAndClick(event)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // Window stack changed, useful for overlay dialogs and popups.
                scanAndClick(event)
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt")
    }

    // ---- Scan and click ---------------------------------------------------

    private fun scanAndClick(event: AccessibilityEvent) {
        // Throttle if this window already reached the click limit or the last click was too recent.
        if (clickCountThisWindow >= MAX_CLICKS_PER_WINDOW) return
        val now = System.currentTimeMillis()
        if (now - lastClickTimeMs < MIN_CLICK_INTERVAL_MS) return

        val root = event.source ?: rootInActiveWindow ?: return
        val candidate = findCandidate(root) ?: return

        // Deduplicate clicks for the same node instance.
        val nodeKey = System.identityHashCode(candidate)
        if (nodeKey in clickedNodeKeys) return

        val clicked = doClick(candidate)
        if (clicked) {
            lastClickTimeMs = now
            clickCountThisWindow++
            clickedNodeKeys.add(nodeKey)
            Log.i(
                TAG,
                "✓ Clicked → text='${candidate.text}' " +
                    "id='${candidate.viewIdResourceName}' " +
                    "pkg=${event.packageName}"
            )
        }
    }

    // ---- Node lookup ------------------------------------------------------

    /**
     * Traverses the node tree with BFS and returns the best candidate by priority:
        *  Priority 1: both text and view ID match (highest confidence).
        *  Priority 2: text match only (high confidence).
        *  Priority 3: view ID match only (fallback).
     */
    private fun findCandidate(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var textAndId: AccessibilityNodeInfo? = null
        var textOnly:  AccessibilityNodeInfo? = null
        var idOnly:    AccessibilityNodeInfo? = null
        var closeIcon: AccessibilityNodeInfo? = null

        bfs(root) { node ->
            val text    = mergeLabels(node).lowercase().trim()
            val viewId  = node.viewIdResourceName?.lowercase() ?: ""

            // Reject blacklisted nodes before any positive match is considered.
            if (isBlacklisted(text)) return@bfs

            val textHit = isTargetText(text)
            val idHit   = isTargetViewId(viewId)

            when {
                textHit && idHit  && textAndId == null -> textAndId = node
                textHit && !idHit && textOnly  == null -> textOnly  = node
                !textHit && idHit && idOnly    == null -> idOnly    = node
                closeIcon == null && isCloseIcon(node, text, viewId, root) -> closeIcon = node
            }
        }

        return textAndId ?: textOnly ?: idOnly ?: closeIcon
    }

    // ---- Matching predicates ----------------------------------------------

    private fun isTargetText(text: String): Boolean =
        text.isNotBlank() && TARGET_TEXTS.any { text.contains(it, ignoreCase = true) }

    private fun isTargetViewId(viewId: String): Boolean =
        viewId.isNotBlank() && TARGET_VIEW_IDS.any { viewId.contains(it, ignoreCase = true) }

    private fun isBlacklisted(text: String): Boolean =
        text.isNotBlank() && BLACKLIST_TEXTS.any { text.contains(it, ignoreCase = true) }

    /** Heuristic for popup close icon in top-right area, such as "x"/"×". */
    private fun isCloseIcon(
        node: AccessibilityNodeInfo,
        text: String,
        viewId: String,
        root: AccessibilityNodeInfo
    ): Boolean {
        val compact = text.replace(" ", "")
        val symbolHit = compact == "x" || compact == "×" || compact == "✕" || compact == "✖"
        val textHit = compact == "close" || compact == "关闭"
        val idHit = viewId.contains("close", ignoreCase = true)
        if (!symbolHit && !textHit && !idHit) return false

        // Prefer nodes likely to be popup close buttons in top-right area.
        val nodeRect = Rect()
        val rootRect = Rect()
        node.getBoundsInScreen(nodeRect)
        root.getBoundsInScreen(rootRect)
        if (rootRect.width() <= 0 || rootRect.height() <= 0) return false

        val inTopArea = nodeRect.centerY() <= (rootRect.top + rootRect.height() * 0.35)
        val inRightArea = nodeRect.centerX() >= (rootRect.left + rootRect.width() * 0.65)
        val clickable = node.isClickable || node.parent?.isClickable == true
        return inTopArea && inRightArea && clickable
    }

    /** Merges text and contentDescription so matches are not missed when only one is populated. */
    private fun mergeLabels(node: AccessibilityNodeInfo): String =
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .joinToString(" ")

    // ---- Utilities --------------------------------------------------------

    /** Performs a BFS traversal and invokes [action] for each node. */
    private fun bfs(root: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            action(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
    }

    /**
        * Clicks a node:
        *  - If the node itself is clickable, perform ACTION_CLICK directly.
        *  - Otherwise walk up at most MAX_PARENT_WALK_DEPTH levels to find the nearest clickable ancestor.
     */
    private fun doClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        var parent: AccessibilityNodeInfo? = node.parent
        repeat(MAX_PARENT_WALK_DEPTH) {
            parent?.let {
                if (it.isClickable) {
                    return it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                parent = it.parent
            }
        }
        return false
    }
}
