package frost.notify

import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

internal class DesktopMouseAdapter : MouseAdapter() {

    override fun mousePressed(e: MouseEvent) {

        val notifyCanvas = e.source as DesktopNotify

        /*
         * Pass the local notification coordinates to DesktopNotify.
         *
         * DesktopNotify.onClick() is responsible for determining
         * whether this was:
         *
         *   - the close button
         *   - the clickable filename
         *   - anywhere else in the notification
         */
        notifyCanvas.onClick(
            e.x,
            e.y
        )
    }

    override fun mouseMoved(e: MouseEvent) {

        val notifyCanvas = e.source as DesktopNotify

        /*
         * Keep the mouse position available to DesktopNotify for
         * hover rendering and update the cursor based on whether
         * the mouse is actually over the filename hyperlink.
         */
        notifyCanvas.updateCursor(
            e.x,
            e.y
        )
    }

    override fun mouseExited(e: MouseEvent) {

        val notifyCanvas = e.source as DesktopNotify

        /*
         * Always restore the normal cursor when the mouse leaves
         * the notification.
         */
        notifyCanvas.cursor =
            Cursor.getDefaultCursor()
    }
}