package frost.notify

import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

internal class DesktopWindowCloseAdapter : WindowAdapter() {

    override fun windowClosing(e: WindowEvent) {

        val source =
            e.window as DesktopNotify

        source.notification.close()
    }

    override fun windowLostFocus(e: WindowEvent) {

        val source =
            e.window as DesktopNotify

        // These don't work reliably on this platform:
        // source.toFront()
        // source.requestFocus()
        // source.requestFocusInWindow()

        /*
         * Reassert always-on-top when the notification loses focus.
         *
         * This is used as a z-order workaround for the native
         * DesktopNotify JWindow.
         */
        source.isAlwaysOnTop = false
        source.isAlwaysOnTop = true
    }
}

