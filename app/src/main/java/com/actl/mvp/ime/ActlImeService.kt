package com.actl.mvp.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.inputmethodservice.InputMethodService

class ActlImeService : InputMethodService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingText: String? = null
    private var pendingTextRetry = 0
    private var pendingAction: String? = null
    private var pendingActionRetry = 0

    private val inputReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) {
                return
            }
            when (intent.action) {
                ACTION_INPUT_B64, ACTION_INPUT_B64_LEGACY -> {
                    val encoded = intent.getStringExtra(EXTRA_MSG).orEmpty()
                    if (encoded.isBlank()) {
                        return
                    }
                    val text = decodeUtf8Base64(encoded)
                    if (text.isBlank()) {
                        return
                    }
                    injectText(text)
                }

                ACTION_INPUT_TEXT -> {
                    val text = intent.getStringExtra(EXTRA_MSG).orEmpty()
                    if (text.isBlank()) {
                        return
                    }
                    injectText(text)
                }

                ACTION_EDITOR_ACTION -> {
                    val action = intent.getStringExtra(EXTRA_ACTION).orEmpty().lowercase()
                    if (action.isBlank()) {
                        return
                    }
                    injectEditorAction(action)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerInputReceiver()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(inputReceiver) }
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        flushPendingIfAny()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        flushPendingIfAny()
    }

    private fun registerInputReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_INPUT_B64)
            addAction(ACTION_INPUT_B64_LEGACY)
            addAction(ACTION_INPUT_TEXT)
            addAction(ACTION_EDITOR_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(inputReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(inputReceiver, filter)
        }
    }

    private fun injectText(text: String) {
        if (tryCommitText(text)) {
            pendingText = null
            pendingTextRetry = 0
            return
        }
        pendingText = text
        pendingTextRetry = MAX_PENDING_RETRY
        schedulePendingRetry()
    }

    private fun injectEditorAction(action: String) {
        if (tryPerformEditorAction(action)) {
            pendingAction = null
            pendingActionRetry = 0
            return
        }
        pendingAction = action
        pendingActionRetry = MAX_PENDING_RETRY
        schedulePendingRetry()
    }

    private fun schedulePendingRetry() {
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.postDelayed(retryRunnable, RETRY_INTERVAL_MS)
    }

    private val retryRunnable = object : Runnable {
        override fun run() {
            var keepRetry = false
            pendingText?.let { text ->
                if (tryCommitText(text)) {
                    pendingText = null
                    pendingTextRetry = 0
                } else {
                    pendingTextRetry -= 1
                    keepRetry = pendingTextRetry > 0
                }
            }
            pendingAction?.let { action ->
                if (tryPerformEditorAction(action)) {
                    pendingAction = null
                    pendingActionRetry = 0
                } else {
                    pendingActionRetry -= 1
                    keepRetry = keepRetry || pendingActionRetry > 0
                }
            }
            if (keepRetry) {
                mainHandler.postDelayed(this, RETRY_INTERVAL_MS)
            }
        }
    }

    private fun flushPendingIfAny() {
        pendingText?.let { text ->
            if (tryCommitText(text)) {
                pendingText = null
                pendingTextRetry = 0
            }
        }
        pendingAction?.let { action ->
            if (tryPerformEditorAction(action)) {
                pendingAction = null
                pendingActionRetry = 0
            }
        }
    }

    private fun tryCommitText(text: String): Boolean {
        val ic: InputConnection = currentInputConnection ?: return false
        return runCatching { ic.commitText(text, 1) }.getOrDefault(false)
    }

    private fun tryPerformEditorAction(action: String): Boolean {
        val ic: InputConnection = currentInputConnection ?: return false
        return when (action) {
            ACTION_ENTER -> sendEnter(ic)
            ACTION_AUTO -> {
                performAutoEditorAction(ic)
            }

            else -> {
                val resolved = resolveNamedEditorAction(action) ?: return false
                if (runCatching { ic.performEditorAction(resolved) }.getOrDefault(false)) {
                    true
                } else {
                    // Some apps expose only custom actionId instead of standard IME_ACTION_*.
                    performCustomEditorAction(ic)
                }
            }
        }
    }

    private fun resolveAutoEditorAction(): Int? {
        val info = currentInputEditorInfo ?: return null
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        if (action == EditorInfo.IME_ACTION_UNSPECIFIED || action == EditorInfo.IME_ACTION_NONE) {
            return null
        }
        return action
    }

    private fun performAutoEditorAction(ic: InputConnection): Boolean {
        // 1) Prefer app-provided custom actionId.
        if (performCustomEditorAction(ic)) {
            return true
        }
        // 2) Then try standard action extracted from imeOptions.
        val auto = resolveAutoEditorAction()
        if (auto != null && auto != EditorInfo.IME_ACTION_NONE) {
            if (runCatching { ic.performEditorAction(auto) }.getOrDefault(false)) {
                return true
            }
        }
        // 3) Last fallback: attempt common "submit-like" actions; do not send ENTER here.
        val fallbacks = intArrayOf(
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_NEXT
        )
        for (candidate in fallbacks) {
            if (runCatching { ic.performEditorAction(candidate) }.getOrDefault(false)) {
                return true
            }
        }
        return false
    }

    private fun performCustomEditorAction(ic: InputConnection): Boolean {
        val info = currentInputEditorInfo ?: return false
        val actionId = info.actionId
        if (actionId <= 0) {
            return false
        }
        return runCatching { ic.performEditorAction(actionId) }.getOrDefault(false)
    }

    private fun resolveNamedEditorAction(action: String): Int? {
        return when (action) {
            ACTION_SEARCH -> EditorInfo.IME_ACTION_SEARCH
            ACTION_SEND -> EditorInfo.IME_ACTION_SEND
            ACTION_DONE -> EditorInfo.IME_ACTION_DONE
            ACTION_GO -> EditorInfo.IME_ACTION_GO
            ACTION_NEXT -> EditorInfo.IME_ACTION_NEXT
            else -> null
        }
    }

    private fun sendEnter(ic: InputConnection): Boolean {
        val down = runCatching { ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)) }
            .getOrDefault(false)
        val up = runCatching { ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)) }
            .getOrDefault(false)
        return down || up
    }

    private fun decodeUtf8Base64(value: String): String {
        return runCatching {
            val raw = Base64.decode(value, Base64.DEFAULT)
            String(raw, Charsets.UTF_8)
        }.getOrDefault("")
    }

    private companion object {
        private const val ACTION_INPUT_B64 = "com.actl.mvp.action.ADB_INPUT_B64"
        private const val ACTION_INPUT_B64_LEGACY = "ADB_INPUT_B64"
        private const val ACTION_INPUT_TEXT = "com.actl.mvp.action.ADB_INPUT_TEXT"
        private const val ACTION_EDITOR_ACTION = "com.actl.mvp.action.ADB_EDITOR_ACTION"
        private const val EXTRA_MSG = "msg"
        private const val EXTRA_ACTION = "action"
        private const val MAX_PENDING_RETRY = 8
        private const val RETRY_INTERVAL_MS = 80L

        private const val ACTION_AUTO = "auto"
        private const val ACTION_SEARCH = "search"
        private const val ACTION_SEND = "send"
        private const val ACTION_DONE = "done"
        private const val ACTION_GO = "go"
        private const val ACTION_NEXT = "next"
        private const val ACTION_ENTER = "enter"
    }
}
