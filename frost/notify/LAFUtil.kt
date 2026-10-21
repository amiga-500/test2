package frost.notify

import frost.swingActiveRender.SwingActiveRender
import dorkbox.tweenEngine.TweenEngine.Companion.create
import dorkbox.util.ScreenUtil
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.util.ArrayList
import java.util.Random

internal object LAFUtil {

    private val popups =
        mutableMapOf<String, MutableList<NotifyType<*>>>()

    /*
     * Notifications waiting to be displayed in ONE_AT_A_TIME mode.
     *
     * The key matches the same monitor/frame + position concept used
     * by the existing popup stack.
     */
    private val queuedNotifications =
        mutableMapOf<String, MutableList<Notify>>()

    /*
     * Notifications in the current ONE_AT_A_TIME sequence whose sounds
     * should be suppressed.
     *
     * These are individual Notify objects rather than a blanket flag,
     * so notifications arriving later can still play their sounds.
     */
    private val silentQueuedNotifications =
        mutableMapOf<String, MutableSet<Notify>>()

    // access is only from a single thread ever, so unsafe is preferred.
    val tweenEngine =
        create()
            .setCombinedAttributesLimit(2)
            .build()

    // this is for updating the tween engine during active-rendering
    private val animationUpdateHandler: (Long) -> Unit =
        { deltaInNanos ->

            /*
             * Prevent a long rendering/update stall from causing
             * tweens to jump forward by several seconds at once.
             *
             * This is especially important for notification hide
             * timers, which are implemented as tweens.
             */
            val maxDeltaInNanos =
                100_000_000L   // 100 ms

            val safeDeltaInNanos =
                deltaInNanos.coerceAtMost(
                    maxDeltaInNanos
                )

            tweenEngine.update(
                safeDeltaInNanos
            )
        }

    val RANDOM =
        Random()

    fun getGraphics(
        screen: Int
    ): Rectangle {

        var device: GraphicsDevice? = null

        if (screen == Short.MIN_VALUE.toInt()) {

            // set screen position based on mouse
            val mouseLocation =
                MouseInfo
                    .getPointerInfo()
                    .location

            device =
                ScreenUtil
                    .getMonitorAtLocation(
                        mouseLocation
                    )
        }

        if (device == null) {

            // set screen position based on specified screen
            var screenNumber =
                screen

            val ge =
                GraphicsEnvironment
                    .getLocalGraphicsEnvironment()

            val screenDevices =
                ge.screenDevices

            if (screenNumber < 0) {

                screenNumber = 0

            } else if (
                screenNumber >
                screenDevices.size - 1
            ) {

                screenNumber =
                    screenDevices.size - 1
            }

            device =
                screenDevices[screenNumber]
        }

        return device!!
            .defaultConfiguration
            .bounds
    }

    /**
     * Gets the identifier for the notification stream.
     * Desktop notifications are grouped by monitor + position.
     */
    private fun getNotificationQueueKey(
        notification: Notify
    ): String {

        val bounds =
            getGraphics(
                notification.screen
            )

        val point =
            Point(
                bounds.getX().toInt(),
                bounds.getY().toInt()
            )

        return ScreenUtil
            .getMonitorNumberAtLocation(
                point
            )
            .toString() +
            ":" +
            notification.position
    }

    /**
     * Adds a notification to its popup stack.
     *
     * Notifications are now allowed to have different heights.
     * The first notification establishes the stack's anchor position,
     * and subsequent notifications are positioned using the actual
     * heights of the notifications before them.
     */
    // only called on the swing EDT thread
    fun addPopupToMap(
        notify: NotifyType<*>
    ) {

        // start if we have previously stopped the timer
        if (
            !SwingActiveRender.contains(
                animationUpdateHandler
            )
        ) {

            tweenEngine.resetUpdateTime()

            SwingActiveRender.add(
                animationUpdateHandler
            )
        }

        synchronized(popups) {

            val id =
                notify.idAndPosition

            val allNotifications =
                popups.getOrPut(id) {
                    ArrayList(
                        4
                    )
                }

            notify.popupIndex =
                allNotifications.size

            val targetY =
                calculateTargetY(
                    notify,
                    allNotifications,
                    notify.popupIndex
                )

            allNotifications.add(
                notify
            )

            notify.setLocationInternal(
                notify.anchorX,
                targetY
            )

            notify.setupHide()
        }
    }

    /**
     * Removes a notification from its popup stack and moves the
     * remaining notifications into their new positions.
     */
    // only called on the swing app or SwingActiveRender thread
    fun removePopupFromMap(
        notify: NotifyType<*>
    ) {

        synchronized(popups) {

            val allNotifications =
                popups[notify.idAndPosition]
                    ?: return

            /*
             * There are two loops because it is necessary to cancel
             * all existing movement tweens BEFORE adding new ones.
             */
            var adjustPopupPosition = false

            val iterator =
                allNotifications.iterator()

            while (iterator.hasNext()) {

                val next =
                    iterator.next()

                next.cancelMove()

                if (adjustPopupPosition) {

                    next.popupIndex--
                }

                if (next === notify) {

                    notify.cancelHide()

                    adjustPopupPosition =
                        true

                    iterator.remove()
                }
            }

            /*
             * Recalculate every remaining notification using its
             * actual height.
             */
            allNotifications.forEach { remainingNotify ->

                val changedY =
                    calculateTargetY(
                        remainingNotify,
                        allNotifications,
                        remainingNotify.popupIndex
                    )

                remainingNotify.setupMove(
                    changedY.toFloat()
                )
            }

            if (allNotifications.isEmpty()) {

                popups.remove(
                    notify.idAndPosition
                )
            }

            if (popups.isEmpty()) {

                // if there's nothing left, stop the timer.
                SwingActiveRender.remove(
                    animationUpdateHandler
                )
            }
        }
    }

    /**
     * Recalculates the positions of a notification stack after one
     * of its notifications changes size.
     *
     * This is used when dynamic notification height changes while
     * the notification is already visible.
     */
    fun relayoutPopupStack(
        notify: NotifyType<*>
    ) {

        synchronized(popups) {

            val allNotifications =
                popups[notify.idAndPosition]
                    ?: return

            /*
             * A notification calls refresh() from its constructor,
             * before it has been added to the popup stack.
             *
             * In that situation there is nothing to relayout.
             *
             * Without this check, creating notification #2/#3 can
             * unexpectedly reposition the notifications that are
             * already visible.
             */
            if (
                allNotifications.none {
                    it === notify
                }
            ) {
                return
            }

            /*
             * Cancel existing stack movement before creating the
             * replacement movement tweens.
             */
            allNotifications.forEach {
                it.cancelMove()
            }

            allNotifications.forEach { remainingNotify ->

                val changedY =
                    calculateTargetY(
                        remainingNotify,
                        allNotifications,
                        remainingNotify.popupIndex
                    )

                remainingNotify.setupMove(
                    changedY.toFloat()
                )
            }
        }
    }

    /**
     * Controls which positions will grow the notification stack
     * downwards.
     *
     * @return true to grow downwards
     */
    fun growDown(
        notify: NotifyType<*>
    ): Boolean {

        return when (
            notify.notification.position
        ) {

            Position.TOP_LEFT,
            Position.TOP,
            Position.TOP_RIGHT,
            Position.CENTER ->
                true

            else ->
                false
        }
    }

    /**
     * Determines whether a notification should be displayed immediately.
     *
     * @return true if the notification should be shown now.
     *         false if it was placed into the waiting queue.
     */
    fun requestShow(
        notification: Notify
    ): Boolean {

        /*
         * Normal STACK mode does not use the queue.
         */
        if (
            Notify.DISPLAY_MODE !=
            Notify.NotificationDisplayMode.ONE_AT_A_TIME
        ) {
            return true
        }

        val key =
            getNotificationQueueKey(
                notification
            )

        notification.notificationQueueKey =
            key

        synchronized(popups) {

            val activeNotifications =
                popups[key]

            val queue =
                queuedNotifications[key]

            val hasActiveNotification =
                !activeNotifications.isNullOrEmpty()

            /*
             * This is the notification that showNextQueued() is
             * currently trying to display.
             *
             * Remove it from the queue and allow it through.
             */
            if (
                !hasActiveNotification &&
                queue != null &&
                queue.isNotEmpty() &&
                queue[0] === notification
            ) {

                queue.removeAt(0)

                /*
                 * Preserve the sound suppression decision that was made when
                 * this notification was waiting in the queue.
                 */
                notification.suppressSound =
                    silentQueuedNotifications[key]
                        ?.contains(notification) == true

                if (queue.isEmpty()) {

                    queuedNotifications.remove(
                        key
                    )
                }

                return true
            }

            /*
             * If another notification is active, this one must wait.
             */
            if (hasActiveNotification) {

                val waiting =
                    queuedNotifications.getOrPut(
                        key
                    ) {
                        ArrayList(4)
                    }

                /*
                 * Don't insert the same Notify object into the queue
                 * more than once.
                 */
                if (
                    waiting.none {
                        it === notification
                    }
                ) {

                    waiting.add(
                        notification
                    )
                }

                return false
            }

            /*
             * No active notification exists, but there are already
             * notifications waiting. Preserve FIFO ordering.
             */
            if (
                !queue.isNullOrEmpty()
            ) {

                if (
                    queue.none {
                        it === notification
                    }
                ) {

                    queue.add(
                        notification
                    )
                }

                return false
            }

            /*
             * Nothing is active and nothing is waiting.
             * This notification can be shown immediately.
             */
            return true
        }
    }

    /**
     * Removes a notification from the waiting queue without displaying it.
     *
     * This is used when Notify.close() is called on a notification that
     * has not yet been shown.
     */
    fun removeQueuedNotification(
        notification: Notify
    ) {

        val key =
            notification.notificationQueueKey
                ?: return

        synchronized(popups) {

            val queue =
                queuedNotifications[key]

            if (queue != null) {

                val iterator =
                    queue.iterator()

                while (iterator.hasNext()) {

                    if (
                        iterator.next() === notification
                    ) {

                        iterator.remove()
                        break
                    }
                }

                if (queue.isEmpty()) {

                    queuedNotifications.remove(
                        key
                    )
                }
            }

            /*
             * It may also have been marked silent because it was part
             * of a manually-dismissed notification sequence.
             */
            val silentSet =
                silentQueuedNotifications[key]

            silentSet?.remove(
                notification
            )

            if (
                silentSet != null &&
                silentSet.isEmpty()
            ) {

                silentQueuedNotifications.remove(
                    key
                )
            }
        }

        notification.suppressSound =
            false

        notification.notificationQueueKey =
            null
    }

    /**
     * Displays the next waiting notification for the same
     * notification stream.
     */
    fun showNextQueued(
        closedNotification: Notify
    ) {

        if (
            Notify.DISPLAY_MODE !=
            Notify.NotificationDisplayMode.ONE_AT_A_TIME
        ) {
            return
        }

        val key =
            closedNotification.notificationQueueKey
                ?: return

        val nextNotification =
            synchronized(popups) {

                /*
                 * This notification has now completely finished.
                 * Remove it from the silent set, if applicable.
                 */
                val silentSet =
                    silentQueuedNotifications[key]

                silentSet?.remove(
                    closedNotification
                )

                if (
                    silentSet != null &&
                    silentSet.isEmpty()
                ) {

                    silentQueuedNotifications.remove(
                        key
                    )
                }

                queuedNotifications[key]
                    ?.firstOrNull()
            }

        if (nextNotification != null) {

            nextNotification.show()
        }
    }

    /**
     * Calculates the target Y coordinate for a notification.
     *
     * The FIRST notification in a stack establishes the anchor.
     * Every subsequent notification is offset from that first
     * notification by the cumulative heights of the notifications
     * before it plus the configured spacer.
     *
     * This is especially important for CENTER:
     *
     *   first notification = centered
     *   subsequent notifications = grow downward
     *
     * Using each notification's own center would cause unequal gaps
     * when notification heights differ.
     */
    private fun calculateTargetY(
        notify: NotifyType<*>,
        allNotifications: List<NotifyType<*>>,
        index: Int
    ): Int {

        /*
         * The first notification establishes the stack anchor.
         *
         * anchorY is the TOP edge of the first notification, regardless
         * of whether the stack grows upward or downward.
         */
        val stackAnchorY =
            if (allNotifications.isNotEmpty()) {

                allNotifications[0]
                    .anchorY

            } else {

                notify.anchorY
            }

        /*
         * The first notification always remains exactly at the anchor.
         */
        if (index == 0) {
            return stackAnchorY
        }

        return if (growDown(notify)) {

            /*
             * TOP positions:
             *
             * Each notification is placed below all notifications
             * before it.
             */
            var offset = 0

            for (i in 0 until index) {

                offset +=
                    allNotifications[i]
                        .notification
                        .notificationHeight

                offset +=
                    Notify.SPACER
            }

            stackAnchorY +
                offset

        } else {

            /*
             * BOTTOM positions:
             *
             * Each notification is placed ABOVE the previous one.
             *
             * The current notification's OWN height must be included
             * because its TOP edge needs to end above the previous
             * notification by SPACER pixels.
             *
             * During normal stack operation the current notification
             * is already in allNotifications. During initial insertion
             * it is not yet present, so use notify.notificationHeight
             * for that final/current entry.
             */
            var offset = 0

            for (i in 1..index) {

                val notificationHeight =
                    if (i < allNotifications.size) {

                        allNotifications[i]
                            .notification
                            .notificationHeight

                    } else {

                        notify.notification.notificationHeight
                    }

                offset +=
                    notificationHeight

                offset +=
                    Notify.SPACER
            }

            stackAnchorY -
                offset
        }
    }

    /**
     * Marks all notifications that are currently waiting in a
     * ONE_AT_A_TIME queue as silent.
     *
     * This is only called when the currently-visible notification was
     * explicitly closed by the user.
     */
    fun markUserClosed(
        notification: Notify
    ) {

        if (
            Notify.DISPLAY_MODE !=
            Notify.NotificationDisplayMode.ONE_AT_A_TIME
        ) {
            return
        }

        val key =
            notification.notificationQueueKey
                ?: return

        synchronized(popups) {

            val queue =
                queuedNotifications[key]
                    ?: return

            if (queue.isEmpty()) {
                return
            }

            val silentSet =
                silentQueuedNotifications.getOrPut(
                    key
                ) {
                    mutableSetOf()
                }

            silentSet.addAll(
                queue
            )
        }
    }
}