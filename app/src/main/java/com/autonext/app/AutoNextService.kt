package com.autonext.app

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

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

        const val PREFS_NAME = "autonext_prefs"
        const val KEY_ALLOWLIST_ENABLED = "key_allowlist_enabled"
        const val KEY_ALLOWLIST_TEXT = "key_allowlist_text"

        /** Target text keywords matched case-insensitively by substring. */
        private val TARGET_TEXTS = listOf(
            "skip", "next", "跳过", "下一步"
        )

        /** Target view ID fragments matched against viewIdResourceName by case-insensitive substring. */
        private val TARGET_VIEW_IDS = listOf(
            "skip", "next", "btn_skip", "btn_next",
            "ad_skip", "skip_btn", "next_btn"
        )

        /** Close-related text/viewId hints, used only with ad-context validation. */
        private val CLOSE_HINTS = listOf(
            "close", "关闭", "關閉", "ad_close", "close_ad", "btn_close", "iv_close",
            "img_close", "close_btn", "dialog_close", "popup_close"
        )

        /** Popup context must contain ad/sponsored words before any close action is allowed. */
        private val AD_CONTEXT_HINTS = listOf(
            "广告", "廣告", "赞助", "贊助", "AD"
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
        private const val STATUS_NOTIFICATION_ID = 1001
        private const val STATUS_CHANNEL_ID = "adgo_service_status"

        /**
         * When enabled, service only runs for packages that match [PACKAGE_ALLOWLIST].
         * Keep disabled by default to support all apps.
         */
        const val DEFAULT_ENABLE_PACKAGE_ALLOWLIST = false

        /** Optional package fragments to allow when allowlist mode is enabled. */
        val DEFAULT_ALLOWLIST = listOf(
            "com.ss.android",
            "com.tencent",
            "tv.danmaku.bili"
        )

        /** Always blocked package fragments to avoid risky taps in system or purchase flows. */
        private val PACKAGE_DENYLIST = listOf(
            "com.autonext.app",
            "com.android.systemui",
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.android.vending"
        )
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        showStatusNotification()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString()?.lowercase() ?: ""
        if (!shouldHandlePackage(pkg)) return

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

    override fun onDestroy() {
        hideStatusNotification()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hideStatusNotification()
        return super.onUnbind(intent)
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
        val rootContextText = collectRootContext(root)

        bfs(root) { node ->
            val text    = mergeLabels(node).lowercase().trim()
            val viewId  = node.viewIdResourceName?.lowercase() ?: ""

            // Reject blacklisted nodes before any positive match is considered.
            if (isBlacklisted(text)) return@bfs

            val textHit = isTargetText(text)
            val idHit   = isTargetViewId(viewId)
            val closeHit = isCloseHint(text, viewId)
            val allowClose = closeHit && hasAdContext(node, rootContextText)

            when {
                textHit && idHit  && textAndId == null -> textAndId = node
                textHit && !idHit && textOnly  == null -> textOnly  = node
                !textHit && idHit && idOnly    == null -> idOnly    = node
                closeIcon == null && allowClose && isCloseIcon(node, text, viewId, root) -> closeIcon = node
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

    private fun isCloseHint(text: String, viewId: String): Boolean {
        val compact = text.replace(" ", "")
        val symbolHit = compact == "x" || compact == "×" || compact == "✕" || compact == "✖"
        return symbolHit || CLOSE_HINTS.any {
            compact.contains(it, ignoreCase = true) || viewId.contains(it, ignoreCase = true)
        }
    }

    private fun hasAdContext(node: AccessibilityNodeInfo, rootContextText: String): Boolean {
        if (containsAdContext(rootContextText)) return true

        val local = StringBuilder()
        var parent: AccessibilityNodeInfo? = node
        repeat(3) {
            parent?.let {
                local.append(' ')
                local.append(mergeLabels(it))
                local.append(' ')
                local.append(it.viewIdResourceName.orEmpty())
                parent = it.parent
            }
        }

        node.parent?.let { p ->
            for (i in 0 until p.childCount) {
                p.getChild(i)?.let { child ->
                    local.append(' ')
                    local.append(mergeLabels(child))
                    local.append(' ')
                    local.append(child.viewIdResourceName.orEmpty())
                }
            }
        }
        return containsAdContext(local.toString())
    }

    private fun containsAdContext(text: String): Boolean =
        text.isNotBlank() && AD_CONTEXT_HINTS.any { text.contains(it, ignoreCase = true) }

    private fun collectRootContext(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        bfs(root) { node ->
            sb.append(' ')
            sb.append(mergeLabels(node))
            sb.append(' ')
            sb.append(node.viewIdResourceName.orEmpty())
        }
        return sb.toString()
    }

    /** Package-level gating to reduce accidental clicks on sensitive/system screens. */
    private fun shouldHandlePackage(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        if (PACKAGE_DENYLIST.any { pkg.contains(it, ignoreCase = true) }) return false

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val allowlistEnabled = prefs.getBoolean(KEY_ALLOWLIST_ENABLED, DEFAULT_ENABLE_PACKAGE_ALLOWLIST)
        if (!allowlistEnabled) return true

        val raw = prefs.getString(KEY_ALLOWLIST_TEXT, DEFAULT_ALLOWLIST.joinToString("\n")).orEmpty()
        val allowlist = parseAllowlist(raw)
        if (allowlist.isEmpty()) return false
        return allowlist.any { pkg.contains(it, ignoreCase = true) }
    }

    private fun parseAllowlist(raw: String): List<String> =
        raw.split('\n', ',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

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

    private fun showStatusNotification() {
        ensureStatusNotificationChannel()

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(getString(R.string.notif_title_enabled))
            .setContentText(getString(R.string.notif_text_enabled))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        NotificationManagerCompat.from(this).notify(STATUS_NOTIFICATION_ID, notification)
    }

    private fun hideStatusNotification() {
        NotificationManagerCompat.from(this).cancel(STATUS_NOTIFICATION_ID)
    }

    private fun ensureStatusNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            STATUS_CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
