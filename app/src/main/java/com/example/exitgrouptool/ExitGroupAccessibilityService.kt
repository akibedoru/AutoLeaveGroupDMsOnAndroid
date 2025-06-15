package com.example.exitgrouptool

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import android.os.SystemClock

class ExitGroupAccessibilityService : AccessibilityService() {

    private var step = 0
    private var lastStepTime = 0L
    private var groupDetectedTime: Long = 0L
    private val stepDelay = 500L // 各ステップの間にn秒空ける
    private val groupClickDelay = 500L // グループ検出後の遅延（ミリ秒）
    private val handler = Handler(Looper.getMainLooper())
    private val interval = 500L // 0.5秒ごとにチェック

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
            handler.postDelayed(this, interval)
        }
    }

    private fun checkAndProcessSteps() {
        val root = rootInActiveWindow ?: return

        // Discord以外の画面なら何もしない
        if (root.packageName != "com.discord") {
            return
        }

        val now = SystemClock.uptimeMillis()

        when (step) {
            0 -> {
                val groupNode = findMatchingGroupNode(root, getTargetGroupNames())
                if (groupNode != null) {
                    if (groupDetectedTime == 0L) {
                        groupDetectedTime = now
                        Log.d("ExitService", "Step 0: グループを検出、待機開始")
                        return
                    }

                    if (now - groupDetectedTime < groupClickDelay) {
                        Log.d("ExitService", "Step 0: 待機中… ${now - groupDetectedTime}ms 経過")
                        return
                    }

                    if (performClickUpwards(groupNode)) {
                        Log.d("ExitService", "Step 0: グループをクリック")
                        step = 1
                        lastStepTime = now
                        groupDetectedTime = 0L
                    }
                } else {
                    groupDetectedTime = 0L
                }
            }

            1 -> {
                if (now - lastStepTime < stepDelay) return

                val groupNameNode = findGroupNameNode(root)
                if (groupNameNode != null && performClickUpwards(groupNameNode)) {
                    Log.d("ExitService", "Step 1: グループ名クリック成功")
                }

                if (isDetailScreenVisible(root)) {
                    step = 2
                    lastStepTime = now
                    Log.d("ExitService", "Step 1: 詳細画面へ → step=2")
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
                    if (desc.contains("その他") || desc.contains("メニュー") || desc.contains("more")) {
                        Log.d("ExitService", "Step 2: descにマッチするノードを発見 → クリック")
                        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            step = 3
                            lastStepTime = now
                            return
                        }
                    }

                    if (node.className == "android.widget.ImageView") {
                        val score = (rect.right * 2) - rect.top // 右上ほど高スコア
                        if (score > maxScore) {
                            maxScore = score
                            targetNode = node
                        }
                    }
                }

                if (targetNode != null && targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.d("ExitService", "Step 2: 三点リーダー候補（右上ImageView）をクリック")
                    step = 3
                    lastStepTime = now
                } else {
                    Log.d("ExitService", "Step 2: 三点リーダーが見つかりませんでした")
                    // stepは進めず、次回イベント時に再評価
                }
            }

            3 -> {
                if (now - lastStepTime < stepDelay) return
                // 「グループから脱退する」をクリック
                val leaveNodes = root.findAccessibilityNodeInfosByText("グループから脱退する")

                if (!leaveNodes.isNullOrEmpty()) {
                    val node = leaveNodes[0]
                    Log.d("ExitService", "脱退ボタン見つけた: text=${node.text}, clickable=${node.isClickable}, enabled=${node.isEnabled}")

                    // 親をたどってクリック可能なノードを探す（今まで通り）
                    if (performClickUpwards(node)) {
                        Log.d("ExitService", "Step 3: 脱退ボタンをクリック")
                        step = 4
                        lastStepTime = SystemClock.uptimeMillis()
                    } else {
                        Log.d("ExitService", "Step 3: 脱退ボタンは見つかったがクリックできませんでした")
                    }
                } else {
                    Log.d("ExitService", "脱退ボタンが見つかりません")
                }
            }

            4 -> {
                if (now - lastStepTime < stepDelay) return
                // 確認ダイアログの「はい」をクリック
                val yesNodes = root.findAccessibilityNodeInfosByText("はい")
                if (!yesNodes.isNullOrEmpty() && yesNodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.d("ExitService", "Step 4: 確認の「はい」をクリック")
                    step = 5
                    lastStepTime = now
                } else {
                    Log.d("ExitService", "確認の「はい」が見つかりません")
                }
            }

            5 -> {
                Log.d("ExitService", "完了！必要なら次のグループへ移動処理を書く")
                // ここで次のグループへ進むロジックを入れる場合は step=0 に戻すなど
                step = 0
            }
        }
    }

    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    // 対象テキストを含むノードを再帰的に検索
    private fun findNodeByText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString()?.contains(text) == true) return node
        for (i in 0 until node.childCount) {
            val child = findNodeByText(node.getChild(i), text)
            if (child != null) return child
        }
        return null
    }

    // 指定ノードから上にたどってクリック可能なノードをクリック
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

    // 上部のグループ名を探す（クラス名やヒントなどでフィルタしてもよい）
    private fun findGroupNameNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findNodeByText(root, "discord.gg") // 例: 名前の中にURLが含まれる場合
            ?: findNodeByText(root, "グループ名") // 必要に応じて他のテキストで探索
    }

    // 詳細画面に遷移したかどうかを判定（「メンバー」「ピン留め」などの存在で判断）
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
        Log.d("ExitService", "ノードテキスト: $nodeText") // 追加ログ

        for (keyword in keywords) {
            if (nodeText.contains(keyword.lowercase())) {
                Log.d("ExitService", "グループ名マッチ: $nodeText ← キーワード: $keyword")
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val found = findMatchingGroupNodeRecursive(node.getChild(i), keywords)
            if (found != null) return found
        }

        return null
    }

    // アプリの設定からキーワードを読み込む
    private fun getTargetGroupNames(): List<String> {
        val prefs = getSharedPreferences("ExitPrefs", MODE_PRIVATE)
        val raw = prefs.getString("keywords", "") ?: ""
        val result = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        Log.d("ExitService", "読み込んだキーワード一覧: $result") // ログ追加

        return result
    }
}
