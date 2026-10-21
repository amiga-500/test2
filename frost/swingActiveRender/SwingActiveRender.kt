package frost.swingActiveRender

import java.awt.Canvas
import java.awt.Component
import java.awt.Window
import java.util.concurrent.*
import javax.swing.SwingUtilities

/**
 * Contains all the appropriate logic to setup and render via "Active" rendering (instead of "Passive" rendering).
 *
 * This permits us to render components OFF of the EDT - even though there are other frames/components that are ON the EDT.
 * <br></br>
 * Because we still want to react to mouse events, etc on the EDT, we do not completely remove the EDT -- we merely allow us
 * to "synchronize" the EDT object to our thread. It's a little-bit hacky, but it works beautifully, and permits MUCH nicer animations.
 * <br></br>
 */
@Suppress("MemberVisibilityCanBePrivate")
object SwingActiveRender {
    /**
     * How many frames per second we want the Swing ActiveRender thread to run at
     *
     * ### NOTE:
     *
     * The ActiveRenderLoop replaces the Swing EDT (only for specified JFrames) in order to enable smoother animations. It is also
     * important to REMEMBER -- if you add a component to an actively managed JFrame, YOU MUST make sure to call
     * [javax.swing.JComponent.setIgnoreRepaint] otherwise this component will "fight" on the EDT for updates.
     *
     * You can ALSO completely disable the Swing EDT by calling [NullRepaintManager.install]
     */
    @Volatile
    var TARGET_FPS = 30


    private val activeRenderThread: Thread

    internal val activeRenderEvents: MutableList<(deltaInNanos: Long)->Unit> = CopyOnWriteArrayList()

    private val renderLoop = ActiveRenderLoop()

    /*
     * Start the active-render thread.
     * Update/version registration is not needed by Frost-Next.
     */
    init {
        activeRenderThread =
            Thread(
                renderLoop,
                "Swing-ActiveRender"
            )

        activeRenderThread.isDaemon = true

        activeRenderThread.start()
    }

    /**
     * Enables the component to be added to an "Active Render" thread, at a target "Frames-per-second". This is to support smooth, swing-based
     * animations.
     *
     *
     * This works by removing this object from EDT updates and manually calls paint on the component, updating it on our own thread, but
     * still remaining synchronized with the EDT.
     *
     * @param component the component to add to the ActiveRender thread.
     */
    fun add(component: Component) {

        add(component, false)
    }

    /*
     * repaintOnly is intended for per-pixel translucent Windows windows.
     *
     * Such windows must use AWT's normal repaint mechanism instead of
     * BufferStrategy, which does not reliably present their pixels.
     */
    fun add(
        component: Component,
        repaintOnly: Boolean
    ) {

        val ignoreRepaintValue =
            !repaintOnly

        if (SwingUtilities.isEventDispatchThread()) {

            component.ignoreRepaint =
                ignoreRepaintValue

        } else {

            SwingUtilities.invokeAndWait {

                component.ignoreRepaint =
                    ignoreRepaintValue
            }
        }

        /*
         * The normal rendering path retains BufferStrategy.
         * The repaint-only path deliberately does not create one.
         */
        if (!repaintOnly) {

            if (component is Window) {

                component.createBufferStrategy(2)

            } else if (component is Canvas) {

                component.createBufferStrategy(2)
            }
        }

        setupActiveRenderThread()

        renderLoop.signalStart(
            component,
            repaintOnly
        )
    }


    /**
     * Removes a component from the ActiveRender queue. This should happen when the component is closed.
     *
     * @param component the component to remove
     */
    fun remove(component: Component) {
        if (SwingUtilities.isEventDispatchThread()) {
            // this part has to be on the swing EDT
            component.ignoreRepaint = false
        } else {
            SwingUtilities.invokeAndWait {
                // this part has to be on the swing EDT
                component.ignoreRepaint = false
            }
        }

        renderLoop.maybeShutdown(component)
    }

    /**
     * Specifies an ActionHandler to be called when the ActiveRender thread starts to render at each tick.
     *
     * @param handler the handler to add
     */
    fun add(handler: (deltaInNanos: Long)->Unit) {
        activeRenderEvents.add(handler)
    }

    /**
     * Potentially SLOW calculation, as it compares each entry in a queue for equality
     *
     * @param handler this is the handler to check
     *
     * @return true if this handler already exists in the active render, on-frame-start queue
     */
    fun contains(handler: (deltaInNanos: Long)->Unit): Boolean {
        return activeRenderEvents.contains(handler)
    }

    /**
     * Removes the handler from the on-frame-start queue
     *
     * @param handler the handler to remove
     */
    fun remove(handler: (deltaInNanos: Long)->Unit) {
        activeRenderEvents.remove(handler)
    }

    fun isDispatchThread(): Boolean {
        // make sure we are initialized!
        setupActiveRenderThread()

        return Thread.currentThread() == renderLoop.currentThread
    }

    /**
     * Creates (if necessary) the active-render thread. When there are no active-render targets, this thread will exit
     */
    private fun setupActiveRenderThread() {
        renderLoop.waitForStartup()
    }
}
