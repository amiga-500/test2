package frost.swingActiveRender

import frost.swingActiveRender.SwingActiveRender.TARGET_FPS
import java.awt.Component
import java.awt.Toolkit
import java.util.concurrent.*
import java.util.concurrent.locks.*
import kotlin.concurrent.withLock

/**
 * Loop that controls the active rendering process
 */
class ActiveRenderLoop : Runnable {
    @Volatile
    lateinit var currentThread: Thread

    private val lock = ReentrantLock()
    private var started = false;
    private val condition = lock.newCondition()
    private val runTimeCondition = lock.newCondition()


    // volatile, so that access triggers thread synchrony
    @Volatile
    private var hasActiveRenders = false
    private val activeRenders: MutableList<Component> = CopyOnWriteArrayList()
    /*
     * Components in this set are repainted through AWT rather than painted
     * directly on the active-render thread.
     */
    private val repaintOnlyComponents: MutableSet<Component> =
        ConcurrentHashMap.newKeySet<Component>()

    override fun run() {
        currentThread = Thread.currentThread()

        lock.withLock {
            started = true
            condition.signal()
        }

        var lastTime = System.nanoTime()
        val defaultToolkit = Toolkit.getDefaultToolkit()

        // 30 FPS is usually just fine. This isn't a game where we need 60+ FPS. We permit this to be changed though, just in case it is.
        @Suppress("LocalVariableName")
        val OPTIMAL_TIME = (1000000000 / TARGET_FPS).toLong()

        // is a copy-on-write, and it must eventually be correct.
        val renderEvents = SwingActiveRender.activeRenderEvents

        while (true) {
            // if we have NO active renderers, just wait for one.

            while (hasActiveRenders) {
                try {
                    val now = System.nanoTime()
                    val updateDeltaNanos = now - lastTime
                    lastTime = now

                    for (event in renderEvents) {
                        event.invoke(updateDeltaNanos)
                    }

                    val activeRenders = this.activeRenders

                    for (component in activeRenders) {

                        if (!component.isDisplayable) {
                            continue
                        }

                        try {

                            if (
                                repaintOnlyComponents.contains(
                                    component
                                )
                            ) {

                                /*
                                 * Queue a normal AWT repaint. This lets
                                 * Windows perform its translucent-window
                                 * painting and compositing.
                                 */
                                if (component.isVisible) {
                                    component.repaint()
                                }

                            } else {

                                /*
                                 * Preserve the original active rendering
                                 * behavior for normal components.
                                 */
                                val graphics =
                                    component.graphics

                                if (graphics != null) {

                                    try {

                                        component.paint(graphics)

                                    } finally {

                                        graphics.dispose()
                                    }
                                }
                            }

                            defaultToolkit.sync()

                        } catch (e: Exception) {

                            // A component may have been closed during rendering.
                            e.printStackTrace()
                        }
                    }

                    try {
                        // Converted to int before the division, because IDIV is
                        // 1 order magnitude faster than LDIV (and INTs work for us anyways)
                        // see: http://www.cs.nuim.ie/~jpower/Research/Papers/2008/lambert-qapl08.pdf
                        // Also, down-casting (long -> int) is not expensive w.r.t IDIV/LDIV
                        val l = (lastTime - System.nanoTime() + OPTIMAL_TIME).toInt()
                        val millis = l / 1_000_000

                        if (millis > 1) {
                            Thread.sleep(millis.toLong())
                        } else {
                            // try to keep the CPU from getting slammed. We couldn't match our target FPS, so loop again
                            Thread.yield()
                        }
                    } catch (ignored: InterruptedException) {
                    }
                } catch (e: Exception) {
                    // there was an unexpected problem
                    e.printStackTrace()
                }
            }

            lock.withLock {
                runTimeCondition.await()
            }
        }
    }

    fun maybeShutdown(component: Component): Boolean {
        activeRenders.remove(component)
        repaintOnlyComponents.remove(component)

        val hasActiveRenders = activeRenders.isNotEmpty()
        this.hasActiveRenders = hasActiveRenders
        return hasActiveRenders
    }

    fun signalStart(
        component: Component,
        repaintOnly: Boolean
    ) {

        if (repaintOnly) {

            repaintOnlyComponents.add(component)

        } else {

            repaintOnlyComponents.remove(component)
        }

        hasActiveRenders = true

        activeRenders.add(component)

        lock.withLock {

            runTimeCondition.signal()
        }
    }

    fun waitForStartup() {
        lock.withLock {
            if (!started) {
                condition.await()
            }
        }
    }
}
