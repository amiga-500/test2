package frost.notify

enum class NotifySound(
    val displayName: String,
    val resourceName: String?
) {

    NONE(
        "None",
        null
    ),

    DING(
        "Ding!",
        "ding.wav"
    ),

    COZY_NUDGE(
        "Cozy Nudge",
        "cozy-nudge.wav"
    ),

    BRIGHT_SUCCESS(
        "Bright Success",
        "bright-success.wav"
    ),

    PLAYFUL(
        "Playful",
        "playful.wav"
    );

    companion object {
        private val BY_DISPLAY_NAME = entries.associateBy { it.displayName }

        @JvmStatic
        fun fromDisplayName(name: String?): NotifySound {
            return BY_DISPLAY_NAME[name] ?: NONE
        }
    }
}