package com.autonext.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityButtonController
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * AutoNextService — core ad auto-skip engine powered by Android Accessibility.
 *
 * Lifecycle:
 *  [onServiceConnected] → posts a persistent status notification and registers the
 *  system accessibility button so the user can pause/resume with one tap.
 *
 * Event processing pipeline:
 *  1. Listens for TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED, and
 *     TYPE_WINDOWS_CHANGED events across all foreground apps.
 *  2. [scanAndClick] throttles by interval and per-window click count, then delegates
 *     to [findCandidate] which performs a BFS traversal of the node tree.
 *  3. Node matching uses a priority system:
 *       P1 – both text AND view-ID match (highest confidence)
 *       P2 – text match only
 *       P3 – view-ID match only
 *       P4 – close-icon heuristic (symbol + ad-context + popup-layout validation)
 *  4. [doClick] performs ACTION_CLICK on the candidate, walking up to
 *     [MAX_PARENT_WALK_DEPTH] ancestors if the node itself is not clickable.
 *
 * Safety guards:
 *  • [BLACKLIST_TEXTS] rejects payment / checkout nodes to prevent accidental purchases.
 *  • [PACKAGE_DENYLIST] skips system UI, settings, and the app itself.
 *  • Per-window click cap ([MAX_CLICKS_PER_WINDOW]) and dedup via [clickedNodeKeys].
 *  • Popup close actions require ad-context keywords ([AD_CONTEXT_HINTS]) **and**
 *    layout-based popup validation ([isPopupBasedOnLayout]).
 */
class AutoNextService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoNextService"

        const val PREFS_NAME = "autonext_prefs"
        const val KEY_ALLOWLIST_ENABLED = "key_allowlist_enabled"
        const val KEY_ALLOWLIST_TEXT = "key_allowlist_text"

        /** Skip/Next button text keywords; matched case-insensitively by substring against merged node labels. */
        private val TARGET_TEXTS = listOf(
            "skip", "next", "跳过", "下一步"
        )

        /** Skip/Next view-ID fragments; matched case-insensitively against [AccessibilityNodeInfo.viewIdResourceName]. */
        private val TARGET_VIEW_IDS = listOf(
            "skip", "next", "btn_skip", "btn_next",
            "ad_skip", "skip_btn", "next_btn"
        )

        /** Close-related text / viewId hints — only acted on when ad-context ([AD_CONTEXT_HINTS]) is confirmed nearby. */
        private val CLOSE_HINTS = listOf(
            "close", "关闭", "關閉", "ad_close", "close_ad", "btn_close", "iv_close",
            "img_close", "close_btn", "dialog_close", "popup_close"
        )

        /** Ad-context keywords; the popup close path requires at least one match in the surrounding node tree. */
        private val AD_CONTEXT_HINTS = listOf(
            "广告", "廣告", "赞助", "贊助", "AD", "Promotion", 
            "Advertisement", "Sponsored", "Banner", "Popup",
            "Close Ad", "Skip Ad", "广告位", "推广", "营销"
        )

        /**
         * Blacklist text fragments — any node whose merged label contains one of these terms is
         * unconditionally rejected, even if it otherwise matches skip/next/close patterns.
         * Prevents accidental taps on payment, checkout, or VIP-purchase buttons.
         */
        private val BLACKLIST_TEXTS = listOf(
            "支付", "购买", "下单", "确认支付", "立即购买", "去结算", "加入", "会员",
            "pay", "purchase", "buy", "checkout", "place order", "VIP", "Join"
        )

        private const val MIN_CLICK_INTERVAL_MS = 800L  // Global cooldown between consecutive auto-clicks (ms).
        private const val MAX_CLICKS_PER_WINDOW = 5     // Cap auto-clicks per window token to limit runaway taps.
        private const val MAX_PARENT_WALK_DEPTH = 5     // How far up the tree [doClick] walks to find a clickable ancestor.
        private const val STATUS_NOTIFICATION_ID = 1001
        private const val STATUS_CHANNEL_ID = "adgo_service_status"

        /**
         * Master toggle for package-allowlist mode. When `false` (default) the service
         * monitors every foreground app (minus [PACKAGE_DENYLIST]). When `true`, only
         * packages matching the user-configured allowlist are processed.
         */
        const val DEFAULT_ENABLE_PACKAGE_ALLOWLIST = false

        /** Default package fragments shipped with the app; used when the user has not customized the allowlist. */
        val DEFAULT_ALLOWLIST = listOf(
            "com.ss.android",
            "com.tencent",
            "tv.danmaku.bili"
        )

        /** Hard-coded deny list — these packages are never processed regardless of allowlist settings. */
        private val PACKAGE_DENYLIST = listOf(
            "com.autonext.app",
            "com.android.systemui",
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.android.vending"
        )

        // ---- Layout detection thresholds for popup validation ----
        private const val POPUP_MIN_WIDTH_RATIO = 0.1
        private const val POPUP_MAX_WIDTH_RATIO = 0.8
        private const val POPUP_MIN_HEIGHT_RATIO = 0.1
        private const val POPUP_MAX_HEIGHT_RATIO = 0.6
        private const val CLOSE_ICON_TOP_AREA_THRESHOLD = 0.35
        private const val CLOSE_ICON_RIGHT_AREA_THRESHOLD = 0.65
        private const val POPUP_CENTER_DISTANCE_RATIO = 0.3
        private const val POPUP_TOP_AREA_THRESHOLD = 0.2
        private const val PARENT_WALK_DEPTH_FOR_CONTEXT = 3
        private const val CLOSE_SYMBOL_MAX_WALK_DEPTH = 3

        /** Single-character close-button symbols recognized as potential close icons. */
        private val CLOSE_SYMBOLS = setOf("x", "×", "✕", "✖")
    }

    // ====================================================================
    // Runtime state
    // ====================================================================

    /** Epoch millis of the most recent successful auto-click (global throttle). */
    @Volatile private var lastClickTimeMs = 0L

    /** When `true` the scan loop short-circuits; toggled via the accessibility button. */
    @Volatile private var isPaused = false

    private var accessibilityButtonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var bannerView: LinearLayout? = null
    private val dismissBannerRunnable = Runnable { dismissBanner() }

    /**
     * Composite "package/class" token of the current foreground window.
     * Resets [clickCountThisWindow] and [clickedNodeKeys] on change.
     */
    private var currentWindowToken = ""
    @Volatile private var clickCountThisWindow = 0

    /**
     * Identity-hash-code set of nodes already clicked in the current window.
     * Prevents duplicate taps on the same UI element across rapid events.
     */
    private val clickedNodeKeys = mutableSetOf<Int>()

    // ====================================================================
    // AccessibilityService lifecycle callbacks
    // ====================================================================

    override fun onServiceConnected() {
        super.onServiceConnected()
        showStatusNotification()
        registerAccessibilityButton()
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
        unregisterAccessibilityButton()
        dismissBanner()
        hideStatusNotification()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        unregisterAccessibilityButton()
        hideStatusNotification()
        return super.onUnbind(intent)
    }

    // ====================================================================
    // Scan-and-click pipeline
    // ====================================================================

    private fun scanAndClick(event: AccessibilityEvent) {
        if (isPaused) return
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

    // ====================================================================
    // Node lookup — BFS candidate selection
    // ====================================================================

    /**
     * Performs a BFS traversal of the accessibility node tree starting at [root].
     *
     * Returns the single best candidate node using a strict priority order:
     *  P1 – text **and** view-ID both match a skip/next pattern (highest confidence).
     *  P2 – text matches only.
     *  P3 – view-ID matches only.
     *  P4 – close-icon heuristic (symbol/text/id hit + ad-context + popup-layout check).
     *
     * Blacklisted nodes are silently skipped so payment buttons are never returned.
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

    // ====================================================================
    // Matching predicates
    // ====================================================================

    private fun isTargetText(text: String): Boolean =
        text.isNotBlank() && TARGET_TEXTS.any { text.contains(it, ignoreCase = true) }

    private fun isTargetViewId(viewId: String): Boolean =
        viewId.isNotBlank() && TARGET_VIEW_IDS.any { viewId.contains(it, ignoreCase = true) }

    private fun isBlacklisted(text: String): Boolean =
        text.isNotBlank() && BLACKLIST_TEXTS.any { text.contains(it, ignoreCase = true) }

    private fun isCloseHint(text: String, viewId: String): Boolean {
        val compact = text.replace(" ", "")
        val symbolHit = isCloseSymbol(compact)
        return symbolHit || CLOSE_HINTS.any {
            compact.contains(it, ignoreCase = true) || viewId.contains(it, ignoreCase = true)
        }
    }

    private fun isCloseSymbol(text: String): Boolean = text in CLOSE_SYMBOLS

        private fun hasAdContext(node: AccessibilityNodeInfo, rootContextText: String): Boolean {
        if (containsAdContext(rootContextText)) return true

        val local = StringBuilder()
        var parent: AccessibilityNodeInfo? = node
        repeat(PARENT_WALK_DEPTH_FOR_CONTEXT) {
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
                    child.recycle()
                }
            }
        }
        
        // Add layout detection as part of context validation
        val layoutBased = isPopupBasedOnLayout(node)
        return containsAdContext(local.toString()) || layoutBased
    }

    private fun containsAdContext(text: String): Boolean =
        text.isNotBlank() && AD_CONTEXT_HINTS.any { text.contains(it, ignoreCase = true) }

    private fun collectRootContext(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            sb.append(' ')
            sb.append(mergeLabels(node))
            sb.append(' ')
            sb.append(node.viewIdResourceName.orEmpty())
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return sb.toString()
    }

    /**
     * Package-level gating.
     * Returns `false` for deny-listed packages and, when allowlist mode is active,
     * for packages not present in the user's allowlist.
     */
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

    /**
     * Heuristic: determines whether [node] is a popup close icon ("x" / "×" / "close").
     *
     * Checks:
     *  1. The node text/id matches a close symbol or close hint.
     *  2. It sits in the **top-right** quadrant of the root bounds.
     *  3. Either the node itself or its immediate parent is clickable.
     *  4. Layout-based popup validation passes ([isPopupBasedOnLayout]).
     */
    private fun isCloseIcon(
        node: AccessibilityNodeInfo,
        text: String,
        viewId: String,
        root: AccessibilityNodeInfo
    ): Boolean {
        val compact = text.replace(" ", "")
        val symbolHit = isCloseSymbol(compact)
        val textHit = compact == "close" || compact == "关闭"
        val idHit = viewId.contains("close", ignoreCase = true)
        if (!symbolHit && !textHit && !idHit) return false

        // Prefer nodes likely to be popup close buttons in top-right area.
        val nodeRect = Rect()
        val rootRect = Rect()
        node.getBoundsInScreen(nodeRect)
        root.getBoundsInScreen(rootRect)
        if (rootRect.width() <= 0 || rootRect.height() <= 0) return false

        val inTopArea = nodeRect.centerY() <= (rootRect.top + rootRect.height() * CLOSE_ICON_TOP_AREA_THRESHOLD)
        val inRightArea = nodeRect.centerX() >= (rootRect.left + rootRect.width() * CLOSE_ICON_RIGHT_AREA_THRESHOLD)
        val clickable = node.isClickable || node.parent?.isClickable == true
        
        // Enhanced validation: check layout characteristics
        val layoutValidation = isPopupBasedOnLayout(node)
        return inTopArea && inRightArea && clickable && layoutValidation
    }

    /**
     * Layout-based popup detection.
     *
     * Validates that the node's bounding rect has proportions consistent with a popup
     * dialog (10–80 % of screen width, 10–60 % of height) and is positioned near the
     * screen center or top. Also requires at least one ancestor with a dialog/popup/modal
     * class name (checked by [checkParentWithAlpha]).
     */
    private fun isPopupBasedOnLayout(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        
        // Get screen size
        val rootWindow = rootInActiveWindow
        val windowRect = Rect()
        rootWindow?.getBoundsInScreen(windowRect)
        val windowWidth = windowRect.width()
        val windowHeight = windowRect.height()
        
        if (windowWidth <= 0 || windowHeight <= 0) return false
        
        val widthRatio = rect.width().toDouble() / windowWidth
        val heightRatio = rect.height().toDouble() / windowHeight
        
        // Popups typically occupy 10-80% of screen width and 10-60% of screen height
        if (widthRatio < POPUP_MIN_WIDTH_RATIO || widthRatio > POPUP_MAX_WIDTH_RATIO) return false
        if (heightRatio < POPUP_MIN_HEIGHT_RATIO || heightRatio > POPUP_MAX_HEIGHT_RATIO) return false
        
        // Check if positioned near screen center or top
        val centerX = rect.centerX().toDouble()
        val centerY = rect.centerY().toDouble()
        
        val centerDistance = Math.sqrt(
            Math.pow(centerX - windowWidth / 2.0, 2.0) + 
            Math.pow(centerY - windowHeight / 2.0, 2.0)
        )
        
        // If near center or top, likely a popup
        val isNearCenter = centerDistance < windowWidth * POPUP_CENTER_DISTANCE_RATIO
        val isNearTop = rect.top < windowHeight * POPUP_TOP_AREA_THRESHOLD
        
        // Also check for shadow or border characteristics (via parent nodes)
        val hasParentWithAlpha = checkParentWithAlpha(node)
        
        return (isNearCenter || isNearTop) && hasParentWithAlpha
    }

    /** Walks up to [CLOSE_SYMBOL_MAX_WALK_DEPTH] ancestors looking for class names containing "dialog", "popup", "modal", or "overlay". */
    private fun checkParentWithAlpha(node: AccessibilityNodeInfo): Boolean {
        var parent: AccessibilityNodeInfo? = node.parent
        repeat(CLOSE_SYMBOL_MAX_WALK_DEPTH) {
            parent?.let { currentParent ->
                // Check if parent has popup-related properties
                val className = currentParent.className?.toString()?.lowercase() ?: ""
                if (className.contains("dialog") || className.contains("popup") || 
                    className.contains("modal") || className.contains("overlay")) {
                    return true
                }
                parent = currentParent.parent
            }
        }
        return false
    }

    /** Merges [AccessibilityNodeInfo.text] and [contentDescription] into a single string so keyword matching covers both fields. */
    private fun mergeLabels(node: AccessibilityNodeInfo): String =
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .joinToString(" ")

    // ====================================================================
    // Utilities
    // ====================================================================

    /**
     * Breadth-first traversal of the accessibility node tree.
     * Invokes [action] for every reachable node starting from [root].
     *
     * Note: child nodes obtained via `getChild()` are **not** recycled here;
     * callers that cache references should handle recycling themselves.
     */
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
     * Attempts to click [node].
     *
     * If the node is directly clickable, ACTION_CLICK is dispatched immediately.
     * Otherwise, the method walks up to [MAX_PARENT_WALK_DEPTH] ancestor levels
     * to locate the nearest clickable parent and clicks that instead.
     *
     * @return `true` if any node in the walk was successfully clicked.
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

    // ====================================================================
    // Accessibility button (system navigation-bar shortcut)
    // ====================================================================

    private fun registerAccessibilityButton() {
        val controller = accessibilityButtonController ?: return
        val callback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) {
                togglePause()
            }

            override fun onAvailabilityChanged(
                controller: AccessibilityButtonController,
                available: Boolean
            ) {
                Log.d(TAG, "Accessibility button available=$available")
            }
        }
        controller.registerAccessibilityButtonCallback(callback)
        accessibilityButtonCallback = callback
    }

    private fun unregisterAccessibilityButton() {
        val controller = accessibilityButtonController ?: return
        accessibilityButtonCallback?.let { controller.unregisterAccessibilityButtonCallback(it) }
        accessibilityButtonCallback = null
    }

    private fun togglePause() {
        isPaused = !isPaused
        updateAppIcon(paused = isPaused)
        updateStatusNotification()
        showBanner(
            if (isPaused) getString(R.string.toast_paused) else getString(R.string.toast_resumed),
            paused = isPaused
        )
        Log.i(TAG, if (isPaused) "Auto-skip PAUSED" else "Auto-skip RESUMED")
    }

    /**
     * Toggles the launcher icon between **active** and **paused** variants.
     *
     * Uses two `<activity-alias>` entries declared in the manifest
     * (`.MainActivityActive` / `.MainActivityPaused`). Only one alias is enabled
     * at a time; `DONT_KILL_APP` keeps the running process alive during the switch.
     */
    private fun updateAppIcon(paused: Boolean) {
        val pm = packageManager
        val pkg = packageName
        val enableAlias = if (paused) ".MainActivityPaused" else ".MainActivityActive"
        val disableAlias = if (paused) ".MainActivityActive" else ".MainActivityPaused"

        try {
            pm.setComponentEnabledSetting(
                ComponentName(pkg, "$pkg$disableAlias"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                ComponentName(pkg, "$pkg$enableAlias"),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to switch app icon", e)
        }
    }

    // ====================================================================
    // Top overlay banner — transient pause/resume feedback
    // ====================================================================

    private fun showBanner(message: String, paused: Boolean) {
        dismissBanner()

        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        val density = resources.displayMetrics.density

        val bgColor = if (paused) Color.parseColor("#E6FF9800") else Color.parseColor("#E64CAF50")
        val bg = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = 12 * density
        }

        val tv = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            gravity = Gravity.CENTER
            val hPad = (24 * density).toInt()
            val vPad = (12 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            addView(tv)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (48 * density).toInt()
        }

        try {
            wm.addView(container, params)
            bannerView = container
            mainHandler.removeCallbacks(dismissBannerRunnable)
            mainHandler.postDelayed(dismissBannerRunnable, 3000L)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show banner", e)
        }
    }

    private fun dismissBanner() {
        bannerView?.let { v ->
            try {
                val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
                wm?.removeView(v)
            } catch (_: Exception) { }
        }
        bannerView = null
        mainHandler.removeCallbacks(dismissBannerRunnable)
    }

    // ====================================================================
    // Status notification — persistent indicator in the notification shade
    // ====================================================================

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
            .setSmallIcon(R.drawable.ic_notif_active)
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

    private fun updateStatusNotification() {
        ensureStatusNotificationChannel()

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val titleRes = if (isPaused) R.string.notif_title_paused else R.string.notif_title_enabled
        val textRes = if (isPaused) R.string.notif_text_paused else R.string.notif_text_enabled
        val icon = if (isPaused) R.drawable.ic_notif_paused else R.drawable.ic_notif_active

        val notification = NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(getString(titleRes))
            .setContentText(getString(textRes))
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
