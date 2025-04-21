package ramble.sokol.touchvisionmtsapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.TargetApi
import android.app.AppOpsManager
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.MediaPlayer
import android.os.SystemClock
import android.os.Vibrator
import android.os.VibrationEffect
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.GestureDetectorCompat
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ramble.sokol.touchvisionmtsapp.R
import java.io.File
import java.io.FileOutputStream
import java.util.LinkedList
import java.util.Locale
import kotlin.math.abs

class YourAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TouchVisionService"
        private var instance: YourAccessibilityService? = null
        private const val SWIPE_THRESHOLD = 100
        private const val DOUBLE_TAP_TIMEOUT = 300
        private const val VIBRATION_DURATION = 50
        private const val DOUBLE_TAP_SLOP = 20
        private const val HOME_SWIPE_THRESHOLD = 300
        private const val HOME_SWIPE_MAX_X_DEVIATION = 100
        private const val SCREEN_THIRD = 1f / 3f
        private const val RECENT_SWIPE_THRESHOLD = 300
        private const val RECENT_SWIPE_MAX_X_DEVIATION = 100
        fun getInstance(): YourAccessibilityService? = instance
        private var textToSpeech: TextToSpeech? = null
        private var isTtsInitialized = false
        private var lastSpokenText: String? = null
    }

    private var windowManager: WindowManager? = null
    private var highlightView: View? = null
    private var gestureOverlayView: View? = null
    private var currentNode: AccessibilityNodeInfo? = null
    private var nodeList = LinkedList<AccessibilityNodeInfo>()
    private var currentIndex = 0
    private var isServiceActive = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastTapTime: Long = 0
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var vibrator: Vibrator? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private lateinit var gestureDetector: GestureDetectorCompat
    private var isHomeGestureInProgress = false
    private var isRecentGestureInProgress = false
    private var ttsGenerator: PyObject? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentAudioFile: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        //initTTS()
        initTextToSpeech()
        instance = this
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        val display = windowManager?.defaultDisplay
        val size = android.graphics.Point()
        display?.getSize(size)
        screenWidth = size.x
        screenHeight = size.y

        gestureDetector = GestureDetectorCompat(this, GestureListener())

        serviceInfo.apply {
            flags = flags or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        }
        setServiceInfo(serviceInfo)

        setupGestureOverlay()
        setupHighlightView()
        isServiceActive = true
        Log.d(TAG, "Service connected and active")
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (isHomeGestureInProgress || isRecentGestureInProgress) {
                return true
            }
            return false
        }
    }

    private fun initTTS() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val modelFile = File(filesDir, "silero_model.pt")
                if (!modelFile.exists()) {
                    assets.open("silero_model.pt").use { input ->
                        FileOutputStream(modelFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                val cacheDir = File(filesDir, "tts_cache").apply { mkdirs() }

                val python = Python.getInstance()
                val module = python.getModule("tts_service")
                ttsGenerator = module.callAttr(
                    "SpeechGenerator",
                    cacheDir.absolutePath,
                    "cpu"
                )
                Log.d(TAG, "TTS initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "TTS initialization failed", e)
            }
        }
    }



    private fun setupGestureOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        gestureOverlayView = View(this).apply {
            setBackgroundColor(0x00000000) // Полностью прозрачный
        }

        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.START or Gravity.TOP
        }

        windowManager?.addView(gestureOverlayView, params)

        gestureOverlayView?.setOnTouchListener { _, event ->
            if (gestureDetector.onTouchEvent(event)) {
                when (event.action) {
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isHomeGestureInProgress = false
                    }
                }
                true
            } else {
                handleGesture(event)
            }
        }
    }

    private fun initTextToSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "TTS Language not supported")
                } else {
                    isTtsInitialized = true
                    Log.d(TAG, "TTS initialized successfully")

                    speakCurrentNode()
                }
            } else {
                Log.e(TAG, "TTS initialization failed")
            }
        }
    }

    private fun speakNodeInfo(node: AccessibilityNodeInfo) {
        if (!isTtsInitialized) return

        val text = when {
            node.text != null && node.text.isNotEmpty() -> node.text.toString()
            node.contentDescription != null && node.contentDescription.isNotEmpty() -> node.contentDescription.toString()
            else -> null
        }

        text?.let {
            if (it != lastSpokenText) {
                stopSpeaking()
                textToSpeech?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "node_tts")
                lastSpokenText = it
                Log.d(TAG, "Speaking: $it")
            }
        }
    }

    private fun speakCurrentNode() {
        currentNode?.let { speakNodeInfo(it) }
    }

    private fun stopSpeaking() {
        textToSpeech?.stop()
    }

    private fun setupHighlightView() {
        highlightView = LayoutInflater.from(this).inflate(R.layout.highlight_view, null).apply {
            bringToFront()
        }

        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.START or Gravity.TOP
        }

        windowManager?.addView(highlightView, params)
        highlightView?.visibility = View.INVISIBLE
        Log.d(TAG, "Highlight view setup completed")
    }

    private fun highlightNode(node: AccessibilityNodeInfo) {
        val bounds = getAdjustedBounds(node)
        Log.d(TAG, "Highlighting node at: $bounds")

        highlightView?.apply {
            layoutParams = (layoutParams as WindowManager.LayoutParams).apply {
                x = bounds.left
                y = bounds.top
                width = bounds.width()
                height = bounds.height()
            }
            visibility = View.VISIBLE
            invalidate()
        }

        windowManager?.updateViewLayout(highlightView, highlightView?.layoutParams)
        currentNode = node
        logNodeInfo(node)
        speakNodeInfo(node)
    }

    private fun getAdjustedBounds(node: AccessibilityNodeInfo): Rect {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        Log.d(TAG, "Raw bounds: $bounds")
        return bounds
    }

    private fun logNodeInfo(node: AccessibilityNodeInfo) {
        val bounds = getAdjustedBounds(node)
        Log.d(TAG, """
            ===== Element Info =====
            Position: ${currentIndex + 1}/${nodeList.size}
            Class: ${node.className}
            Text: ${node.text}
            Description: ${node.contentDescription}
            Package: ${node.packageName}
            Screen Position: [${bounds.left}, ${bounds.top}]-[${bounds.right},${bounds.bottom}]
            Size: ${bounds.width()}x${bounds.height()}
            Interactive: Clickable=${node.isClickable}, Focusable=${node.isFocusable}, Scrollable=${node.isScrollable}
            ========================
        """.trimIndent())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isServiceActive) return

        Log.d(TAG, "AccessibilityEvent: ${event.eventTypeToString()} from ${event.packageName}")

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                Log.d(TAG, "Window content changed, updating node list")
                updateNodeList()
            }
        }
    }

    private fun updateNodeList() {
        Log.d(TAG, "Starting node list update")
        val previousIndex = currentIndex
        val previousNode = currentNode?.let { AccessibilityNodeInfo.obtain(it) }

        nodeList.clear()
        val rootNode = rootInActiveWindow ?: run {
            Log.w(TAG, "Cannot get root node")
            return
        }

        collectInteractiveNodes(rootNode)
        Log.d(TAG, "Collected ${nodeList.size} nodes")

        nodeList.sortWith(compareBy(
            { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                rect.top
            },
            { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                rect.left
            }
        ))

        if (nodeList.isNotEmpty()) {
            val newIndex = nodeList.indexOfFirst { node ->
                previousNode?.let { it.equals(node) } ?: false
            }

            currentIndex = if (newIndex != -1) newIndex else {
                if (previousIndex < nodeList.size) previousIndex else 0
            }

            highlightNode(nodeList[currentIndex])
            Log.d(TAG, "Highlighted node (index $currentIndex)")

            previousNode?.recycle()
        } else {
            highlightView?.visibility = View.INVISIBLE
            Log.d(TAG, "No nodes to highlight")
        }
    }

    private fun collectInteractiveNodes(node: AccessibilityNodeInfo) {
        if (!node.isVisibleToUser) {
            node.recycle()
            return
        }

        val isContainer = when (node.className?.toString()) {
            "android.view.ViewGroup",
            "android.widget.LinearLayout",
            "android.widget.FrameLayout",
            "android.widget.RelativeLayout",
            "android.widget.ScrollView",
            "androidx.recyclerview.widget.RecyclerView",
            "android.widget.ListView",
            "android.widget.GridView" -> true
            else -> false
        }

        val hasContent = node.text?.isNotEmpty() == true ||
                node.contentDescription?.isNotEmpty() == true ||
                node.isCheckable ||
                node.isEditable

        val isInteractive = node.isClickable ||
                node.isFocusable ||
                node.isScrollable ||
                node.isLongClickable ||
                node.isCheckable ||
                node.isEditable

        val isVisualElement = node.className?.toString()?.let { className ->
            className.contains("ImageView") ||
                    className.contains("ImageButton") ||
                    className.contains("Button") ||
                    className.contains("CheckBox") ||
                    className.contains("Switch") ||
                    className.contains("RadioButton") ||
                    className.contains("EditText") ||
                    className.contains("TextView") ||
                    className.contains("WebView")
        } ?: false


//        val shouldInclude = (!isContainer || (isContainer && isInteractive)) &&
//                (hasContent || isInteractive || isVisualElement)

        val shouldInclude = (!isContainer) &&
                (hasContent || isInteractive || isVisualElement)

        if (shouldInclude) {
            Log.v(TAG, "Adding node: ${node.className} " +
                    "(text=${node.text}, desc=${node.contentDescription}, " +
                    "clickable=${node.isClickable}, focusable=${node.isFocusable})")
            nodeList.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectInteractiveNodes(child)
            }
        }
        node.recycle()
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        textToSpeech?.let {
            it.stop()
            it.shutdown()
        }
        windowManager?.removeView(highlightView)
        windowManager?.removeView(gestureOverlayView)
        isServiceActive = false
        instance = null
        Log.d(TAG, "Service destroyed")
        stopSpeaking()
        try {
            val cacheDir = File("${filesDir.absolutePath}/tts_cache")
            if (cacheDir.exists()) {
                cacheDir.listFiles()?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear TTS cache", e)
        }
        super.onDestroy()
    }

    fun handleGesture(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.rawX
                lastY = event.rawY
                Log.v(TAG, "Action down at ($lastX, $lastY)")

                // Сбрасываем флаги жестов
                isHomeGestureInProgress = false
                isRecentGestureInProgress = false

                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTapTime < DOUBLE_TAP_TIMEOUT &&
                    abs(event.rawX - lastTapX) < DOUBLE_TAP_SLOP &&
                    abs(event.rawY - lastTapY) < DOUBLE_TAP_SLOP) {

                    Log.d(TAG, "Double tap detected at (${event.rawX}, ${event.rawY})")
                    performClick(true)
                    lastTapTime = 0
                    return true
                }

                lastTapTime = currentTime
                lastTapX = event.rawX
                lastTapY = event.rawY
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - lastY
                val dx = event.rawX - lastX

                // Если жест уже начат - продолжаем его обработку
                if (isHomeGestureInProgress || isRecentGestureInProgress) {
                    return true
                }

                // Определяем направление жеста
                val isVertical = abs(dy) > abs(dx)

                if (isVertical) {
                    // Вертикальные жесты
                    if (dy > 0) {
                        // Свайп вниз
                        if (abs(dy) > RECENT_SWIPE_THRESHOLD / 2 &&
                            abs(dx) < RECENT_SWIPE_MAX_X_DEVIATION) {
                            isRecentGestureInProgress = true
                            return true
                        }
                    } else {
                        // Свайп вверх
                        if (abs(dy) > HOME_SWIPE_THRESHOLD / 2 &&
                            abs(dx) < HOME_SWIPE_MAX_X_DEVIATION) {
                            isHomeGestureInProgress = true
                            return true
                        }
                    }
                }

                // Горизонтальные жесты обрабатываются в ACTION_UP
                return false
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                Log.v(TAG, "Action up, dx=$dx, dy=$dy")

                if (isHomeGestureInProgress) {
                    // Проверяем завершение жеста "домой"
                    if (abs(lastY - event.rawY) > HOME_SWIPE_THRESHOLD &&
                        abs(event.rawX - lastX) < HOME_SWIPE_MAX_X_DEVIATION) {
                        Log.d(TAG, "Home swipe completed")
                        performHomeAction()
                    }
                    isHomeGestureInProgress = false
                    return true
                }

                if (isRecentGestureInProgress) {
                    // Проверяем завершение жеста "недавние приложения"
                    if (abs(event.rawY - lastY) > RECENT_SWIPE_THRESHOLD &&
                        abs(event.rawX - lastX) < RECENT_SWIPE_MAX_X_DEVIATION) {
                        Log.d(TAG, "Recent apps swipe completed")
                        performRecentAppsAction()
                    }
                    isRecentGestureInProgress = false
                    return true
                }

                // Обработка горизонтальных жестов
                if (abs(dx) > SWIPE_THRESHOLD && abs(dx) > abs(dy)) {
                    Log.d(TAG, "Horizontal swipe detected (${if (dx > 0) "RIGHT" else "LEFT"})")
                    if (dx > 0) {
                        moveToNextNode()
                    } else {
                        moveToPreviousNode()
                    }
                    return true
                }
            }
        }
        return false
    }

    private fun performRecentAppsAction() {
        Log.d(TAG, "Performing recent apps action")
        vibrate()

        try {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to perform recent apps action", e)
            vibrateError()
        }
    }


    private fun performClick(isDoubleTap: Boolean = false) {
        currentNode?.let { node ->
            Log.d(TAG, "Attempting click on node: ${node.className} " +
                    "(clickable=${node.isClickable}, focusable=${node.isFocusable}, " +
                    "text=${node.text}, desc=${node.contentDescription})")

            if (node.isClickable || node.isFocusable || node.isLongClickable || node.isCheckable) {
                Log.d(TAG, "Performing direct click action")
                vibrate()
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return
                }
            }

            var parent: AccessibilityNodeInfo? = node.parent
            while (parent != null) {
                if (parent.isClickable || parent.isFocusable || parent.isLongClickable || parent.isCheckable) {
                    Log.d(TAG, "Performing click on parent ${parent.className}")
                    vibrate()
                    if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        parent.recycle()
                        return
                    }
                }
                val oldParent = parent
                parent = parent.parent
                oldParent.recycle()
            }

            if (isDoubleTap) {
                Log.d(TAG, "Attempting double tap fallback")

                if (tryLaunchFromPackageName(node)) {
                    return
                }

                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty()) {
                    performClickAtCoordinates(bounds.centerX(), bounds.centerY())
                    return
                }
            }

            Log.d(TAG, "Could not perform click on node")
            vibrateError()
        } ?: run {
            Log.w(TAG, "No current node to click")
            vibrateError()
        }
    }

    private fun tryLaunchFromPackageName(node: AccessibilityNodeInfo): Boolean {
        try {
            var currentNode: AccessibilityNodeInfo? = node
            var packageName: String? = null

            while (currentNode != null && packageName == null) {
                packageName = currentNode.packageName?.toString()
                if (packageName != null && packageName != "android") {
                    break
                }
                val oldNode = currentNode
                currentNode = currentNode.parent
                oldNode.recycle()
            }

            if (packageName != null && packageName != "android") {
                Log.d(TAG, "Found package name: $packageName, attempting to launch")
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    vibrate()
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch from package name", e)
        }
        return false
    }

    private fun performClickAtCoordinates(x: Int, y: Int) {
        Log.d(TAG, "Performing click at coordinates ($x, $y)")

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                val path = android.graphics.Path().apply {
                    moveTo(x.toFloat(), y.toFloat())
                }
                val gestureDescription = android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(
                        path, 0, 50))
                    .build()

                dispatchGesture(gestureDescription, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        Log.d(TAG, "Gesture click completed")
                    }

                    override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        Log.d(TAG, "Gesture click cancelled")
                    }
                }, null)
                return
            } catch (e: Exception) {
                Log.e(TAG, "dispatchGesture failed", e)
            }
        }

        try {
            rootInActiveWindow?.let { root ->
                val clickedNodes = root.findAccessibilityNodeInfosByViewId("$x,$y")
                if (clickedNodes != null && clickedNodes.isNotEmpty()) {
                    clickedNodes.forEach { clickedNode ->
                        if (clickedNode.isClickable) {
                            clickedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            clickedNode.recycle()
                            return
                        }
                        clickedNode.recycle()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to perform click at coordinates", e)
        }

        try {
            val downTime = SystemClock.uptimeMillis()
            val eventTime = SystemClock.uptimeMillis() + 100

            val downEvent = MotionEvent.obtain(
                downTime, eventTime,
                MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0
            )

            val upEvent = MotionEvent.obtain(
                downTime, eventTime + 50,
                MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0
            )

            try {
                @Suppress("DEPRECATION")
                Instrumentation().sendPointerSync(downEvent)
                @Suppress("DEPRECATION")
                Instrumentation().sendPointerSync(upEvent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inject touch event", e)
            } finally {
                downEvent.recycle()
                upEvent.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create touch event", e)
        }
    }
    private fun performHomeAction() {
        Log.d(TAG, "Performing home action")
        vibrate()

        try {
            val homeIntent = Intent(Intent.ACTION_MAIN)
            homeIntent.addCategory(Intent.CATEGORY_HOME)
            homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

            try {
                performGlobalAction(GLOBAL_ACTION_HOME)
            } catch (e: Exception) {
                Log.w(TAG, "Global home action failed, falling back to intent", e)
                startActivity(homeIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to perform home action", e)
            vibrateError()
        }
    }

    private fun moveToNextNode() {
        if (nodeList.isEmpty()) {
            Log.d(TAG, "No nodes available")
            vibrate()
            return
        }

        Log.d(TAG, "Moving to next node (current=$currentIndex, total=${nodeList.size})")
        if (currentIndex < nodeList.size - 1) {
            currentIndex++
            highlightNode(nodeList[currentIndex])
        } else {
            Log.d(TAG, "Reached end of list, cannot move further")
            vibrate()
        }
        highlightNode(nodeList[currentIndex])
        speakCurrentNode()
    }

    private fun moveToPreviousNode() {
        if (nodeList.isEmpty()) {
            Log.d(TAG, "No nodes available")
            vibrate()
            return
        }

        Log.d(TAG, "Moving to previous node (current=$currentIndex)")
        if (currentIndex > 0) {
            currentIndex--
            highlightNode(nodeList[currentIndex])
        } else {
            Log.d(TAG, "Reached start of list, cannot move back")
            vibrate()
        }
        highlightNode(nodeList[currentIndex])
        speakCurrentNode()
    }

    private fun vibrateError() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val pattern = longArrayOf(0, 50, 50, 50)
                val amplitudes = intArrayOf(0, 100, 0, 100)
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 50, 50, 50), -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error vibration failed", e)
        }
    }

    private fun vibrate() {
        Log.d(TAG, "Triggering vibration")
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(
                    VIBRATION_DURATION.toLong(),
                    VibrationEffect.DEFAULT_AMPLITUDE
                ))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(VIBRATION_DURATION.toLong())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }
    }

    private fun performClick() {
        currentNode?.let { node ->
            if (node.isClickable) {
                Log.d(TAG, "Performing click on ${node.className} (text=${node.text}, desc=${node.contentDescription})")
                vibrate()
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                Log.d(TAG, "Node not clickable: ${node.className}")
                vibrateError()
            }
        } ?: run {
            Log.w(TAG, "No current node to click")
            vibrateError()
        }
    }

    fun disableService() {
        Log.d(TAG, "Disabling service")
        isServiceActive = false
        highlightView?.visibility = View.INVISIBLE
    }

    private fun AccessibilityEvent.eventTypeToString(): String {
        return when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
            else -> "UNKNOWN($eventType)"
        }
    }
}

