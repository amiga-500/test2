package frost.notify

import frost.notify.LAFUtil.tweenEngine
import frost.notify.Notify.Companion.MOVE_DURATION
import dorkbox.tweenEngine.Tween
import dorkbox.tweenEngine.TweenEquations
import dorkbox.tweenEngine.TweenEvents
import dorkbox.util.ScreenUtil
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.JWindow

// we can't use regular popup, because if we have no owner, it won't work!
// instead, we just create a JWindow and use it to hold our content
internal class DesktopNotify(
    override val notification: Notify
) : JWindow(findTranslucencyCapableGraphicsConfiguration()), NotifyType<DesktopNotify> {

    companion object {

        private val tweenAccessor = DesktopAccessor()

        private val windowCloseListener =
            DesktopWindowCloseAdapter()

        private val mouseListener =
            DesktopMouseAdapter()

        @Suppress("DuplicatedCode")
        private fun getAnchorX(
            position: Position,
            bounds: Rectangle
        ): Int {

            // we use the screen that the mouse is currently on.
            val startX =
                bounds.getX().toInt()

            val screenWidth =
                bounds.getWidth().toInt()

            return when (position) {

                // LEFT ALIGN
                Position.TOP_LEFT,
                Position.BOTTOM_LEFT ->
                    Notify.MARGIN + startX

                // CENTER ALIGN
                Position.TOP,
                Position.CENTER,
                Position.BOTTOM ->
                    startX +
                        screenWidth / 2 -
                        Notify.WIDTH / 2

                // RIGHT ALIGN
                Position.TOP_RIGHT,
                Position.BOTTOM_RIGHT ->
                    startX +
                        screenWidth -
                        Notify.WIDTH -
                        Notify.MARGIN
            }
        }

        private fun getAnchorY(
            position: Position,
            bounds: Rectangle,
            height: Int
        ): Int {

            val startY =
                bounds.getY().toInt()

            val screenHeight =
                bounds.getHeight().toInt()

            return when (position) {

                // TOP ALIGN
                Position.TOP_LEFT,
                Position.TOP,
                Position.TOP_RIGHT ->
                    startY + Notify.MARGIN

                // CENTER ALIGN
                Position.CENTER ->
                    startY +
                        screenHeight / 2 -
                        height / 2

                // BOTTOM ALIGN
                Position.BOTTOM_LEFT,
                Position.BOTTOM,
                Position.BOTTOM_RIGHT ->
                    startY +
                        screenHeight -
                        height -
                        Notify.MARGIN
            }
        }
    }

    private lateinit var cachedImage: BufferedImage
    private lateinit var cachedClose: BufferedImage
    private lateinit var cachedCloseEnabled: BufferedImage
    private var transparencyActive = false
    private var transparencyWarningLogged = false

    private var animationTween: Tween<DesktopNotify>? = null
    private var closing = false

    @Volatile
    internal var animationOffsetX = 0

    @Volatile
    internal var animationOffsetY = 0

    override var linkBounds: List<Rectangle> =
        emptyList()

    // for the progress bar. we directly draw this onscreen
    // non-volatile because it's always accessed in the active render thread
    override var progress = 0

    /*
     * Local close-button bounds.
     *
     * These match the actual rendered close button:
     *
     *   x = WIDTH - 33
     *   y = 12
     *   size = 22
     *
     * DesktopNotify converts these into screen coordinates when
     * the window moves so draw() can perform its hover test.
     */
    private val closeButton =
        Rectangle(
            Notify.WIDTH - 33,
            12,
            22,
            22
        )

    override var shakeTween: Tween<DesktopNotify>? =
        null

    override var moveTween: Tween<DesktopNotify>? =
        null

    override var hideTween: Tween<DesktopNotify>? =
        null

    // this is used in combination with position, so that we can track
    // which screen and what position a popup is in
    override var idAndPosition = ""

    override var popupIndex = 0

    override var anchorX = 0

    override var anchorY = 0

    // the ONLY reason "shake" works, is because we configure the target X/Y location for moving based on where we WANT the popup to go
    // and completely ignoring its current position
    var shakeX = 0

    var shakeY = 0

    // this is on the swing EDT
    init {

        addWindowListener(
            windowCloseListener
        )

        addMouseListener(
            mouseListener
        )

        addMouseMotionListener(
            mouseListener
        )

        isAlwaysOnTop = true

        val actualSize =
            Dimension(
                Notify.WIDTH,
                notification.notificationHeight
            )

        preferredSize =
            actualSize

        maximumSize =
            actualSize

        minimumSize =
            actualSize

        size =
            actualSize

        setLocation(
            Short.MIN_VALUE.toInt(),
            Short.MIN_VALUE.toInt()
        )

        background =
            notification.theme.panel_BG

        // now we setup the rendering of the image
        refresh()
    }

    override fun refresh() {

        configureTransparency()

        val rendered =
            renderBackgroundInfo(
                notification.title,
                notification.text,
                notification.theme,
                notification.image,
                transparencyActive
            )

        cachedImage =
            rendered.image

        linkBounds =
            rendered.linkBounds

        notification.notificationHeight =
            rendered.height

        setSize(
            Notify.WIDTH,
            notification.notificationHeight
        )

        preferredSize =
            Dimension(
                Notify.WIDTH,
                notification.notificationHeight
            )

        maximumSize =
            Dimension(
                Notify.WIDTH,
                notification.notificationHeight
            )

        minimumSize =
            Dimension(
                Notify.WIDTH,
                notification.notificationHeight
            )

        /*
         * Cache both close-button states.
         */
        cachedClose =
            renderCloseButton(
                notification.theme,
                false
            )

        cachedCloseEnabled =
            renderCloseButton(
                notification.theme,
                true
            )

        val bounds =
            LAFUtil.getGraphics(
                notification.screen
            )

        val point =
            Point(
                bounds.getX().toInt(),
                bounds.getY().toInt()
            )

        idAndPosition =
            ScreenUtil
                .getMonitorNumberAtLocation(
                    point
                )
                .toString() +
            ":" +
            notification.position

        val growDown =
            LAFUtil.growDown(
                this
            )

        anchorX =
            getAnchorX(
                notification.position,
                bounds
            )

        anchorY =
            getAnchorY(
                notification.position,
                bounds,
                notification.notificationHeight
            ) +
            calculateOffset(
                growDown,
                point
            )

        /*
         * If this notification is already visible and its height changed,
         * reposition the rest of its notification stack.
         */
        LAFUtil.relayoutPopupStack(this)
    }

    private fun logTransparencyFallback(reason: String) {

        if (transparencyWarningLogged) {
            return
        }

        transparencyWarningLogged = true

        println(
            "Notify transparency requested but unavailable: $reason"
        )
    }

    /**
     * have to adjust for offsets when the window-manager has a toolbar that consumes space and prevents overlap.
     */
    private fun calculateOffset(
        showFromTop: Boolean,
        point: Point
    ): Int {

        val gc =
            ScreenUtil
                .getMonitorAtLocation(point)
                ?.defaultConfiguration
                ?: return 0

        val screenInsets =
            Toolkit
                .getDefaultToolkit()
                .getScreenInsets(gc)

        return if (showFromTop) {
            screenInsets.top
        } else {
            -(screenInsets.bottom)
        }
    }

    override fun paint(
        gIgnore: Graphics
    ) {

        /*
         * Windows' per-pixel translucent window painter supplies its own
         * graphics context. Draw into that context so Windows can capture
         * and display the notification correctly.
         *
         * Other platforms, and opaque Windows notifications, retain the
         * existing BufferStrategy rendering path.
         */
        val directPainting =
            useRepaintOnlyTransparencyPainting()

        val strategy =
            if (directPainting) {
                null
            } else {
                getBufferStrategy()
            }

        if (!directPainting && strategy == null) {
            return
        }

        val g =
            if (directPainting) {
                gIgnore.create()
            } else {
                strategy!!.drawGraphics
            }

        try {

            /*
             * Clear the entire current painting area before drawing the
             * next frame. For transparent Windows notifications, use
             * AlphaComposite.Clear so previous pixels become transparent.
             *
             * This prevents the old notification position from remaining
             * visible underneath the animated position.
             */
            if (directPainting) {

                val graphics2D =
                    g as Graphics2D

                val originalComposite =
                    graphics2D.composite

                try {

                    graphics2D.composite =
                        AlphaComposite.Clear

                    graphics2D.fillRect(
                        0,
                        0,
                        width,
                        height
                    )

                } finally {

                    graphics2D.composite =
                        originalComposite
                }

            } else {

                /*
                 * Keep the existing clearing behavior for BufferStrategy.
                 */
                g.clearRect(
                    0,
                    0,
                    width,
                    height
                )
            }

            // Render the notification, preserving existing recovery behavior.
            try {

                draw(g)

            } catch (ignored: Exception) {

                refresh()

                try {

                    draw(g)

                } catch (ignored2: Exception) {
                }
            }

        } finally {

            // Dispose the graphics context in either rendering mode.
            g.dispose()
        }

        /*
         * Present the buffer only when the BufferStrategy path was used.
         */
        if (!directPainting) {
            strategy!!.show()
        }
    }


    private fun draw(
        g: Graphics
    ) {

        /*
         * animationOffsetX/Y are used only for the visual
         * entry/exit animation.
         *
         * shakeX/Y are intentionally NOT included here because
         * shake() physically moves the JWindow itself.
         */
        val translatedGraphics =
            g.create()

        try {

            translatedGraphics.translate(
                animationOffsetX,
                animationOffsetY
            )

            translatedGraphics.drawImage(
                cachedImage,
                0,
                0,
                null
            )

            if (!notification.hideCloseButton) {

                /*
                 * closeButton contains screen coordinates for the
                 * notification's normal window position.
                 *
                 * During entry/exit animation the rendered close button
                 * moves with the cached notification image, so apply
                 * the same visual animation offset when checking hover.
                 */
                val mouseLocation =
                    MouseInfo
                        .getPointerInfo()
                        ?.location

                val visualCloseButton =
                    Rectangle(
                        closeButton
                    )

                visualCloseButton.translate(
                    animationOffsetX,
                    animationOffsetY
                )

                if (
                    mouseLocation != null &&
                    visualCloseButton.contains(
                        mouseLocation
                    )
                ) {

                    translatedGraphics.drawImage(
                        cachedCloseEnabled,
                        0,
                        0,
                        null
                    )

                } else {

                    translatedGraphics.drawImage(
                        cachedClose,
                        0,
                        0,
                        null
                    )
                }
            }

            /*
             * The progress bar can change, so draw it every frame when active.
             *
             * It is rendered inside the same translated graphics context
             * so it remains attached to the notification during animation.
             */
            if (progress > 0) {

                val barX = 18

                val barY =
                    notification.notificationHeight - 9

                val barWidth =
                    Notify.WIDTH - 36

                val barHeight = 3

                /*
                 * Track.
                 */
                translatedGraphics.color =
                    Color(
                        255,
                        255,
                        255,
                        22
                    )

                translatedGraphics.fillRoundRect(
                    barX,
                    barY,
                    barWidth,
                    barHeight,
                    barHeight,
                    barHeight
                )

                /*
                 * Progress.
                 */
                val progressWidth =
                    (
                        (
                            barWidth.toFloat() *
                            progress.toFloat()
                        ) /
                        Notify.WIDTH.toFloat()
                    )
                        .toInt()
                        .coerceAtLeast(1)

                translatedGraphics.color =
                    notification.progressBarColor
                        ?: notification.theme.progress_FG

                translatedGraphics.fillRoundRect(
                    barX,
                    barY,
                    progressWidth.coerceAtMost(
                        barWidth
                    ),
                    barHeight,
                    barHeight,
                    barHeight
                )
            }

        } finally {

            translatedGraphics.dispose()
        }
    }

    fun onClick(
        x: Int,
        y: Int
    ) {
        if (
            animationOffsetX != 0 ||
            animationOffsetY != 0
        ) {
            return
        }
        /*
         * Coordinates passed by DesktopMouseAdapter are local
         * notification coordinates.
         */
        if (
            !notification.hideCloseButton &&
            closeButton.contains(
                x + getX(),
                y + getY()
            )
        ) {

            notification.closeByUserAction()
            return
        }

        if (isLinkAt(x, y)) {

            notification.onLinkClickAction()
            return
        }

        if (notification.closeOnBodyClick) {

            notification.closeByUserAction()

        } else {

            notification.onClickAction()
        }
    }

     override fun animateIn() {

        if (
            !notification.animateIn ||
            notification.animationDuration <= 0f ||
            animationTween != null
        ) {
            return
        }

        cancelMove()

        val startX =
            when (notification.position) {

                Position.TOP_LEFT,
                Position.BOTTOM_LEFT ->
                    -Notify.WIDTH

                Position.TOP_RIGHT,
                Position.BOTTOM_RIGHT ->
                    Notify.WIDTH

                else ->
                    0
            }

        val startY =
            when (notification.position) {

                Position.TOP,
                Position.CENTER ->
                    -notification.notificationHeight

                Position.BOTTOM ->
                    notification.notificationHeight

                else ->
                    0
            }

        /*
         * Put the rendered notification completely outside the
         * JWindow's visible area.
         */
        setAnimationOffset(
            startX,
            startY
        )

        val popup =
            this

        val tween =
            tweenEngine
                .to(
                    this,
                    DesktopAccessor.ANIMATION_OFFSET,
                    tweenAccessor,
                    notification.animationDuration
                )
                .value(
                    0f,
                    0f
                )
                .ease(
                    TweenEquations.Linear
                )
                .addCallback(
                    TweenEvents.COMPLETE
                ) {

                    setAnimationOffset(
                        0,
                        0
                    )

                    popup.animationTween = null
                }

        animationTween =
            tween

        tween.start()
    }

    override fun setupHide() {

        if (hideTween != null) {

            hideTween!!.value(
                Notify.WIDTH.toFloat()
            )

        } else if (
            notification.hideAfterDurationInMillis > 0
        ) {

            // begin a timeline to get rid of the popup (default is 5 seconds)
            val tween =
                tweenEngine
                    .to(
                        this,
                        DesktopAccessor.PROGRESS,
                        tweenAccessor,
                        notification.hideAfterDurationInMillis / 1000.0f
                    )
                    .value(
                        Notify.WIDTH.toFloat()
                    )
                    .ease(
                        TweenEquations.Linear
                    )
                .addCallback(
                    TweenEvents.COMPLETE
                ) {
                    /*
                     * The hide tween has completed. Clear the reference before
                     * closing the notification so cancelHide() cannot operate on
                     * a completed/pool-reusable tween.
                     */
                    hideTween = null

                    notification.onClose()
                }

            hideTween = tween

            tween.start()
        }
    }

    override fun setupMove(
        y: Float
    ) {

        if (moveTween != null) {

            moveTween!!.value(y)

        } else {

            val tween =
                tweenEngine
                    .to(
                        this,
                        DesktopAccessor.Y_POS,
                        tweenAccessor,
                        MOVE_DURATION
                    )
                    .value(y)
                    .ease(
                        TweenEquations.Linear
                    )
                    .addCallback(
                        TweenEvents.COMPLETE
                    ) {

                        // make sure to remove the tween once it's done, otherwise .cancel can do weird things.
                        moveTween = null
                    }

            moveTween = tween

            tween.start()
        }
    }

    override fun doShake(
        count: Int,
        targetX: Float,
        targetY: Float
    ) {

        if (shakeTween != null) {

            shakeTween!!
                .value(
                    targetX,
                    targetY
                )
                .repeatAutoReverse(
                    count,
                    0f
                )

        } else {

            val tween =
                tweenEngine
                    .to(
                        this,
                        DesktopAccessor.SHAKE,
                        tweenAccessor,
                        0.05f
                    )
                    .value(
                        targetX,
                        targetY
                    )
                    .repeatAutoReverse(
                        count,
                        0f
                    )
                    .ease(
                        TweenEquations.Linear
                    )
                    .addCallback(
                        TweenEvents.COMPLETE
                    ) {
                        /*
                         * IMPORTANT:
                         *
                         * TweenEngine automatically returns a completed
                         * tween to its pool. Clear our reference before
                         * that happens so we never retain a reference to
                         * a tween that may later be reused for something
                         * else.
                         */
                        shakeTween = null
                    }

            shakeTween = tween

            tween.start()
        }
    }

    override fun setLocationInternal(
        x: Int,
        y: Int
    ) {

        setLocation(
            x,
            y
        )

        /*
         * Update the screen-space hover rectangle while keeping
         * the actual local close-button geometry unchanged.
         */
        closeButton.setLocation(
            x + Notify.WIDTH - 33,
            y + 12
        )
    }

    /*
     * Per-pixel translucent windows use ordinary AWT repainting on
     * Windows and macOS. Linux retains the original active-rendering
     * path, which has already been tested successfully.
     */
    internal fun useRepaintOnlyTransparencyPainting(): Boolean {

        val osName =
            System.getProperty(
                "os.name",
                ""
            )

        val isWindows =
            osName.startsWith(
                "Windows",
                ignoreCase = true
            )

        val isMacOS =
            osName.startsWith(
                "Mac",
                ignoreCase = true
            )

        return transparencyActive &&
            (isWindows || isMacOS)
    }

    // this is called during parent initialization (before we are initialized), so we cannot access objects here properly!
    //    override fun setLocation(x: Int, y: Int) {
    //        super.setLocation(x, y)
    //    }

    override fun setVisible(
        visible: Boolean
    ) {

        updatePositionsPre(
            this,
            this,
            visible
        )

        /*
         * Transparent Windows notifications must be initialized before
         * showing the window. Otherwise Windows can paint the notification
         * in its final position before animateIn() sets the starting offset.
         */
        if (
            visible &&
            !isVisible &&
            useRepaintOnlyTransparencyPainting()
        ) {

            // Set the stack position, start the animation, and register
            // the repaint-only renderer before exposing the native window.
            updatePositionsPost(
                this,
                this,
                true
            )

            super.setVisible(true)

        } else {

            /*
             * Preserve the original lifecycle on Linux and for opaque
             * notifications, including the BufferStrategy rendering path.
             */
            super.setVisible(
                visible
            )

            updatePositionsPost(
                this,
                this,
                visible
            )
        }

        if (visible) {
            toFront()
        }
    }

    // called on the Swing EDT
    override fun close() {

        if (closing) {
            return
        }

        /*
         * Cancel any existing entry animation.
         *
         * The notification will continue its exit animation from its
         * current visual offset.
         */
        if (animationTween != null) {

            animationTween!!.cancel()
            animationTween = null
        }

        if (
            notification.animateOut &&
            notification.animationDuration > 0f &&
            isVisible
        ) {

            closing = true

            cancelMove()
            cancelHide()
            cancelShake()

            val endX: Int
            val endY: Int

            when (notification.position) {

                Position.TOP_LEFT,
                Position.BOTTOM_LEFT -> {

                    endX =
                        -Notify.WIDTH

                    endY = 0
                }

                Position.TOP_RIGHT,
                Position.BOTTOM_RIGHT -> {

                    endX =
                        Notify.WIDTH

                    endY = 0
                }

                Position.TOP,
                Position.CENTER -> {

                    endX = 0

                    endY =
                        -notification.notificationHeight
                }

                Position.BOTTOM -> {

                    endX = 0

                    endY =
                        notification.notificationHeight
                }
            }

            val popup =
                this

            val tween =
                tweenEngine
                    .to(
                        this,
                        DesktopAccessor.ANIMATION_OFFSET,
                        tweenAccessor,
                        notification.animationDuration
                    )
                    .value(
                        endX.toFloat(),
                        endY.toFloat()
                    )
                    .ease(
                        TweenEquations.Linear
                    )
                    .addCallback(
                        TweenEvents.COMPLETE
                    ) {

                        popup.animationTween = null
                        popup.closeImmediately()
                    }

            animationTween =
                tween

            tween.start()

            return
        }

        closeImmediately()
    }

    private fun closeImmediately() {

        if (animationTween != null) {

            animationTween!!.cancel()
            animationTween = null
        }

        setAnimationOffset(
            0,
            0
        )

        cancelMove()
        cancelHide()
        cancelShake()

        removeWindowListener(
            windowCloseListener
        )

        removeMouseMotionListener(
            mouseListener
        )

        removeMouseListener(
            mouseListener
        )

        updatePositionsPre(
            component = this,
            notify = this,
            visible = false
        )

        super.setVisible(false)

        removeAll()
        dispose()

        notification.onCloseCompleted()
    }

    fun setLocationShake(x: Int, y: Int) {
        val x1 = getX() - shakeX
        val y1 = getY() - shakeY

        shakeX = x
        shakeY = y

        setLocationInternal(
            x1 + x,
            y1 + y
        )
    }

    fun updateCursor(
        x: Int,
        y: Int
    ) {

        cursor =
            if (
                isLinkAt(
                    x,
                    y
                )
            ) {

                Cursor.getPredefinedCursor(
                    Cursor.HAND_CURSOR
                )

            } else {

                Cursor.getDefaultCursor()
            }
    }

    /**
     * Configures the actual AWT window transparency.
     *
     * Notify.transparentBackground is only a request. We first verify that
     * the graphics device supports per-pixel translucency.
     *
     * Unsupported platforms simply fall back to the normal opaque
     * notification.
     */
    private fun configureTransparency() {

        if (!notification.transparentBackground) {

            transparencyActive = false

            background =
                notification.theme.panel_BG

            return
        }

        val graphicsDevice =
            graphicsConfiguration.device

        val deviceSupports =
            graphicsDevice.isWindowTranslucencySupported(
                GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT
            )

        val configurationSupports =
            graphicsConfiguration.isTranslucencyCapable

        if (!deviceSupports) {

            transparencyActive = false

            background =
                notification.theme.panel_BG

            logTransparencyFallback(
                "graphics device does not support PERPIXEL_TRANSLUCENT"
            )

            return
        }

        if (!configurationSupports) {

            transparencyActive = false

            background =
                notification.theme.panel_BG

            logTransparencyFallback(
                "graphics configuration is not translucency-capable"
            )

            return
        }

        try {

            background =
                Color(
                    0,
                    0,
                    0,
                    0
                )

            transparencyActive = true

        } catch (ignored: UnsupportedOperationException) {

            transparencyActive = false

            background =
                notification.theme.panel_BG

            logTransparencyFallback(
                "AWT rejected the transparent background (UnsupportedOperationException)"
            )

        } catch (ignored: IllegalComponentStateException) {

            transparencyActive = false

            background =
                notification.theme.panel_BG

            logTransparencyFallback(
                "window was not in a state where transparency could be enabled"
            )
        }
    }

    fun setAnimationOffset(
        x: Int,
        y: Int
    ) {

        animationOffsetX = x
        animationOffsetY = y
    }
}

private fun findTranslucencyCapableGraphicsConfiguration(): GraphicsConfiguration {

    val graphicsEnvironment =
        GraphicsEnvironment.getLocalGraphicsEnvironment()

    val defaultDevice =
        graphicsEnvironment.defaultScreenDevice

    /*
     * Prefer a translucency-capable configuration on the default
     * screen/device.
     */
    if (
        defaultDevice.isWindowTranslucencySupported(
            GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT
        )
    ) {

        for (configuration in defaultDevice.configurations) {

            if (configuration.isTranslucencyCapable) {

                return configuration
            }
        }
    }

    /*
     * If the default device has no usable configuration, check
     * the other graphics devices.
     */
    for (device in graphicsEnvironment.screenDevices) {

        if (
            !device.isWindowTranslucencySupported(
                GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT
            )
        ) {
            continue
        }

        for (configuration in device.configurations) {

            if (configuration.isTranslucencyCapable) {

                return configuration
            }
        }
    }

    /*
     * Fall back to the normal default configuration when the
     * platform exposes no translucency-capable configuration.
     */
    return defaultDevice.defaultConfiguration
}