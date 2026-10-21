package frost.notify

import dorkbox.util.FontUtil
import java.awt.Color
import java.awt.Font

/**
 * Settings available to change the theme.
 */
class Theme {

    companion object {

        val defaultLight: Theme by lazy {
            Theme(
                Notify.TITLE_TEXT_FONT,
                Notify.MAIN_TEXT_FONT,
                Color(0xEEEEEE),
                Color.BLACK,
                Color.BLACK,
                Color(0x8A94A6),
                Color(0x038803)
            )
        }

        val defaultDark: Theme by lazy {
            Theme(
                Notify.TITLE_TEXT_FONT,
                Notify.MAIN_TEXT_FONT,
                Color(0x1B1F24),
                Color(0xF5F7FA),
                Color(0xCDD3DB),
                Color(0x8C96A5),
                Color(0x038803)
            )
        }
    }

    val panel_BG: Color
    val titleText_FG: Color
    val mainText_FG: Color
    val closeX_FG: Color
    val progress_FG: Color

    val titleTextFont: Font
    val mainTextFont: Font

    /**
     * Creates a theme using the specified fonts and a standard
     * light/dark color scheme.
     */
    constructor(
        titleTextFont: String,
        mainTextFont: String,
        isDarkTheme: Boolean
    ) {

        this.titleTextFont =
            FontUtil.parseFont(titleTextFont)

        this.mainTextFont =
            FontUtil.parseFont(mainTextFont)

        if (isDarkTheme) {

            this.panel_BG =
                Color(0x1B1F24)

            this.titleText_FG =
                Color(0xF5F7FA)

            this.mainText_FG =
                Color(0xCDD3DB)

            this.closeX_FG =
                Color(0x8C96A5)

            this.progress_FG =
                Color(0x038803)

        } else {

            this.panel_BG =
                Color(0xEEEEEE)

            this.titleText_FG =
                Color.BLACK

            this.mainText_FG =
                Color.BLACK

            this.closeX_FG =
                Color(0x8A94A6)

            this.progress_FG =
                Color(0x038803)
        }
    }

    /**
     * Creates a theme using explicitly supplied colors.
     */
    constructor(
        titleTextFont: String,
        mainTextFont: String,
        panel_BG: Color,
        titleText_FG: Color,
        mainText_FG: Color,
        closeX_FG: Color,
        progress_FG: Color
    ) {

        this.titleTextFont =
            FontUtil.parseFont(titleTextFont)

        this.mainTextFont =
            FontUtil.parseFont(mainTextFont)

        this.panel_BG =
            panel_BG

        this.titleText_FG =
            titleText_FG

        this.mainText_FG =
            mainText_FG

        this.closeX_FG =
            closeX_FG

        this.progress_FG =
            progress_FG
    }
}