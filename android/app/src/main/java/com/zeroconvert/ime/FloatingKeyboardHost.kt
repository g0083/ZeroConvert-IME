package com.zeroconvert.ime

import android.content.SharedPreferences
import android.graphics.Rect
import android.inputmethodservice.InputMethodService.Insets
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.Insets as SystemInsets
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

internal class FloatingKeyboardState(private val preferences: SharedPreferences) {
    var isFloating by mutableStateOf(preferences.getBoolean("enabled", false))
        private set
    var placement = FloatingKeyboardPlacement(
        horizontal = preferences.getFloat("horizontal", 0.5f).coerceIn(0f, 1f),
        vertical = preferences.getFloat("vertical", 0.75f).coerceIn(0f, 1f),
        widthDp = preferences.getFloat("width_dp", FloatingKeyboardPlacement.DEFAULT_WIDTH_DP),
        heightDp = preferences.getFloat("height_dp", 0f)
    )
        private set

    fun setEnabled(enabled: Boolean) {
        isFloating = enabled
        preferences.edit().putBoolean("enabled", enabled).apply()
    }

    fun savePlacement(value: FloatingKeyboardPlacement) {
        placement = value
        preferences.edit()
            .putFloat("horizontal", value.horizontal)
            .putFloat("vertical", value.vertical)
            .putFloat("width_dp", value.widthDp)
            .putFloat("height_dp", value.heightDp)
            .apply()
    }
}

internal class FloatingKeyboardHost(
    private val panel: View,
    private val state: FloatingKeyboardState,
    private val naturalKeyboardHeightDp: () -> Float,
    private val onDock: () -> Unit
) : FrameLayout(panel.context) {
    private data class Drag(
        val start: Rect,
        val corner: FloatingKeyboardCorner? = null,
        var dx: Float = 0f,
        var dy: Float = 0f,
        var finished: Boolean = false
    )
    private data class ResizeTouch(val pointerId: Int, val x: Float, val y: Float, var moved: Boolean = false)

    private var drag by mutableStateOf<Drag?>(null)
    private var resizeTouch: ResizeTouch? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var systemInsets = SystemInsets.NONE
    private val viewport = Rect()
    private val panelFrame = Rect()
    private val panelLocation = IntArray(2)
    var activeResizeCorner by mutableStateOf<FloatingKeyboardCorner?>(null)
        private set
    var cornerRevealCount by mutableIntStateOf(0)
        private set
    val isMoving: Boolean get() = drag?.let { it.corner == null && !it.finished } == true
    var dockingPreview by mutableStateOf(false)
        private set

    init {
        // Match the input-method window while floating and wrap the same Compose view when docked.
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val safeInsets = WindowInsetsCompat.toWindowInsetsCompat(insets, this).getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        if (systemInsets != safeInsets) {
            systemInsets = safeInsets
            if (state.isFloating) requestLayout()
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!state.isFloating) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val margin = dp(8)
        val left = (systemInsets.left + margin).coerceAtMost(width)
        val top = (systemInsets.top + margin).coerceAtMost(height)
        val nextViewport = Rect(
            left,
            top,
            (width - systemInsets.right - margin).coerceAtLeast(left),
            (height - systemInsets.bottom - margin).coerceAtLeast(top)
        )
        if (drag != null && viewport != nextViewport) cancelDrag()
        viewport.set(nextViewport)
        computePanelFrame()
        panel.measure(
            MeasureSpec.makeMeasureSpec(panelFrame.width(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(panelFrame.height(), MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(width, height)
    }

    private fun computePanelFrame() {
        val gesture = drag
        val corner = gesture?.corner
        if (gesture != null && corner != null) {
            val horizontal = resizeFloatingKeyboardAxis(
                gesture.start.left..gesture.start.right, viewport.left..viewport.right,
                gesture.dx.roundToInt(), dp(FloatingKeyboardPlacement.MIN_WIDTH_DP), corner.left
            )
            val vertical = resizeFloatingKeyboardAxis(
                gesture.start.top..gesture.start.bottom, viewport.top..viewport.bottom,
                gesture.dy.roundToInt(), dp(FloatingKeyboardPlacement.MIN_HEIGHT_DP), corner.top
            )
            panelFrame.set(horizontal.first, vertical.first, horizontal.last, vertical.last)
            return
        }
        val placement = state.placement
        val width = placement.widthPx(density, viewport.width())
        val defaultHeightDp = naturalKeyboardHeightDp() * floatingKeyboardWidthScale(width / density) +
            FloatingKeyboardTopRailDp + FloatingKeyboardHandleHeightDp
        val height = placement.heightPx(density, viewport.height(), defaultHeightDp)
        val maxLeft = (viewport.right - width).coerceAtLeast(viewport.left)
        val maxTop = (viewport.bottom - height).coerceAtLeast(viewport.top)
        val requestedLeft = gesture?.let { (it.start.left + it.dx).roundToInt() }
            ?: placement.left(viewport.left, maxLeft)
        val requestedTop = gesture?.let { (it.start.top + it.dy).roundToInt() }
            ?: placement.top(viewport.top, maxTop)
        val panelLeft = requestedLeft.coerceIn(viewport.left, maxLeft)
        val panelTop = requestedTop.coerceIn(viewport.top, maxTop)
        panelFrame.set(panelLeft, panelTop, panelLeft + width, panelTop + height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!state.isFloating) {
            super.onLayout(changed, left, top, right, bottom)
            return
        }
        panel.layout(panelFrame.left, panelFrame.top, panelFrame.right, panelFrame.bottom)
        val gesture = drag
        if (gesture?.finished == true) {
            drag = null
            val width = panelFrame.width()
            val height = panelFrame.height()
            val placement = state.placement
            val resizing = gesture.corner != null
            // Once resized, keep both dimensions independent, including after a width-only drag.
            state.savePlacement(
                placement.copy(
                    widthDp = if (resizing && width != gesture.start.width()) {
                        width / density
                    } else {
                        placement.widthDp
                    },
                    heightDp = if (resizing && (height != gesture.start.height() || placement.heightDp == 0f)) {
                        height / density
                    } else {
                        placement.heightDp
                    }
                ).placedAt(
                    panelFrame.left, panelFrame.top,
                    viewport.left, viewport.right - width, viewport.top, viewport.bottom - height
                )
            )
        }
    }

    fun computeFloatingInsets(outInsets: Insets) {
        // Insets are relative to the IME window. The floating panel reserves no app layout space.
        val windowBottom = rootView.height
        outInsets.contentTopInsets = windowBottom
        outInsets.visibleTopInsets = windowBottom
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        panel.getLocationInWindow(panelLocation)
        outInsets.touchableRegion.set(
            panelLocation[0], panelLocation[1], panelLocation[0] + panel.width, panelLocation[1] + panel.height
        )
    }

    fun computeDockedOverlayInsets(outInsets: Insets, overlayHeightPx: Int) {
        panel.getLocationInWindow(panelLocation)
        val keyboardTop = (panelLocation[1] + overlayHeightPx).coerceIn(0, rootView.height)
        outInsets.contentTopInsets = keyboardTop
        outInsets.visibleTopInsets = keyboardTop
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(
            panelLocation[0],
            panelLocation[1],
            panelLocation[0] + panel.width,
            panelLocation[1] + panel.height
        )
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (state.isFloating && drag == null && event.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = event.x - panel.left
            val y = event.y - panel.top
            val cornerWidth = dp(FloatingKeyboardCornerWidthDp).coerceAtMost(panel.width / 2)
            val corner = FloatingKeyboardCorner.entries.firstOrNull {
                val edgeX = if (it.left) x else panel.width - x
                val edgeY = if (it.top) y else panel.height - y
                val railHeight = if (it.top) FloatingKeyboardTopRailDp else FloatingKeyboardHandleHeightDp
                edgeX >= 0 && edgeX < cornerWidth && edgeY >= 0 && edgeY < dp(railHeight)
            }
            if (corner != null) {
                drag = Drag(Rect(panelFrame), corner)
                resizeTouch = ResizeTouch(event.getPointerId(0), event.rawX, event.rawY)
                activeResizeCorner = corner
                cornerRevealCount++
                return true
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val touch = resizeTouch ?: return super.onTouchEvent(event)
        val gesture = drag ?: return super.onTouchEvent(event)
        val index = event.findPointerIndex(touch.pointerId)
        if (event.actionMasked == MotionEvent.ACTION_CANCEL || index < 0) {
            cancelDrag()
            return true
        }
        // Convert the active pointer to screen coordinates even if another finger goes down.
        val dx = event.rawX + event.getX(index) - event.x - touch.x
        val dy = event.rawY + event.getY(index) - event.y - touch.y
        if (touch.moved || dx * dx + dy * dy > touchSlop * touchSlop) {
            touch.moved = true
            gesture.dx = dx
            gesture.dy = dy
            requestLayout()
        }
        if ((event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_POINTER_UP) &&
            index == event.actionIndex
        ) {
            resizeTouch = null
            activeResizeCorner = null
            if (touch.moved) {
                gesture.finished = true
                requestLayout()
            } else {
                cancelDrag()
            }
        }
        return true
    }

    fun startMove() {
        if (drag != null) return
        drag = Drag(Rect(panelFrame))
        cornerRevealCount++
    }

    fun moveBy(dx: Float, dy: Float) {
        val gesture = drag?.takeIf { it.corner == null && !it.finished } ?: return
        gesture.dx += dx
        gesture.dy += dy
        dockingPreview = isAtDockEdge(gesture)
        requestLayout()
    }

    fun finishMove() {
        val gesture = drag?.takeIf { it.corner == null && !it.finished } ?: return
        if (dockingPreview && isAtDockEdge(gesture)) {
            cancelDrag()
            onDock()
        } else {
            dockingPreview = false
            gesture.finished = true
            requestLayout()
        }
    }

    fun cancelMove() {
        if (drag?.corner == null) cancelDrag()
    }

    fun cancelDrag() {
        drag = null
        resizeTouch = null
        activeResizeCorner = null
        dockingPreview = false
        requestLayout()
    }

    private val density: Float get() = resources.displayMetrics.density
    private fun dp(value: Int): Int = dp(value.toFloat())
    private fun dp(value: Float): Int = (value * density).roundToInt()

    private fun isAtDockEdge(gesture: Drag): Boolean = shouldDockFloatingKeyboard(
        gesture.dy, gesture.start.bottom + gesture.dy, viewport.bottom, dp(20)
    )
}
