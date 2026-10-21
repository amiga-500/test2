package frost.notify

import dorkbox.propertyLoader.Property
import dorkbox.util.ImageUtil
import dorkbox.util.LocationResolver
import dorkbox.util.SwingUtil
import java.awt.Color
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.ref.SoftReference
import javax.imageio.ImageIO
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent
import javax.swing.ImageIcon
import javax.swing.JFrame
import java.util.concurrent.Executors

@Suppress("unused", "MemberVisibilityCanBePrivate")
class Notify private constructor() {

    enum class NotificationDisplayMode {
        STACK,
        ONE_AT_A_TIME
    }

    companion object {

        const val MAX_NOTIFICATION_HEIGHT = 240

        /**
         * The width of a notification.
         */
        @Property
        var WIDTH = 420

        /**
         * The height of a notification.
         */
        @Property
        var HEIGHT = 128

        /**
         * The gap in pixels between stacked notifications.
         *
         * 0 = notification windows touch
         *
         * Default is 2.
         */
        @Property
        @JvmField
        var SPACER = 2

        /**
         * The distance in pixels between a notification and the
         * edge of the screen/application.
         *
         * For example, with BOTTOM_RIGHT this controls the distance
         * from both the right and bottom edges.
         *
         * For left/right/top/bottom aligned positions, the margin
         * applies to the corresponding screen edges.
         *
         * Default is 2.
         */
        @Property
        @JvmField
        var MARGIN = 2

        /**
         * Controls how multiple notifications are displayed.
         *
         * STACK:
         *     All notifications are shown and stacked normally.
         *
         * ONE_AT_A_TIME:
         *     Only one notification is shown for each notification
         *     position/stream. Additional notifications wait in FIFO order.
         *
         * Default is STACK.
         */
        @JvmField
        var DISPLAY_MODE = NotificationDisplayMode.STACK

        /**
         * This is the title font used by a notification.
         */
        @Property
        var TITLE_TEXT_FONT = "Dialog BOLD 15"

        /**
         * This is the main text font used by a notification.
         */
        @Property
        var MAIN_TEXT_FONT = "Dialog PLAIN 13"

        /**
         * How long notification stacks take to reposition
         * after a notification is removed.
         *
         * Value is in seconds.
         *
         * Default is 0.3 seconds.
         */
        @Property
        @JvmField
        var MOVE_DURATION = 0.3f

        /**
         * Location of the dialog image resources.
         *
         * By default, they must be in the 'resources' directory
         * relative to the application.
         */
        @Property
        var IMAGE_PATH = "resources"

        /**
         * Plays one of Notify's built-in sounds asynchronously.
         *
         * This is exposed as a static Java API so callers can use:
         *
         *     Notify.playSound(NotifySound.COZY_NUDGE);
         */
        @JvmStatic
        fun playSound(
            sound: NotifySound
        ) {
            SoundPlayer.play(
                sound,
                null
            )
        }

        /**
         * Plays an external sound file asynchronously.
         */
        @JvmStatic
        fun playSound(
            file: File
        ) {
            SoundPlayer.play(
                NotifySound.NONE,
                file
            )
        }

        /**
         * Plays an external sound file specified by path asynchronously.
         */
        @JvmStatic
        fun playSound(
            path: String
        ) {
            SoundPlayer.play(
                NotifySound.NONE,
                File(path)
            )
        }

        /**
         * Builder pattern to create the notification.
         */
        fun create(): Notify {
            return Notify()
        }
    }

    /*
     * -------------------------------------------------------------
     * NOTIFICATION STATE
     * -------------------------------------------------------------
     */
    @Volatile
    internal var notificationHeight = HEIGHT

    @Volatile
    private var selectedSound =
        NotifySound.NONE

    @Volatile
    private var customSoundFile: File? = null

    @Volatile
    internal var notifyPopup: NotifyType<*>? = null

    /*
     * When true, this notification's sound is suppressed when it is shown.
     *
     * This is used by ONE_AT_A_TIME mode when the previous notification
     * was manually closed by the user and this notification was already
     * waiting in the queue.
     */
    @Volatile
    internal var suppressSound = false

    /*
     * Identifies the notification stream used by the one-at-a-time
     * notification queue.
     */
    @Volatile
    internal var notificationQueueKey: String? = null

    @Volatile
    var title = "Notification"
        set(value) {
            field = value
            notifyPopup?.refresh()
        }

    /**
     * Controls whether the notification title is centered
     * within the available title/content area.
     *
     * Default is false (left-aligned).
     */
    @Volatile
    var titleCenter = false
        set(value) {
            field = value
            notifyPopup?.refresh()
        }

    /**
     * Controls whether the notification body text is centered.
     *
     * Default is false (left-aligned).
     */
    @Volatile
    var bodyTextCenter = false
        set(value) {
            field = value
            notifyPopup?.refresh()
        }

    @Volatile
    var text = "Lorem ipsum"
        set(value) {
            field = value
            notifyPopup?.refresh()
        }

    @Volatile
    var theme = Theme.defaultDark
        set(value) {
            field = value
            notifyPopup?.refresh()
        }

    /**
     * Accent color used for the notification and progress indicator.
     */
    @Volatile
    var accentColor = Color(0x34C759)
        set(value) {
            field = value
            notifyPopup?.refresh()
        }

    @Volatile
    internal var panelColor: Color? = null

    /**
     * Optional per-notification progress bar color.
     *
     * null means use the color supplied by the current Theme.
     */
    @Volatile
    var progressBarColor: Color? = null
        set(value) {
            field = value
            notifyPopup?.refresh()
        }

    @Volatile
    var position = Position.BOTTOM_RIGHT

    @Volatile
    var hideAfterDurationInMillis = 0

    /**
     * Is the close button in the top-right corner visible?
     *
     * Default is true.
     */
    @Volatile
    var hideCloseButton = true
        set(value) {
            field = value
            notifyPopup?.refresh()
        }

    @Volatile
    var screen = Short.MIN_VALUE.toInt()

    @Volatile
    var image: ImageIcon? = null
        set(value) {
            field = value
            notifyPopup?.refresh()
        }

    /**
     * Controls whether the notification gets an internal shadow.
     *
     * Default is true.
     */
    @Volatile
    var showShadow = true
        set(value) {
            field = value
            notifyPopup?.refresh()
        }

    /**
     * Controls the darkness of the notification's drop shadow.
     *
     * 0   = invisible
     * 100 = fully opaque black
     *
     * Ignored when showShadow is false.
     *
     * Default is 35.
     */
    @Volatile
    var shadowOpacity = 35
        set(value) {
            field = value.coerceIn(0, 100)
            notifyPopup?.refresh()
        }

    /**
     * Controls the thickness of the notification's drop shadow.
     *
     * 0  = no visible shadow thickness
     * 10 = maximum shadow thickness
     *
     * Ignored when showShadow is false.
     *
     * Default is 3.
     */
    @Volatile
    var shadowThickness = 3
        set(value) {
            field = value.coerceIn(0, 10)
            notifyPopup?.refresh()
        }

    /**
     * Enables per-pixel transparent corners around the rounded
     * notification card when the platform supports it.
     *
     * Default is true.
     */
    @Volatile
    var transparentBackground = true
        set(value) {
            field = value
            notifyPopup?.refresh()
        }

    /**
     * Optional per-notification title text color.
     *
     * null means use the color supplied by the current Theme.
     */
    @Volatile
    var titleTextColor: Color? = null
        set(value) {
            field = value
            notifyPopup?.refresh()
        }

    /**
     * Centers the notification title within the available
     * title/content area.
     */
    fun titleCenter(
        center: Boolean
    ): Notify {

        titleCenter =
            center

        notifyPopup?.refresh()

        return this
    }

    /**
     * Enables or disables centering of the notification body text.
     */
    fun bodyTextCenter(
        center: Boolean
    ): Notify {

        bodyTextCenter =
            center

        notifyPopup?.refresh()

        return this
    }

    /**
     * Optional per-notification body text color.
     *
     * null means use the color supplied by the current Theme.
     */
    @Volatile
    var bodyTextColor: Color? = null
        set(value) {
            field = value
            notifyPopup?.refresh()
        }

    /*
     * -------------------------------------------------------------
     * CALLBACKS
     * -------------------------------------------------------------
     */

    /**
     * Called when the notification is closed, either via the close
     * button or via close().
     */
    @Volatile
    var onCloseAction: Notify.()->Unit = {}

    /**
     * Called when the general notification area is clicked.
     *
     * This is NOT called for:
     *
     *   - the close button
     *   - the clickable filename/link
     */
    @Volatile
    var onClickAction: Notify.()->Unit = {}

    /**
     * Called only when the actual clickable filename/link is clicked.
     */
    @Volatile
    var onLinkClickAction: Notify.()->Unit = {}

    /**
     * When true, clicking anywhere other than the close button or
     * clickable filename closes the notification.
     */
    @Volatile
    var closeOnBodyClick = true

    /*
     * -------------------------------------------------------------
     * ANIMATION
     * -------------------------------------------------------------
     */

    @Volatile
    var shakeDurationInMillis = 0

    @Volatile
    var shakeAmplitude = 0

    /**
     * Controls whether this notification slides into view when shown.
     *
     * Default is false.
     */
    @Volatile
    var animateIn = false

    /**
     * Controls whether this notification slides out of view when closed.
     *
     * Default is false.
     */
    @Volatile
    var animateOut = false

    /**
     * Duration in seconds used for the notification's slide-in
     * and slide-out animations.
     *
     * Default is 0.30 seconds.
     */
    @Volatile
    var animationDuration = 0.30f

    /*
     * -------------------------------------------------------------
     * IMAGE / SOUND / BUILDER API
     * -------------------------------------------------------------
     */

    /**
     * Select one of Notify's built-in notification sounds.
     */
    fun sound(
        sound: NotifySound?
    ): Notify {
        if (sound == null) {
            selectedSound = NotifySound.NONE
        } else {
            selectedSound = sound
        }
        customSoundFile = null

        return this
    }

    /**
     * Use an external sound file instead of a built-in sound.
     */
    fun soundFile(
        file: File?
    ): Notify {

        customSoundFile = file
        selectedSound = NotifySound.NONE

        return this
    }

    /**
     * Use an external sound file specified by path.
     */
    fun soundFile(
        path: String?
    ): Notify {

        return soundFile(
            File(path)
        )
    }

    /**
     * Specifies the main text.
     */
    fun text(
        text: String
    ): Notify {

        this.text = text

        return this
    }

    /**
     * Specifies the title.
     */
    fun title(
        title: String
    ): Notify {

        this.title = title

        return this
    }

    /**
     * Specifies the image.
     */
    fun image(
        image: Image
    ): Notify {

        // we only use 48x48 pixel images
        val width =
            image.getWidth(null)

        val height =
            image.getHeight(null)

        var bufferedImage =
            ImageUtil.getBufferedImage(
                image
            )

        // resize image, keeping aspect ratio
        bufferedImage =
            if (width > height) {

                ImageUtil.resizeImage(
                    bufferedImage,
                    48,
                    -1
                )

            } else {

                ImageUtil.resizeImage(
                    bufferedImage,
                    -1,
                    48
                )
            }

        // clamp to max dimension of 48
        bufferedImage =
            ImageUtil.clampMaxImageSize(
                bufferedImage,
                48
            )

        // center image
        bufferedImage =
            ImageUtil.getSquareBufferedImage(
                bufferedImage
            )

        this.image =
            ImageIcon(bufferedImage)

        return this
    }

    /**
     * Registers the callback used when the filename/link is clicked.
     */
    fun onLinkClickAction(
        onAction: Notify.()->Unit
    ): Notify {

        onLinkClickAction = onAction

        return this
    }

    /**
     * Determines whether clicking the general notification body
     * should close the notification.
     */
    fun closeOnBodyClick(
        close: Boolean
    ): Notify {

        closeOnBodyClick = close

        return this
    }

    /**
     * Specifies the position of the notification on screen.
     */
    fun position(
        position: Position
    ): Notify {

        this.position = position

        return this
    }

    /**
     * Specifies the duration that the notification should show,
     * after which it will be hidden.
     *
     * 0 means to show forever.
     */
    fun hideAfter(
        durationInMillis: Long
    ): Notify {

        require(
            durationInMillis >= 0
        ) {
            "duration must be 0 or greater"
        }

        require(
            durationInMillis < Integer.MAX_VALUE
        ) {
            "duration must be less than Integer.MAX"
        }

        hideAfterDurationInMillis =
            durationInMillis.toInt()

        return this
    }

    /**
     * Specifies the duration that the notification should show,
     * after which it will be hidden.
     */
    fun hideAfter(
        durationInMillis: Int
    ): Notify {

        require(
            durationInMillis >= 0
        ) {
            "duration must be 0 or greater"
        }

        hideAfterDurationInMillis =
            durationInMillis

        return this
    }

    /**
     * Called when the notification is closed.
     */
    fun onCloseAction(
        onAction: Notify.()->Unit
    ): Notify {

        onCloseAction = onAction

        return this
    }

    /**
     * Called when the general notification area is clicked.
     */
    fun onClickAction(
        onAction: Notify.()->Unit
    ): Notify {

        onClickAction = onAction

        return this
    }

    /**
     * Specifies the theme.
     */
    fun theme(
        theme: Theme
    ): Notify {

        this.theme = theme
        notifyPopup?.refresh()

        return this
    }

    /**
     * Specifies the accent color.
     */
    fun accentColor(
        color: Color
    ): Notify {

        this.accentColor = color
        notifyPopup?.refresh()

        return this
    }

    fun panelColor(color: Color?): Notify {
        panelColor = color
        notifyPopup?.refresh()
        return this
    }

    /**
     * Sets the progress bar color for this notification.
     *
     * Pass null to return to the Theme's progress bar color.
     */
    fun progressBarColor(
        color: Color?
    ): Notify {

        progressBarColor =
            color

        notifyPopup?.refresh()

        return this
    }

    /**
     * Enables or disables the internal notification shadow.
     */
    fun showShadow(
        show: Boolean
    ): Notify {

        showShadow = show
        notifyPopup?.refresh()

        return this
    }

    /**
     * Sets the internal shadow opacity.
     *
     * 0   = invisible
     * 100 = fully opaque black
     */
    fun shadowOpacity(
        opacity: Int
    ): Notify {

        shadowOpacity =
            opacity.coerceIn(0, 100)

        notifyPopup?.refresh()

        return this
    }

    /**
     * Sets the drop shadow thickness.
     *
     * 0  = no visible shadow thickness
     * 10 = maximum shadow thickness
     */
    fun shadowThickness(
        thickness: Int
    ): Notify {

        shadowThickness =
            thickness.coerceIn(0, 10)

        notifyPopup?.refresh()

        return this
    }

    /**
     * Enables or disables per-pixel transparent corners.
     *
     * If the platform does not support per-pixel transparency,
     * Notify automatically falls back to the normal opaque
     * notification.
     */
    fun transparentBackground(
        enable: Boolean
    ): Notify {

        transparentBackground =
            enable

        notifyPopup?.refresh()

        return this
    }

    /**
     * Sets the title text color for this notification.
     *
     * Pass null to return to the Theme's title color.
     */
    fun titleTextColor(
        color: Color?
    ): Notify {

        titleTextColor =
            color

        notifyPopup?.refresh()

        return this
    }

    /**
     * Sets the body text color for this notification.
     *
     * Pass null to return to the Theme's body color.
     */
    fun bodyTextColor(
        color: Color?
    ): Notify {

        bodyTextColor =
            color

        notifyPopup?.refresh()

        return this
    }

    /**
     * Shows or hides the close button.
     *
     * Default is hidden.
     */
    fun showCloseButton(
        show: Boolean
    ): Notify {

        hideCloseButton = !show
        notifyPopup?.refresh()

        return this
    }

    /**
     * Shows the notification.
     */
    fun show() {

        val notify = this@Notify

        // must be done on the Swing EDT
        SwingUtil.invokeAndWaitQuietly {

            if (notify.notifyPopup != null) {
                return@invokeAndWaitQuietly
            }

            /*
             * In ONE_AT_A_TIME mode, determine whether this notification
             * should be displayed immediately or placed into the waiting queue.
             */
            if (
                !LAFUtil.requestShow(
                    notify
                )
            ) {
                return@invokeAndWaitQuietly
            }

            val shakeDuration =
                notify.shakeDurationInMillis

            val shakeAmp =
                notify.shakeAmplitude

            val notifyPopup =
                DesktopNotify(notify)

            /*
             * Store the popup before making it visible.
             */
            notify.notifyPopup =
                notifyPopup

            notifyPopup.setVisible(
                true
            )

            /*
             * A queued notification may have its sound intentionally suppressed
             * because the user manually closed the previous notification while
             * this notification was already waiting.
             */
            val playSound =
                !notify.suppressSound

            notify.suppressSound =
                false

            if (playSound) {

                SoundPlayer.play(
                    notify.selectedSound,
                    notify.customSoundFile
                )
            }

            if (shakeDuration > 0) {

                notifyPopup.shake(
                    shakeDuration,
                    shakeAmp
                )
            }
        }
    }

    /**
     * "Shakes" the notification to bring user attention to it.
     */
    fun shake(
        durationInMillis: Int = 2000,
        amplitude: Int = 4
    ): Notify {

        shakeDurationInMillis =
            durationInMillis

        shakeAmplitude =
            amplitude

        val popup =
            notifyPopup

        if (popup !== null) {

            // must be done on the Swing EDT
            SwingUtil.invokeLater {

                popup.shake(
                    durationInMillis,
                    amplitude
                )
            }
        }

        return this
    }

    /**
     * Enables or disables the slide-in animation.
     */
    fun animateIn(
        enable: Boolean
    ): Notify {

        animateIn =
            enable

        return this
    }

    /**
     * Enables or disables the slide-out animation.
     */
    fun animateOut(
        enable: Boolean
    ): Notify {

        animateOut =
            enable

        return this
    }

    /**
     * Sets the slide-in/slide-out animation duration in seconds.
     *
     * Example:
     *
     *     .animationDuration(0.30f)
     */
    fun animationDuration(
        durationInSeconds: Float
    ): Notify {

        require(
            durationInSeconds > 0f
        ) {
            "animation duration must be greater than 0"
        }

        animationDuration =
            durationInSeconds

        return this
    }

    /**
     * Closes the notification.
     */
    fun close() {

        val popup =
            notifyPopup

        if (popup !== null) {

            // must be done on the Swing EDT
            SwingUtil.invokeLater {
                popup.close()
            }

        } else {

            /*
             * This notification has not been shown yet and may be
             * waiting in the ONE_AT_A_TIME queue.
             */
            LAFUtil.removeQueuedNotification(
                this
            )
        }
    }

    /**
     * Specifies which screen to display on.
     */
    fun setScreen(
        screenNumber: Int
    ): Notify {

        this.screen =
            screenNumber

        return this
    }

    /**
     * Closes the notification as a direct result of user interaction.
     *
     * In ONE_AT_A_TIME mode this also suppresses the sounds of
     * notifications that are already waiting in the queue.
     */
    fun closeByUserAction() {

        LAFUtil.markUserClosed(
            this
        )

        val popup =
            notifyPopup

        if (popup != null) {

            SwingUtil.invokeLater {
                popup.close()
            }
        }
    }

    /**
     * Invokes the filename/link click callback on the Swing EDT.
     */
    internal fun onLinkClickAction() {

        SwingUtil.invokeLater {

            this.onLinkClickAction
                .invoke(this)
        }
    }

    /**
     * Handles notification close operations.
     *
     * This can happen from:
     *
     *   1. close button
     *   2. expiration
     *   3. manual close through the notification infrastructure
     */
    internal fun onClose() {

        val popup =
            notifyPopup

        if (popup != null) {

            popup.close()
        }
    }

    /**
     * Called by DesktopNotify after the notification has
     * actually finished closing and has been removed from the stack.
     */
    internal fun onCloseCompleted() {

        if (notifyPopup == null) {
            return
        }

        notifyPopup =
            null

        /*
         * The current notification has now completely closed and
         * has already been removed from the popup stack.
         *
         * Start the next queued notification, if one exists.
         */
        LAFUtil.showNextQueued(
            this
        )

        /*
         * Dispatch the close callback on the Swing EDT.
         */
        SwingUtil.invokeLater {

            this.onCloseAction
                .invoke(this)
        }

        notificationQueueKey =
            null
    }

    /**
     * Invokes the general click callback on the Swing EDT.
     */
    internal fun onClickAction() {

        SwingUtil.invokeLater {

            this.onClickAction
                .invoke(this)
        }
    }

    object SoundPlayer {

        private val executor =
            Executors.newCachedThreadPool { runnable ->

                Thread(
                    runnable,
                    "Notify-Sound"
                ).apply {
                    isDaemon = true
                }
            }

        @JvmStatic
        fun play(
            sound: NotifySound,
            customFile: File?
        ) {

            /*
             * Nothing to play.
             */
            if (sound == NotifySound.NONE && customFile == null) {
                return
            }

            /*
             * Sound playback is intentionally asynchronous so that
             * notification display is never blocked by audio playback.
             */
            executor.execute {

                try {

                    if (customFile != null) {

                        playFileInternal(customFile)

                    } else {

                        val resourceName =
                            sound.resourceName
                                ?: return@execute

                        val input =
                            LocationResolver.getResourceAsStream(
                                resourceName
                            )
                                ?: return@execute

                        input.use {
                            playResourceInternal(it)
                        }
                    }

                } catch (ignored: Exception) {

                    /*
                     * A sound failure must never prevent the
                     * notification itself from being displayed,
                     * updated or closed.
                     */
                }
            }
        }

        @JvmStatic
        fun play(
            sound: NotifySound
        ) {
            play(sound, null)
        }

        @JvmStatic
        fun playFile(
            file: File
        ) {
            play(
                NotifySound.NONE,
                file
            )
        }

        @JvmStatic
        fun playFile(
            path: String
        ) {
            playFile(File(path))
        }

        private fun playFileInternal(
            file: File
        ) {

            AudioSystem
                .getAudioInputStream(file)
                .use { audioInputStream ->

                    playAudio(
                        audioInputStream
                    )
                }
        }

        private fun playResourceInternal(
            input: InputStream
        ) {

            BufferedInputStream(input).use { bufferedInput ->

                AudioSystem
                    .getAudioInputStream(bufferedInput)
                    .use { audioInputStream ->

                    playAudio(
                        audioInputStream
                    )
                }
            }
        }

        private fun playAudio(
            audioInputStream: AudioInputStream
        ) {

            val clip =
                AudioSystem.getClip()

            try {

                clip.open(
                    audioInputStream
                )

                clip.addLineListener { event ->

                    if (event.type == LineEvent.Type.STOP) {
                        clip.close()
                    }
                }

                clip.start()

            } catch (e: Exception) {

                clip.close()

                throw e
            }
        }
    }
}