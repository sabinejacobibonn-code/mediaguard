package com.mediaguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MediaGuardAccessibilityService : AccessibilityService() {

    companion object {
        val TARGET_PACKAGES = setOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",  // TikTok (bestimmte Regionen)
            "com.twitter.android",
            "com.instagram.android",
            "org.telegram.messenger",
            "com.facebook.katana",
            "com.reddit.frontpage",
            "com.google.android.youtube"
        )
        var instance: MediaGuardAccessibilityService? = null
    }

    private var currentPackage = ""
    private var lastSentText = ""
    private val textBuffer = StringBuilder()
    private var lastFlushTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 1000
            packageNames = TARGET_PACKAGES.toTypedArray()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in TARGET_PACKAGES) return
        currentPackage = pkg
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> extractAndSendText()
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> textBuffer.clear()
        }
    }

    private fun extractAndSendText() {
        val now = System.currentTimeMillis()
        if (now - lastFlushTime < 2000) return
        lastFlushTime = now

        val rootNode = try {
            rootInActiveWindow
        } catch (_: Exception) { null } ?: return

        textBuffer.clear()
        try {
            extractTextFromNode(rootNode, textBuffer, depth = 0)
        } catch (_: Exception) {
            // Nie crashen wegen Accessibility-Fehler
        } finally {
            // BUG 7 FIX: rootNode immer recyclen
            try { rootNode.recycle() } catch (_: Exception) {}
        }

        val text = textBuffer.toString().trim()
        if (text.length > 20 && text != lastSentText) {
            lastSentText = text
            sendTextToOverlay(text)
        }
    }

    // BUG 7 FIX: null-check für jeden child, try/catch pro Knoten
    // BUG P5 FIX: textBuffer-Größe beim Aufbau begrenzen (max 4000 Zeichen)
    private fun extractTextFromNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 8 || sb.length > 4000) return
        try {
            node.text?.let { t -> if (t.length > 3) sb.append(t).append(" ") }
            node.contentDescription?.let { cd -> if (cd.length > 3) sb.append(cd).append(" ") }

            for (i in 0 until node.childCount) {
                if (sb.length > 4000) break
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null) {
                    extractTextFromNode(child, sb, depth + 1)
                    try { child.recycle() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    // BUG 10 FIX: auf Android 12+ (API 31) darf startService() nicht aus dem Hintergrund
    // aufgerufen werden → startForegroundService() verwenden wenn nötig, mit Fallback
    private fun sendTextToOverlay(text: String) {
        try {
            val intent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE_TEXT
                putExtra(OverlayService.EXTRA_TEXT, text.take(2000))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {
            // Service läuft nicht – still ignorieren, kein Absturz
        }
    }

    override fun onInterrupt() { instance = null }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
