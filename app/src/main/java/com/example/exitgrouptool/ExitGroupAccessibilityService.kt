package com.example.exitgrouptool

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect

class ExitGroupAccessibilityService : AccessibilityService() {

    private var step = 0
    private var lastStepTime = 0L
    private var groupDetectedTime: Long = 0L
    private val stepDelay = 500L
    private val groupClickDelay = 500L
    private val handler = Handler(Looper.getMainLooper())

    private val random = java.util.Random()

    private fun randomDelay(base: Long, variance: Long): Long {
        return base + random.nextInt(variance.toInt())
    }

    private var interval = randomDelay(500L, 800L)

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.post(checkRunnable)
    }

    private val checkRunnable = object : Runnable {
        override fun run() {
            try {
                checkAndProcessSteps()
            } catch (e: Exception) {
                Log.e("ExitService", "Runnable error: ${e.message}")
            }
            interval = randomDelay(3000L, 2000L)
            handler.postDelayed(this, interval)
        }
    }

    private fun checkAndProcessSteps() {
        val root = rootInActiveWindow ?: return
        if (root.packageName != "com.discord") return

        val now = SystemClock.uptimeMillis()

        when (step) {
            0 -> {
                val groupNode = findMatchingGroupNode(root, getTargetGroupNames())
                if (groupNode != null) {
                    if (groupDetectedTime == 0L) {
                        groupDetectedTime = now
                        recycleSafely(groupNode)
                        return
                    }

                    if (now - groupDetectedTime < groupClickDelay) {
                        recycleSafely(groupNode)
                        return
                    }

                    if (performClickUpwards(groupNode)) {
                        step = 1
                        lastStepTime = now
                        groupDetectedTime = 0L
                    }
                    recycleSafely(groupNode)
                } else {
                    groupDetectedTime = 0L
                }
            }

            1 -> {
                if (now - lastStepTime < stepDelay) return
                val groupNameNode = findGroupNameNode(root)
                if (groupNameNode != null && performClickUpwards(groupNameNode)) {
                    recycleSafely(groupNameNode)
                    return
                }
                recycleSafely(groupNameNode)

                if (isDetailScreenVisible(root)) {
                    step = 2
                    lastStepTime = now
                }
            }

            2 -> {
                if (now - lastStepTime < stepDelay) return

                val clickableCandidates = mutableListOf<AccessibilityNodeInfo>()
                collectClickableNodes(root, clickableCandidates)

                var targetNode: AccessibilityNodeInfo? = null
                var maxScore = Int.MIN_VALUE

                for (node in clickableCandidates) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)

                    val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                    if (desc.contains("その他")) {
                        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            step = 3
                            lastStepTime = now
                            recycleNodesSafely(clickableCandidates)
                            return
                        }
                    }

                    if (node.className == "android.widget.ImageView") {
                        val score = (rect.right * 2) - rect.top
                        if (score > maxScore) {
                            maxScore = score
                            targetNode = node
                        }
                    }
                }

                if (targetNode != null && targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    step = 3
                    lastStepTime = now
                } else {
                    Log.d("ExitService", "Step 2: 三点リーダーが見つかりませんでした")
                }

                recycleNodesSafely(clickableCandidates)
            }

            3 -> {
                if (now - lastStepTime < stepDelay) return
                val leaveNodes = root.findAccessibilityNodeInfosByText("グループから脱退する")

                if (!leaveNodes.isNullOrEmpty()) {
                    val node = leaveNodes[0]
                    if (performClickUpwards(node)) {
                        step = 4
                        lastStepTime = now
                    } else {
                        Log.d("ExitService", "Step 3: 脱退ボタンは見つかったがクリックできませんでした")
                    }
                } else {
                    Log.d("ExitService", "脱退ボタンが見つかりません")
                }
                recycleNodesSafely(leaveNodes)
            }

            4 -> {
                if (now - lastStepTime < stepDelay) return
                val yesNodes = root.findAccessibilityNodeInfosByText("はい")
                if (!yesNodes.isNullOrEmpty() && yesNodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    step = 5
                    lastStepTime = now
                } else {
                    Log.d("ExitService", "確認の「はい」が見つかりません")
                }
                recycleNodesSafely(yesNodes)
            }

            5 -> {
                step = 0
            }
        }
    }

    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    private fun findNodeByText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString()?.contains(text) == true) return node
        for (i in 0 until node.childCount) {
            val child = findNodeByText(node.getChild(i), text)
            if (child != null) return child
        }
        return null
    }

    private fun performClickUpwards(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }

    private fun findGroupNameNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findNodeByText(root, "discord.gg")
            ?: findNodeByText(root, "グループ名")
    }

    private fun isDetailScreenVisible(root: AccessibilityNodeInfo): Boolean {
        val indicators = listOf("メンバー", "ピン留め", "リンク", "メディア")
        return indicators.any { keyword ->
            findNodeByText(root, keyword) != null
        }
    }

    private fun collectClickableNodes(node: AccessibilityNodeInfo?, result: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isClickable) result.add(node)
        for (i in 0 until node.childCount) {
            collectClickableNodes(node.getChild(i), result)
        }
    }

    private fun findMatchingGroupNode(root: AccessibilityNodeInfo, keywords: List<String>): AccessibilityNodeInfo? {
        return findMatchingGroupNodeRecursive(root, keywords)
    }

    private fun findMatchingGroupNodeRecursive(node: AccessibilityNodeInfo?, keywords: List<String>): AccessibilityNodeInfo? {
        if (node == null) return null

        val nodeText = node.text?.toString()?.lowercase() ?: ""
        for (keyword in keywords) {
            if (nodeText.contains(keyword.lowercase())) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val found = findMatchingGroupNodeRecursive(node.getChild(i), keywords)
            if (found != null) return found
        }

        return null
    }

    private fun getTargetGroupNames(): List<String> {
        val prefs = getSharedPreferences("ExitPrefs", MODE_PRIVATE)
        val raw = prefs.getString("keywords", "") ?: ""
        val result = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        Log.d("ExitService", "読み込んだキーワード一覧: $result")
        return result
    }

    private fun recycleSafely(node: AccessibilityNodeInfo?) {
        if (node == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                node.recycle()
            } catch (e: Exception) {
                Log.w("ExitService", "recycle 失敗: ${e.message}")
            }
        }
    }

    private fun recycleNodesSafely(nodes: List<AccessibilityNodeInfo>?) {
        if (nodes == null) return
        for (node in nodes) {
            recycleSafely(node)
        }
    }
}
