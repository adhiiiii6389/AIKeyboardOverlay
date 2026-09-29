package com.example.aikeyboardoverlay.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.aikeyboardoverlay.common.AppBroadcasts
import com.example.aikeyboardoverlay.llm.LlamaEngine
import com.example.aikeyboardoverlay.llm.PromptTemplates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class TextEditorAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TextEditorA11yService"
        private const val MODEL_FILE_NAME = "qwen-0.6b.gguf"
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val llamaEngine = LlamaEngine()
    @Volatile private var isModelReady = false
    @Volatile private var isBusy = false

    private val floatingButtonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AppBroadcasts.ACTION_FLOATING_BUTTON_CLICKED -> handleFloatingButtonClick()
                AppBroadcasts.ACTION_MODEL_STATE_QUERY -> broadcastModelState()
                AppBroadcasts.ACTION_MODEL_TEST_REQUEST -> {
                    handleModelTestRequest(intent.getStringExtra(AppBroadcasts.EXTRA_PROMPT).orEmpty())
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")

        LocalBroadcastManager.getInstance(this).registerReceiver(
            floatingButtonReceiver,
            IntentFilter().apply {
                addAction(AppBroadcasts.ACTION_FLOATING_BUTTON_CLICKED)
                addAction(AppBroadcasts.ACTION_MODEL_STATE_QUERY)
                addAction(AppBroadcasts.ACTION_MODEL_TEST_REQUEST)
            }
        )

        broadcastModelState()
        loadModelInBackground()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(floatingButtonReceiver)
        serviceScope.cancel()
        llamaEngine.release()
        isModelReady = false
        broadcastModelState()
        return super.onUnbind(intent)
    }

    private fun handleModelTestRequest(input: String) {
        if (input.isEmpty() || !isModelReady || isBusy) {
            broadcastModelTestResult(if (!isModelReady) "Model is not active" else "Enter text or wait for the current request")
            return
        }

        isBusy = true
        serviceScope.launch {
            try {
                broadcastModelTestResult(processTextWithLLM(input))
            } catch (e: Exception) {
                Log.e(TAG, "Model test failed", e)
                broadcastModelTestResult("Model processing failed: ${e.message.orEmpty()}")
            } finally {
                isBusy = false
            }
        }
    }

    private fun loadModelInBackground() {
        serviceScope.launch {
            val modelFile = File(filesDir, MODEL_FILE_NAME)
            isModelReady = llamaEngine.loadModel(modelFile, nThreads = 1, nCtx = 1024)
            Log.d(TAG, "Model ready: $isModelReady (path=${modelFile.absolutePath})")
            broadcastModelState()
        }
    }

    private fun handleFloatingButtonClick() {
        if (isBusy) {
            Log.d(TAG, "Ignoring click — already processing a request")
            return
        }

        if (!isModelReady) {
            Log.w(TAG, "Model not loaded yet")
            broadcastState(AppBroadcasts.STATE_ERROR)
            return
        }

        val focusedNode = findFocusedEditableNode()
        if (focusedNode == null) {
            Log.w(TAG, "No focused editable node found")
            broadcastState(AppBroadcasts.STATE_ERROR)
            return
        }

        val originalText = extractText(focusedNode)
        if (originalText.isNullOrEmpty()) {
            Log.w(TAG, "Focused node has no text to process")
            recycleNode(focusedNode)
            broadcastState(AppBroadcasts.STATE_ERROR)
            return
        }

        isBusy = true
        broadcastState(AppBroadcasts.STATE_PROCESSING)

        serviceScope.launch {
            try {
                val processedText = processTextWithLLM(originalText)
                val success = injectText(focusedNode, processedText)
                Log.d(TAG, "Injection ${if (success) "succeeded" else "failed"}")
                broadcastState(if (success) AppBroadcasts.STATE_IDLE else AppBroadcasts.STATE_ERROR)
            } catch (e: Exception) {
                Log.e(TAG, "Processing failed", e)
                broadcastState(AppBroadcasts.STATE_ERROR)
            } finally {
                recycleNode(focusedNode)
                isBusy = false
            }
        }
    }

    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        root.recycleCompat()

        if (focusedNode == null || !focusedNode.isEditable) {
            focusedNode?.let { recycleNode(it) }
            return null
        }
        return focusedNode
    }

    private fun extractText(node: AccessibilityNodeInfo): String? = node.text?.toString()

    private suspend fun processTextWithLLM(input: String): String {
        val prompt = PromptTemplates.buildGrammarCorrectionPrompt(input)
        val maxTokens = (input.length / 3).coerceIn(32, 256)
        val raw = llamaEngine.generateResponse(prompt, maxTokens = maxTokens)
        return raw.trim().removeSurrounding("\"")
    }

    private fun injectText(node: AccessibilityNodeInfo, newText: String): Boolean {
        if (!node.refresh() || !node.isEditable) {
            Log.w(TAG, "Node no longer valid for injection")
            return false
        }
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun broadcastState(state: Int) {
        val intent = Intent(AppBroadcasts.ACTION_PROCESSING_STATE_CHANGED).apply {
            putExtra(AppBroadcasts.EXTRA_STATE, state)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastModelState() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(AppBroadcasts.ACTION_MODEL_STATE_CHANGED)
                .putExtra(AppBroadcasts.EXTRA_MODEL_READY, isModelReady)
        )
    }

    private fun broadcastModelTestResult(output: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(AppBroadcasts.ACTION_MODEL_TEST_RESULT)
                .putExtra(AppBroadcasts.EXTRA_OUTPUT, output)
        )
    }

    @Suppress("DEPRECATION")
    private fun recycleNode(node: AccessibilityNodeInfo?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node?.recycle()
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleCompat() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) this.recycle()
    }
}
