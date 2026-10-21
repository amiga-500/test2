package frost.notify

import frost.swingActiveRender.SwingActiveRender
import dorkbox.tweenEngine.Tween
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.font.TextLayout
import java.awt.FontMetrics
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.Locale
import javax.swing.ImageIcon

internal interface NotifyType<T> {

    data class RenderedNotification(
        val image: BufferedImage,
        val linkBounds: List<Rectangle>,
        val height: Int
    )

    var linkBounds: List<Rectangle>

    fun isLinkAt(x: Int, y: Int): Boolean {
        return linkBounds.any { it.contains(x, y) }
    }

    companion object {

        internal val closeX = Notify.WIDTH - 18
        internal const val closeY = 2

        internal val X_1 = closeX + 5
        internal const val Y_2 = closeY + 11

        private const val CARD_X = 2
        private const val CARD_Y = 2
        private const val CORNER_RADIUS = 14

        private const val BODY_BOTTOM_PADDING = 18
        private const val RIGHT_TEXT_PADDING = 38

        private const val ICON_TILE_X_OFFSET = 17
        private const val ICON_TILE_SIZE = 50

        private const val ICON_CONTENT_X_OFFSET = 81
        private const val NO_ICON_CONTENT_X_OFFSET = 22

        private const val TITLE_BASELINE_OFFSET = 29
        private const val BODY_VERTICAL_CENTER_OFFSET = 4

        /**
         * The style state applied to one piece of text.
         *
         * link == true is the ONLY state that makes text clickable.
         */
        private data class TextStyle(
            val bold: Boolean = false,
            val italic: Boolean = false,
            val underline: Boolean = false,
            val link: Boolean = false,
            val center: Boolean = false,
            val color: Color? = null
        )

        /**
         * One Unicode code point together with its rendering style.
         *
         * Keeping one code point per object prevents us from accidentally
         * splitting UTF-16 surrogate pairs when wrapping/truncating text.
         */
        private data class StyledChar(
            val text: String,
            val style: TextStyle
        )

        /**
         * Saves the style that existed before entering an HTML tag.
         */
        private data class StyleFrame(
            val tag: String,
            val style: TextStyle
        )

        private data class MeasuredLine(
            val height: Int,
            val descent: Int
        )

        private fun pushStyle(
            stack: MutableList<StyleFrame>,
            tag: String,
            current: TextStyle,
            newStyle: TextStyle
        ): TextStyle {

            stack.add(
                StyleFrame(
                    tag,
                    current
                )
            )

            return newStyle
        }

        private fun popStyle(
            stack: MutableList<StyleFrame>,
            tag: String,
            current: TextStyle
        ): TextStyle {

            if (
                stack.isNotEmpty() &&
                stack.last().tag == tag
            ) {

                return stack
                    .removeAt(stack.lastIndex)
                    .style
            }

            /*
             * Ignore malformed/unbalanced closing tags rather than
             * damaging the currently active style.
             */
            return current
        }

        /**
         * Appends decoded text one Unicode code point at a time.
         */
        private fun appendDecodedText(
            destination: MutableList<StyledChar>,
            text: String,
            style: TextStyle
        ) {

            var offset = 0

            while (offset < text.length) {

                val codePoint =
                    text.codePointAt(offset)

                destination.add(
                    StyledChar(
                        String(
                            Character.toChars(codePoint)
                        ),
                        style
                    )
                )

                offset +=
                    Character.charCount(codePoint)
            }
        }

        /**
         * Decodes the entities that are useful for notification text.
         *
         * Supported examples:
         *
         *   &amp;
         *   &lt;
         *   &gt;
         *   &quot;
         *   &apos;
         *   &nbsp;
         *   &#39;
         *   &#123;
         *   &#x1F4A9;
         */
        private fun decodeHtmlEntities(
            text: String
        ): String {

            if (text.indexOf('&') < 0) {
                return text
            }

            val result =
                StringBuilder(text.length)

            var index = 0

            while (index < text.length) {

                if (text[index] != '&') {

                    result.append(text[index])
                    index++
                    continue
                }

                val semicolon =
                    text.indexOf(
                        ';',
                        index + 1
                    )

                if (semicolon < 0) {

                    result.append('&')
                    index++
                    continue
                }

                val entity =
                    text.substring(
                        index + 1,
                        semicolon
                    )

                val decoded =
                    decodeEntity(entity)

                if (decoded != null) {

                    result.append(decoded)
                    index =
                        semicolon + 1

                } else {

                    /*
                     * Unknown entity:
                     * preserve it literally.
                     */
                    result.append('&')
                    index++
                }
            }

            return result.toString()
        }

        private fun decodeEntity(
            entity: String
        ): String? {

            return when {

                entity.equals(
                    "amp",
                    ignoreCase = true
                ) -> "&"

                entity.equals(
                    "lt",
                    ignoreCase = true
                ) -> "<"

                entity.equals(
                    "gt",
                    ignoreCase = true
                ) -> ">"

                entity.equals(
                    "quot",
                    ignoreCase = true
                ) -> "\""

                entity.equals(
                    "apos",
                    ignoreCase = true
                ) -> "'"

                entity.equals(
                    "nbsp",
                    ignoreCase = true
                ) -> "\u00A0"

                entity.equals(
                    "#39",
                    ignoreCase = true
                ) -> "'"

                entity.startsWith(
                    "#x",
                    ignoreCase = true
                ) -> {

                    val value =
                        entity
                            .substring(2)
                            .toLongOrNull(16)

                    if (
                        value != null &&
                        value in 0L..0x10FFFFL
                    ) {

                        String(
                            Character.toChars(
                                value.toInt()
                            )
                        )

                    } else {
                        null
                    }
                }

                entity.startsWith("#") -> {

                    val value =
                        entity
                            .substring(1)
                            .toLongOrNull(10)

                    if (
                        value != null &&
                        value in 0L..0x10FFFFL
                    ) {

                        String(
                            Character.toChars(
                                value.toInt()
                            )
                        )

                    } else {
                        null
                    }
                }

                else -> null
            }
        }

        /**
         * Parses:
         *
         *   <color 0x34C759>
         *
         * and optionally:
         *
         *   <color 0xFF34C759>
         *
         * where the latter is interpreted as ARGB.
         */
        private fun parseColorTag(
            tagText: String
        ): Color? {

            val valueText =
                tagText
                    .substringAfter(
                        ' ',
                        ""
                    )
                    .trim()

            /*
             * Accept the normal HTML/CSS-style form:
             *
             *     <color #RRGGBB>
             *
             * Also accept the previous 0x form for compatibility:
             *
             *     <color 0xRRGGBB>
             *
             * Eight digits are also accepted as ARGB:
             *
             *     <color #AARRGGBB>
             *     <color 0xAARRGGBB>
             */
            val hex =
                when {

                    valueText.startsWith("#") ->
                        valueText.substring(1)

                    valueText.startsWith(
                        "0x",
                        ignoreCase = true
                    ) ->
                        valueText.substring(2)

                    else ->
                        return null
                }

            if (
                hex.length != 6 &&
                hex.length != 8
            ) {
                return null
            }

            val value =
                hex.toLongOrNull(16)
                    ?: return null

            return if (hex.length == 6) {

                Color(
                    value.toInt()
                )

            } else {

                Color(
                    value.toInt(),
                    true
                )
            }
        }

        /**
         * Parses the small HTML-like markup supported by Notify.
         *
         * Supported tags:
         *
         *   <html> ... </html> = optional wrapper for formatted notification text; it has no visual effect and is not required when using markup tags
         *   <b> ... </b>
         *   <i> ... </i>
         *   <u> ... </u> = visual underline ONLY
         *   <a> ... </a> = underline + clickable --> the click triggers: .onLinkClickAction(...)
         *   <center> ... </center>
         *   <color #RRGGBB> ... </color> OR <color 0xRRGGBB> ... </color>
         *   <br> OR <br/> OR <br />
         */
        private fun parseBodyText(
            text: String,
            defaultCenter: Boolean = false
        ): List<StyledChar> {

            val result =
                mutableListOf<StyledChar>()

            val stack =
                mutableListOf<StyleFrame>()

            var currentStyle =
                TextStyle(
                    center = defaultCenter
                )

            var index = 0

            while (index < text.length) {

                /*
                 * Ordinary text between tags.
                 */
                if (text[index] != '<') {

                    val nextTag =
                        text.indexOf(
                            '<',
                            index
                        )

                    val end =
                        if (nextTag >= 0) {
                            nextTag
                        } else {
                            text.length
                        }

                    val decoded =
                        decodeHtmlEntities(
                            text.substring(
                                index,
                                end
                            )
                        )

                    appendDecodedText(
                        result,
                        decoded,
                        currentStyle
                    )

                    index = end
                    continue
                }

                /*
                 * Look for the closing '>'.
                 */
                val closeBracket =
                    text.indexOf(
                        '>',
                        index + 1
                    )

                if (closeBracket < 0) {

                    /*
                     * No closing bracket means this is not a valid tag.
                     * Preserve the remainder as ordinary text.
                     */
                    val decoded =
                        decodeHtmlEntities(
                            text.substring(index)
                        )

                    appendDecodedText(
                        result,
                        decoded,
                        currentStyle
                    )

                    break
                }

                val originalTag =
                    text.substring(
                        index + 1,
                        closeBracket
                    )

                val normalizedTag =
                    originalTag
                        .trim()
                        .lowercase(Locale.ROOT)

                when {

                    /*
                     * Explicit line break.
                     */
                    normalizedTag == "br" ||
                    normalizedTag == "br/" ||
                    normalizedTag == "br /" -> {

                        result.add(
                            StyledChar(
                                "\n",
                                currentStyle
                            )
                        )
                    }

                    /*
                     * HTML root tag. It changes nothing visually.
                     */
                    normalizedTag == "html" -> {

                        currentStyle =
                            pushStyle(
                                stack,
                                "html",
                                currentStyle,
                                currentStyle
                            )
                    }

                    normalizedTag == "/html" -> {

                        currentStyle =
                            popStyle(
                                stack,
                                "html",
                                currentStyle
                            )
                    }

                    /*
                     * Bold.
                     */
                    normalizedTag == "b" -> {

                        currentStyle =
                            pushStyle(
                                stack,
                                "b",
                                currentStyle,
                                currentStyle.copy(
                                    bold = true
                                )
                            )
                    }

                    normalizedTag == "/b" -> {

                        currentStyle =
                            popStyle(
                                stack,
                                "b",
                                currentStyle
                            )
                    }

                    /*
                     * Italic.
                     */
                    normalizedTag == "i" -> {

                        currentStyle =
                            pushStyle(
                                stack,
                                "i",
                                currentStyle,
                                currentStyle.copy(
                                    italic = true
                                )
                            )
                    }

                    normalizedTag == "/i" -> {

                        currentStyle =
                            popStyle(
                                stack,
                                "i",
                                currentStyle
                            )
                    }

                    /*
                     * Underline ONLY.
                     *
                     * This does NOT make the text clickable.
                     */
                    normalizedTag == "u" -> {

                        currentStyle =
                            pushStyle(
                                stack,
                                "u",
                                currentStyle,
                                currentStyle.copy(
                                    underline = true
                                )
                            )
                    }

                    normalizedTag == "/u" -> {

                        currentStyle =
                            popStyle(
                                stack,
                                "u",
                                currentStyle
                            )
                    }

                    /*
                     * Clickable link.
                     *
                     * This is the ONLY tag that sets link=true.
                     */
                    normalizedTag == "a" -> {

                        currentStyle =
                            pushStyle(
                                stack,
                                "a",
                                currentStyle,
                                currentStyle.copy(
                                    link = true
                                )
                            )
                    }

                    normalizedTag == "/a" -> {

                        currentStyle =
                            popStyle(
                                stack,
                                "a",
                                currentStyle
                            )
                    }

                    /*
                     * Center alignment.
                     *
                     * This affects body-line alignment only.
                     * It does not make the text clickable or change
                     * any other visual styling.
                     */
                    normalizedTag == "center" -> {

                        currentStyle =
                            pushStyle(
                                stack,
                                "center",
                                currentStyle,
                                currentStyle.copy(
                                    center = true
                                )
                            )
                    }

                    normalizedTag == "/center" -> {

                        currentStyle =
                            popStyle(
                                stack,
                                "center",
                                currentStyle
                            )
                    }

                    /*
                     * Color.
                     */
                    normalizedTag.startsWith("color ") -> {

                        val color =
                            parseColorTag(
                                normalizedTag
                            )

                        if (color != null) {

                            currentStyle =
                                pushStyle(
                                    stack,
                                    "color",
                                    currentStyle,
                                    currentStyle.copy(
                                        color = color
                                    )
                                )

                        } else {

                            /*
                             * Invalid color tag:
                             * display it literally.
                             */
                            appendDecodedText(
                                result,
                                decodeHtmlEntities(
                                    text.substring(
                                        index,
                                        closeBracket + 1
                                    )
                                ),
                                currentStyle
                            )
                        }
                    }

                    normalizedTag == "/color" -> {

                        currentStyle =
                            popStyle(
                                stack,
                                "color",
                                currentStyle
                            )
                    }

                    else -> {

                        /*
                         * Unknown tags are displayed literally.
                         *
                         * This prevents arbitrary text from silently
                         * disappearing.
                         */
                        appendDecodedText(
                            result,
                            decodeHtmlEntities(
                                text.substring(
                                    index,
                                    closeBracket + 1
                                )
                            ),
                            currentStyle
                        )
                    }
                }

                index =
                    closeBracket + 1
            }

            return result
        }

        /**
         * Converts our markup style into the actual font used for drawing.
         */
        private fun fontForStyle(
            baseFont: Font,
            style: TextStyle
        ): Font {

            var fontStyle =
                baseFont.style

            if (style.bold) {
                fontStyle =
                    fontStyle or Font.BOLD
            }

            if (style.italic) {
                fontStyle =
                    fontStyle or Font.ITALIC
            }

            return baseFont.deriveFont(
                fontStyle
            )
        }

        private fun isWrapWhitespace(
            text: String
        ): Boolean {

            return text.isNotEmpty() &&
                Character.isWhitespace(
                    text.codePointAt(0)
                ) &&
                text != "\n"
        }

        private fun isFilenameSeparator(
            text: String
        ): Boolean {

            return text == "/" ||
                text == "\\" ||
                text == "_" ||
                text == "-" ||
                text == "."
        }

        /**
         * Wraps a single paragraph according to actual pixel width.
         */
        private fun wrapParagraph(
            paragraph: List<StyledChar>,
            maxWidth: Int,
            measureWidth: (List<StyledChar>) -> Float
        ): List<List<StyledChar>> {

            if (paragraph.isEmpty()) {
                return emptyList()
            }

            val result =
                mutableListOf<List<StyledChar>>()

            var start = 0

            while (start < paragraph.size) {

                /*
                 * If everything remaining fits, we're finished.
                 */
                if (
                    measureWidth(
                        paragraph.subList(
                            start,
                            paragraph.size
                        )
                    ) <= maxWidth
                ) {

                    result.add(
                        paragraph
                            .subList(
                                start,
                                paragraph.size
                            )
                            .toList()
                    )

                    break
                }

                /*
                 * Find the largest prefix that fits.
                 *
                 * We use binary search because measuring a styled string
                 * with TextLayout is more expensive than measuring one
                 * character at a time.
                 */
                var low =
                    start + 1

                var high =
                    paragraph.size

                var best =
                    start + 1

                while (low <= high) {

                    val mid =
                        (low + high) ushr 1

                    val width =
                        measureWidth(
                            paragraph.subList(
                                start,
                                mid
                            )
                        )

                    if (width <= maxWidth) {

                        best =
                            mid

                        low =
                            mid + 1

                    } else {

                        high =
                            mid - 1
                    }
                }

                /*
                 * Prefer a whitespace boundary.
                 */
                var split =
                    best

                for (
                    i in best - 1 downTo start + 1
                ) {

                    if (
                        isWrapWhitespace(
                            paragraph[i].text
                        )
                    ) {

                        split =
                            i

                        break
                    }
                }

                /*
                 * If there was no useful whitespace boundary, prefer
                 * filename separators.
                 */
                if (split == best) {

                    for (
                        i in best - 1 downTo start + 1
                    ) {

                        if (
                            isFilenameSeparator(
                                paragraph[i].text
                            )
                        ) {

                            /*
                             * Keep the separator on the previous line.
                             */
                            split =
                                i + 1

                            break
                        }
                    }
                }

                /*
                 * Always make progress, even if a single code point
                 * is wider than the available width.
                 */
                if (split <= start) {

                    split =
                        (start + 1)
                            .coerceAtMost(
                                paragraph.size
                            )
                }

                /*
                 * Don't leave whitespace dangling at the end of
                 * the rendered line.
                 */
                var lineEnd =
                    split

                while (
                    lineEnd > start &&
                    isWrapWhitespace(
                        paragraph[lineEnd - 1].text
                    )
                ) {

                    lineEnd--
                }

                /*
                 * Make sure we never generate a zero-length line.
                 */
                if (lineEnd == start) {
                    lineEnd = split
                }

                result.add(
                    paragraph
                        .subList(
                            start,
                            lineEnd
                        )
                        .toList()
                )

                /*
                 * Skip whitespace at the beginning of the next line.
                 */
                start =
                    split

                while (
                    start < paragraph.size &&
                    isWrapWhitespace(
                        paragraph[start].text
                    )
                ) {

                    start++
                }
            }

            return result
        }

        /**
         * Wraps the complete styled text while respecting <br>.
         */
        private fun wrapStyledText(
            chars: List<StyledChar>,
            maxWidth: Int,
            measureWidth: (List<StyledChar>) -> Float
        ): List<List<StyledChar>> {

            val result =
                mutableListOf<List<StyledChar>>()

            val paragraph =
                mutableListOf<StyledChar>()

            var hadContentSinceBreak = false

            fun flushParagraph() {

                if (paragraph.isNotEmpty()) {

                    result.addAll(
                        wrapParagraph(
                            paragraph.toList(),
                            maxWidth,
                            measureWidth
                        )
                    )
                }

                paragraph.clear()
            }

            for (styledChar in chars) {

                if (styledChar.text == "\n") {

                    flushParagraph()

                    /*
                     * Consecutive line breaks create empty lines.
                     */
                    if (!hadContentSinceBreak) {
                        result.add(emptyList())
                    }

                    hadContentSinceBreak = false

                } else {

                    paragraph.add(
                        styledChar
                    )

                    hadContentSinceBreak = true
                }
            }

            flushParagraph()

            return result
        }

        private fun measureLine(
            line: List<StyledChar>,
            baseFontMetrics: FontMetrics,
            metricsForStyle: (TextStyle) -> FontMetrics
        ): MeasuredLine {

            if (line.isEmpty()) {

                return MeasuredLine(
                    baseFontMetrics.height,
                    baseFontMetrics.descent
                )
            }

            var maxHeight = 0
            var maxDescent = 0

            for (styledChar in line) {

                val metrics =
                    metricsForStyle(
                        styledChar.style
                    )

                if (metrics.height > maxHeight) {
                    maxHeight =
                        metrics.height
                }

                if (metrics.descent > maxDescent) {
                    maxDescent =
                        metrics.descent
                }
            }

            return MeasuredLine(
                maxHeight.coerceAtLeast(
                    baseFontMetrics.height
                ),
                maxDescent.coerceAtLeast(
                    baseFontMetrics.descent
                )
            )
        }

        private fun calculateBodyLineHeight(
            lines: List<List<StyledChar>>,
            baseFontMetrics: FontMetrics,
            metricsForStyle: (TextStyle) -> FontMetrics
        ): Int {

            if (lines.isEmpty()) {
                return 0
            }

            var totalHeight = 0

            for (line in lines) {

                totalHeight +=
                    measureLine(
                        line,
                        baseFontMetrics,
                        metricsForStyle
                    ).height
            }

            return totalHeight
        }

        /**
         * Calculates the total notification height needed for the body.
         *
         * bodyStartY is the TOP of the body's available text region.
         * The first line's baseline is calculated from that top position
         * using its actual line height and descent.
         */
        private fun calculateRequiredHeight(
            lines: List<List<StyledChar>>,
            bodyStartY: Int,
            bottomPadding: Int,
            baseFontMetrics: FontMetrics,
            metricsForStyle: (TextStyle) -> FontMetrics
        ): Int {

            if (lines.isEmpty()) {
                return Notify.HEIGHT
            }

            /*
             * The first body's baseline is inside the first line box.
             */
            val firstLineMetrics =
                measureLine(
                    lines[0],
                    baseFontMetrics,
                    metricsForStyle
                )

            var baseline =
                bodyStartY +
                    firstLineMetrics.height -
                    firstLineMetrics.descent

            var lastLineMetrics =
                firstLineMetrics

            /*
             * Advance through each subsequent line using the height
             * of the line before it.
             */
            for (index in 1 until lines.size) {

                baseline +=
                    lastLineMetrics.height

                lastLineMetrics =
                    measureLine(
                        lines[index],
                        baseFontMetrics,
                        metricsForStyle
                    )
            }

            /*
             * Include the final line's descent and the required
             * bottom padding.
             */
            return baseline +
                lastLineMetrics.descent +
                bottomPadding
        }

        /**
         * Ellipsizes a styled line while keeping its styling.
         *
         * The ellipsis itself is never clickable.
         */
        private fun ellipsizeStyledLine(
            line: List<StyledChar>,
            maxWidth: Int,
            measureWidth: (List<StyledChar>) -> Float
        ): List<StyledChar> {

            if (line.isEmpty()) {
                return line
            }

            if (
                measureWidth(line) <= maxWidth
            ) {
                return line
            }

            /*
             * Don't leave a space immediately before the ellipsis.
             */
            var end =
                line.size

            while (
                end > 0 &&
                isWrapWhitespace(
                    line[end - 1].text
                )
            ) {

                end--
            }

            /*
             * Try progressively shorter prefixes until the
             * complete styled result, including the ellipsis,
             * fits.
             */
            while (end > 0) {

                val prefix =
                    line.subList(
                        0,
                        end
                    )

                val ellipsisStyle =
                    prefix
                        .last()
                        .style
                        .copy(
                            /*
                             * The ellipsis itself must never be clickable.
                             *
                             * We retain underline so that truncating an
                             * underlined/link string still looks consistent.
                             */
                            link = false
                        )

                val candidate =
                    prefix +
                    StyledChar(
                        "…",
                        ellipsisStyle
                    )

                if (
                    measureWidth(candidate) <= maxWidth
                ) {

                    return candidate
                }

                end--
            }

            /*
             * Extremely narrow fallback.
             */
            return listOf(
                StyledChar(
                    "…",
                    TextStyle()
                )
            )
        }
    }

    val notification: Notify

    var shakeTween: Tween<T>?
    var moveTween: Tween<T>?
    var hideTween: Tween<T>?

    var idAndPosition: String
    var popupIndex: Int

    var anchorX: Int
    var anchorY: Int

    var progress: Int

    fun renderBackgroundInfo(
        title: String,
        textBody: String,
        theme: Theme,
        imageIcon: ImageIcon?,
        transparentBackground: Boolean = false
    ): RenderedNotification {

        val parsedBody =
            Companion.parseBodyText(
                textBody,
                notification.bodyTextCenter
            )

        /*
         * Temporary graphics context used strictly for font measurement.
         */
        val measureImage =
            BufferedImage(
                1,
                1,
                BufferedImage.TYPE_INT_ARGB
            )

        val measureGraphics =
            measureImage.createGraphics()

        try {

            measureGraphics.font =
                theme.mainTextFont

            val baseFontMetrics =
                measureGraphics.fontMetrics

        /*
         * Reserve enough space around the card for the requested
         * shadow thickness.
         *
         * CARD_X/CARD_Y remain the minimum perimeter when no shadow
         * thickness is requested.
         */
        val effectiveShadowThickness =
            if (notification.showShadow) {
                notification.shadowThickness
                    .coerceIn(0, 10)
            } else {
                0
            }

        val cardX =
            CARD_X + effectiveShadowThickness

        val cardY =
            CARD_Y + effectiveShadowThickness

        val contentX =
            if (imageIcon != null) {

                cardX +
                    ICON_CONTENT_X_OFFSET

            } else {

                cardX +
                    NO_ICON_CONTENT_X_OFFSET
            }

            val maxTextWidth =
                Notify.WIDTH -
                    contentX -
                    RIGHT_TEXT_PADDING

            /*
             * Cache derived fonts and metrics because styled text can
             * contain many individual code points.
             */
            val fontCache =
                mutableMapOf<TextStyle, Font>()

            val metricsCache =
                mutableMapOf<TextStyle, FontMetrics>()

            fun getFont(
                style: TextStyle
            ): Font {

                return fontCache.getOrPut(style) {

                    Companion.fontForStyle(
                        theme.mainTextFont,
                        style
                    )
                }
            }

            fun getMetrics(
                style: TextStyle
            ): FontMetrics {

                return metricsCache.getOrPut(style) {

                    measureGraphics.getFontMetrics(
                        getFont(style)
                    )
                }
            }

            fun measureStyledTextWidth(
                chars: List<StyledChar>
            ): Float {

                if (chars.isEmpty()) {
                    return 0f
                }

                var totalAdvance =
                    0f

                var start =
                    0

                while (
                    start < chars.size
                ) {

                    val style =
                        chars[start].style

                    val runBuilder =
                        StringBuilder()

                    var end =
                        start

                    /*
                     * Group adjacent characters having the same style,
                     * exactly as the renderer does.
                     */
                    while (
                        end < chars.size &&
                        chars[end].style == style
                    ) {

                        runBuilder.append(
                            chars[end].text
                        )

                        end++
                    }

                    val textLayout =
                        TextLayout(
                            runBuilder.toString(),
                            getFont(style),
                            measureGraphics.fontRenderContext
                        )

                    totalAdvance +=
                        textLayout.advance

                    start =
                        end
                }

                return totalAdvance
            }

            /*
             * Perform the initial full wrapping.
             */
            val wrappedBodyLines =
                Companion.wrapStyledText(
                    parsedBody,
                    maxTextWidth,
                    ::measureStyledTextWidth
            )

            var bodyLines =
                wrappedBodyLines.toMutableList()

            /*
             * Determine the actual bottom of the rendered title text.
             *
             * The title is drawn at TITLE_BASELINE_OFFSET relative to the card.
             * TextLayout gives us the actual visible glyph bounds below that
             * baseline, rather than using the much larger BODY_START_OFFSET.
             */
            val titleFont =
                theme.titleTextFont

            val titleMetrics =
                measureGraphics.getFontMetrics(
                    titleFont
                )

            val fittedTitle =
                fitTitleSingleLine(
                    title,
                    titleMetrics,
                    maxTextWidth
                )

            val titleBottom =
                if (title.isNotBlank()) {

                    val titleLayout =
                        TextLayout(
                            fittedTitle,
                            titleFont,
                            measureGraphics.fontRenderContext
                        )

                    cardY +
                        TITLE_BASELINE_OFFSET +
                        kotlin.math.ceil(
                            titleLayout
                                .getBlackBoxBounds(
                                    0,
                                    titleLayout.characterCount
                                )
                                .bounds2D
                                .maxY
                        ).toInt()

                } else {

                    cardY
                }

            /*
             * Determine how many wrapped lines fit inside the
             * maximum notification height.
             */
            val bodyStartY =
                if (title.isNotBlank()) {

                    titleBottom

                } else {

                    cardY
                }

            var visibleLineCount =
                bodyLines.size

            while (visibleLineCount > 1) {

                val candidateLines =
                    bodyLines.take(
                        visibleLineCount
                    )

                val requiredHeight =
                    Companion.calculateRequiredHeight(
                        candidateLines,
                        bodyStartY,
                        BODY_BOTTOM_PADDING,
                        baseFontMetrics,
                        ::getMetrics
                    )

                if (
                    requiredHeight <=
                    Notify.MAX_NOTIFICATION_HEIGHT
                ) {
                    break
                }

                visibleLineCount--
            }

            /*
             * Truncate the number of visible lines if necessary.
             */
            if (
                bodyLines.size >
                visibleLineCount
            ) {

                bodyLines =
                    bodyLines
                        .take(
                            visibleLineCount
                                .coerceAtLeast(1)
                        )
                        .toMutableList()
            }

            /*
             * If we removed wrapped lines, put an ellipsis on
             * the final visible line.
             */
            if (
                bodyLines.size <
                wrappedBodyLines.size
            ) {

                val lastIndex =
                    bodyLines.lastIndex

                bodyLines[lastIndex] =
                    Companion.ellipsizeStyledLine(
                        bodyLines[lastIndex],
                        maxTextWidth,
                        ::measureStyledTextWidth
                )
            }

            /*
             * Determine the final actual notification height.
             */
            val actualHeight =
                Companion.calculateRequiredHeight(
                    bodyLines,
                    bodyStartY,
                    BODY_BOTTOM_PADDING,
                    baseFontMetrics,
                    ::getMetrics
                ).coerceIn(
                    Notify.HEIGHT,
                    Notify.MAX_NOTIFICATION_HEIGHT
                )

            /*
             * Calculate the total line-box height occupied by the body.
             */
            val bodyLineHeight =
                Companion.calculateBodyLineHeight(
                    bodyLines,
                    baseFontMetrics,
                    ::getMetrics
                )

            /*
             * Calculate where the body should begin vertically.
             *
             * The body is centered within:
             *
             *   - the area below the title when a title exists
             *   - the entire card when there is no title
             */
            val bodyRegionTop =
                titleBottom

            val bodyRegionBottom =
                if (title.isNotBlank()) {

                    actualHeight -
                        cardY -
                        BODY_BOTTOM_PADDING

                } else {

                    actualHeight -
                        cardY
                }

            /*
             * The first body's baseline is positioned so that the complete
             * block of text is vertically centered in the available region.
             */
            val firstLineMetrics =
                if (bodyLines.isNotEmpty()) {

                    Companion.measureLine(
                        bodyLines[0],
                        baseFontMetrics,
                        ::getMetrics
                    )

                } else {

                    MeasuredLine(
                        baseFontMetrics.height,
                        baseFontMetrics.descent
                    )
                }

            val bodyStartBaseline =
                bodyRegionTop +
                    (
                        (
                            bodyRegionBottom -
                                bodyRegionTop -
                                bodyLineHeight
                        ).coerceAtLeast(0)
                        / 2
                    ) +
                    firstLineMetrics.height -
                    firstLineMetrics.descent +
                    BODY_VERTICAL_CENTER_OFFSET

            /*
             * Determine the actual vertical bounds occupied by the title
             * and body text.
             *
             * The icon will be vertically centered against this text block,
             * rather than against the entire notification panel.
             */
            val textContentTop: Int

            val textContentBottom: Int

            if (title.isNotBlank() && bodyLines.isNotEmpty()) {

                /*
                 * Title exists and body exists.
                 *
                 * Use the actual font metrics around the title baseline for
                 * the top of the content block.
                 */
                val titleTop =
                    cardY +
                        TITLE_BASELINE_OFFSET -
                        titleMetrics.ascent

                /*
                 * Find the baseline of the last body line.
                 */
                val lastLineMetrics =
                    Companion.measureLine(
                        bodyLines.last(),
                        baseFontMetrics,
                        ::getMetrics
                    )

                val lastBodyBaseline =
                    bodyStartBaseline +
                        bodyLineHeight -
                        lastLineMetrics.height

                val bodyBottom =
                    lastBodyBaseline +
                        lastLineMetrics.descent

                textContentTop =
                    titleTop

                textContentBottom =
                    bodyBottom

            } else if (title.isNotBlank()) {

                /*
                 * Title only.
                 */
                textContentTop =
                    cardY +
                        TITLE_BASELINE_OFFSET -
                        titleMetrics.ascent

                textContentBottom =
                    cardY +
                        TITLE_BASELINE_OFFSET +
                        titleMetrics.descent

            } else if (bodyLines.isNotEmpty()) {

                /*
                 * Body only.
                 */
                val lastLineMetrics =
                    Companion.measureLine(
                        bodyLines.last(),
                        baseFontMetrics,
                        ::getMetrics
                    )

                val lastBodyBaseline =
                    bodyStartBaseline +
                        bodyLineHeight -
                        lastLineMetrics.height

                textContentTop =
                    bodyStartBaseline -
                        firstLineMetrics.height +
                        firstLineMetrics.descent

                textContentBottom =
                    lastBodyBaseline +
                        lastLineMetrics.descent

            } else {

                /*
                 * No title and no body.
                 *
                 * Fall back to centering the icon within the card.
                 */
                textContentTop =
                    cardY

                textContentBottom =
                    actualHeight -
                        cardY
            }

            /*
             * Center the icon tile against the actual text-content block.
             */
            val iconTileY =
                (
                    textContentTop +
                        (
                            textContentBottom -
                                textContentTop -
                                ICON_TILE_SIZE
                        ) / 2
                )
                    .coerceIn(
                        cardY,
                        actualHeight -
                            cardY -
                            ICON_TILE_SIZE
                    )

            /*
             * Now create the actual rendered image at its final height.
             */
            val image =
                BufferedImage(
                    Notify.WIDTH,
                    actualHeight,
                    BufferedImage.TYPE_INT_ARGB
                )

            val g2 =
                image.createGraphics()

            val newLinkBounds =
                mutableListOf<Rectangle>()

            try {

                g2.setRenderingHint(
                    RenderingHints.KEY_ALPHA_INTERPOLATION,
                    RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY
                )

                g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
                )

                g2.setRenderingHint(
                    RenderingHints.KEY_COLOR_RENDERING,
                    RenderingHints.VALUE_COLOR_RENDER_QUALITY
                )

                g2.setRenderingHint(
                    RenderingHints.KEY_DITHERING,
                    RenderingHints.VALUE_DITHER_ENABLE
                )

                g2.setRenderingHint(
                    RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_ON
                )

                g2.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR
                )

                g2.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY
                )

                g2.setRenderingHint(
                    RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE
                )

                /*
                 * Opaque window background.
                 */
                val windowBackground =
                    if (
                        theme.panel_BG.red +
                        theme.panel_BG.green +
                        theme.panel_BG.blue < 380
                    ) {

                        Color(
                            (theme.panel_BG.red + 2).coerceAtMost(255),
                            (theme.panel_BG.green + 2).coerceAtMost(255),
                            (theme.panel_BG.blue + 2).coerceAtMost(255)
                        )

                    } else {

                        Color(
                            (theme.panel_BG.red - 2).coerceAtLeast(0),
                            (theme.panel_BG.green - 2).coerceAtLeast(0),
                            (theme.panel_BG.blue - 2).coerceAtLeast(0)
                        )
                    }

                /*
                 * When transparency is active, the freshly-created ARGB image
                 * already contains fully transparent pixels. We therefore leave
                 * the area outside the rounded card untouched.
                 *
                 * When transparency is not active, retain the normal opaque
                 * rectangular window background.
                 */
                if (!transparentBackground) {

                    g2.color =
                        windowBackground

                    g2.fillRect(
                        0,
                        0,
                        Notify.WIDTH,
                        actualHeight
                    )
                }

                /*
                 * Card geometry.
                 */
                val cardWidth =
                    Notify.WIDTH - (cardX * 2)

                val cardHeight =
                    actualHeight - (cardY * 2)

                /*
                 * Optional drop shadow.
                 *
                 * The card is moved inward by effectiveShadowThickness, leaving room
                 * around the outside for the shadow to remain visible.
                 */
                if (
                    notification.showShadow &&
                    effectiveShadowThickness > 0 &&
                    notification.shadowOpacity > 0
                ) {

                    val shadowAlpha =
                        (
                            notification.shadowOpacity * 255f / 100f
                        )
                            .toInt()
                            .coerceIn(0, 255)

                    g2.color =
                        Color(
                            0,
                            0,
                            0,
                            shadowAlpha
                        )

                    g2.fillRoundRect(
                        cardX - effectiveShadowThickness,
                        cardY - effectiveShadowThickness,
                        cardWidth + (effectiveShadowThickness * 2),
                        cardHeight + (effectiveShadowThickness * 2),
                        CORNER_RADIUS + (effectiveShadowThickness * 2),
                        CORNER_RADIUS + (effectiveShadowThickness * 2)
                    )
                }

                /*
                 * Main card.
                 */
                g2.color =
                    notification.panelColor
                        ?: theme.panel_BG

                g2.fillRoundRect(
                    cardX,
                    cardY,
                    cardWidth,
                    cardHeight,
                    CORNER_RADIUS,
                    CORNER_RADIUS
                )

                /*
                 * Subtle card highlight.
                 */
                g2.color =
                    Color(
                        255,
                        255,
                        255,
                        28
                    )

                g2.drawRoundRect(
                    cardX,
                    cardY,
                    cardWidth - 1,
                    cardHeight - 1,
                    CORNER_RADIUS,
                    CORNER_RADIUS
                )

                /*
                 * Priority accent.
                 */
                val accentX =
                    cardX + 5

                val accentY =
                    cardY + 17

                val accentHeight =
                    cardHeight - 34

                g2.color =
                    notification.accentColor

                g2.fillRoundRect(
                    accentX,
                    accentY,
                    4,
                    accentHeight,
                    4,
                    4
                )

                /*
                 * Icon tile.
                 */
                if (imageIcon != null) {

                    val iconTileX =
                        cardX +
                            ICON_TILE_X_OFFSET

                    g2.color =
                        Color(
                            255,
                            255,
                            255,
                            12
                        )

                    g2.fillRoundRect(
                        iconTileX,
                        iconTileY,
                        ICON_TILE_SIZE,
                        ICON_TILE_SIZE,
                        12,
                        12
                    )

                    imageIcon.paintIcon(
                        null,
                        g2,
                        iconTileX + 1,
                        iconTileY + 1
                    )
                }

                g2.font =
                    titleFont

                g2.color =
                    notification.titleTextColor
                        ?: theme.titleText_FG

                val titleWidth =
                    titleMetrics.stringWidth(
                        fittedTitle
                    )

                val titleX =
                    if (notification.titleCenter) {

                        contentX +
                            (
                                maxTextWidth -
                                    titleWidth
                            ) / 2

                    } else {

                        contentX
                    }

                g2.drawString(
                    fittedTitle,
                    titleX,
                    cardY + TITLE_BASELINE_OFFSET
                )

                /*
                 * Body.
                 */
                var baseline =
                    bodyStartBaseline

                for (
                    lineIndex in
                    bodyLines.indices
                ) {

                    val line =
                        bodyLines[lineIndex]

                    val lineMetrics =
                        Companion.measureLine(
                            line,
                            baseFontMetrics,
                            ::getMetrics
                        )

                    /*
                     * A line is centered only when every styled character on that
                     * line requests centered alignment.
                     *
                     * Mixed centered/non-centered content on one line intentionally
                     * remains left-aligned.
                     */
                    val lineIsCentered =
                        line.isNotEmpty() &&
                        line.all {
                            it.style.center
                        }

                    val lineWidth =
                        if (lineIsCentered) {

                            measureStyledTextWidth(
                                line
                            )

                        } else {

                            0f
                        }

                    var x =
                        if (lineIsCentered) {

                            contentX +
                                (
                                    (
                                        maxTextWidth.toFloat() -
                                        lineWidth
                                    ) / 2f
                                ).toInt()

                        } else {

                            contentX
                        }

                    var charIndex = 0

                    while (
                        charIndex < line.size
                    ) {

                        val style =
                            line[charIndex].style

                        val font =
                            getFont(style)

                        val metrics =
                            getMetrics(style)

                        val runBuilder =
                            StringBuilder()

                        /*
                         * Group adjacent code points having exactly
                         * the same visual style.
                         */
                        while (
                            charIndex < line.size &&
                            line[charIndex].style == style
                        ) {

                            runBuilder.append(
                                line[charIndex].text
                            )

                            charIndex++
                        }

                        val runText =
                            runBuilder.toString()

                        /*
                         * Use TextLayout for the actual rendered run.
                         *
                         * Unlike measuring each character individually,
                         * TextLayout preserves the exact advance of the
                         * complete string, including trailing whitespace.
                         *
                         * This is especially important when two adjacent
                         * runs use different styles, for example:
                         *
                         *     "very " + <b>"long line"</b>
                         */
                        val textLayout =
                            TextLayout(
                                runText,
                                font,
                                g2.fontRenderContext
                            )

                        /*
                         * Advance of the complete rendered run.
                         *
                         * This includes whitespace and therefore ensures
                         * that a space immediately before a style change
                         * remains visually present.
                         */
                        val runAdvance =
                            textLayout.advance

                        /*
                         * Get the visible glyph bounds rather than relying
                         * only on the logical string advance.
                         *
                         * This gives us a better endpoint for the underline,
                         * especially with filenames containing punctuation.
                         */
                        val blackBox =
                            textLayout
                                .getBlackBoxBounds(
                                    0,
                                    textLayout.characterCount
                                )
                                .bounds2D

                        val underlineWidth =
                            blackBox.maxX
                                .toFloat()
                                .coerceAtLeast(
                                    0f
                                )

                        /*
                         * Draw the styled run.
                         */
                        g2.color =
                            style.color
                                ?: notification.bodyTextColor
                                ?: theme.mainText_FG

                        textLayout.draw(
                            g2,
                            x.toFloat(),
                            baseline.toFloat()
                        )

                        /*
                         * <u> and <a> are both underlined.
                         *
                         * IMPORTANT:
                         *
                         * <u> is NOT clickable.
                         * <a> is the ONLY clickable style.
                         */
                        if (
                            style.underline ||
                            style.link
                        ) {

                            g2.stroke =
                                BasicStroke(
                                    1f
                                )

                            g2.drawLine(
                                x,
                                baseline + 2,
                                x +
                                    kotlin.math.ceil(
                                        underlineWidth.toDouble()
                                    ).toInt() +
                                    1,
                                baseline + 2
                            )
                        }

                        /*
                         * ONLY <a> creates a clickable region.
                         */
                        if (
                            style.link &&
                            runAdvance > 0f
                        ) {

                            newLinkBounds.add(
                                Rectangle(
                                    x,
                                    baseline - metrics.ascent,
                                    kotlin.math.ceil(
                                        runAdvance.toDouble()
                                    ).toInt() + 1,
                                    metrics.height
                                )
                            )
                        }

                        /*
                         * Move to the beginning of the next styled run
                         * using the actual rendered advance.
                         */
                        x +=
                            kotlin.math.ceil(
                                runAdvance.toDouble()
                            ).toInt()
                    }

                    if (
                        lineIndex <
                        bodyLines.lastIndex
                    ) {

                        baseline +=
                            lineMetrics.height
                    }
                }

            } finally {

                g2.dispose()
            }

            return RenderedNotification(
                image = image,
                linkBounds = newLinkBounds,
                height = actualHeight
            )

        } finally {

            measureGraphics.dispose()
            measureImage.flush()
        }
    }

    fun renderCloseButton(
        theme: Theme,
        enabled: Boolean
    ): BufferedImage {

        /*
         * The close image now follows the actual notification height.
         */
        val image =
            BufferedImage(
                Notify.WIDTH,
                notification.notificationHeight,
                BufferedImage.TYPE_INT_ARGB
            )

        val g2 =
            image.createGraphics()

        g2.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_ON
        )

        try {

            val buttonSize = 22
            val buttonX =
                Notify.WIDTH - 33

            val buttonY = 12

            if (enabled) {

                g2.color =
                    Color(
                        255,
                        69,
                        58,
                        45
                    )

                g2.fillRoundRect(
                    buttonX,
                    buttonY,
                    buttonSize,
                    buttonSize,
                    buttonSize,
                    buttonSize
                )
            }

            val centerX =
                buttonX +
                    buttonSize / 2

            val centerY =
                buttonY +
                    buttonSize / 2

            g2.stroke =
                BasicStroke(
                    if (enabled) {
                        2.0f
                    } else {
                        1.7f
                    },
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND
                )

            g2.color =
                if (enabled) {
                    Color(0xFF453A)
                } else {
                    theme.closeX_FG
                }

            /*
             * X
             */
            g2.drawLine(
                centerX - 4,
                centerY - 4,
                centerX + 4,
                centerY + 4
            )

            g2.drawLine(
                centerX + 4,
                centerY - 4,
                centerX - 4,
                centerY + 4
            )

        } finally {

            g2.dispose()
        }

        return image
    }

    /**
     * Fits the title to one line without splitting a Unicode
     * surrogate pair.
     */
    private fun fitTitleSingleLine(
        text: String,
        fontMetrics: FontMetrics,
        maxWidth: Int
    ): String {

        if (
            fontMetrics.stringWidth(text) <=
            maxWidth
        ) {

            return text
        }

        val ellipsis =
            "…"

        var end =
            text.length

        while (end > 0) {

            val candidate =
                text.substring(
                    0,
                    end
                )

            if (
                fontMetrics.stringWidth(
                    candidate + ellipsis
                ) <= maxWidth
            ) {

                return candidate +
                    ellipsis
            }

            end =
                candidate.offsetByCodePoints(
                    candidate.length,
                    -1
                )
        }

        return ellipsis
    }

    fun updatePositionsPre(
        component: Component,
        notify: NotifyType<T>,
        visible: Boolean
    ) {

        if (!visible) {

            LAFUtil.removePopupFromMap(
                notify
            )

            SwingActiveRender.remove(
                component
            )
        }
    }

    fun updatePositionsPost(
        renderComponent: Component,
        notify: NotifyType<T>,
        visible: Boolean
    ) {

        if (visible) {

            /*
             * First calculate and assign the notification's final
             * stack position.
             */
            LAFUtil.addPopupToMap(
                notify
            )

            /*
             * Prepare and start the entry animation BEFORE handing
             * the notification to the active-render thread.
             *
             * This prevents the renderer from seeing the notification
             * before its starting position and tween have been established.
             */
            notify.animateIn()

            /*
             * Now the notification is fully initialized for rendering.
             */
            if (
                renderComponent is DesktopNotify &&
                renderComponent.useRepaintOnlyTransparencyPainting()
            ) {

                /*
                 * Transparent Windows notifications need normal AWT repainting.
                 * They still participate in the active-render loop so that
                 * tween updates and timeouts continue to work.
                 */
                SwingActiveRender.add(
                    renderComponent,
                    true
                )

            } else {

                /*
                 * Preserve the original BufferStrategy path for every other
                 * notification.
                 */
                SwingActiveRender.add(
                    renderComponent
                )
            }
        }
    }

    fun doShake(
        count: Int,
        targetX: Float,
        targetY: Float
    )

    fun shake(
        durationInMillis: Int,
        amplitude: Int
    ) {

        var i1 =
            LAFUtil.RANDOM.nextInt(
                (amplitude shl 2) + 1
            ) - amplitude

        var i2 =
            LAFUtil.RANDOM.nextInt(
                (amplitude shl 2) + 1
            ) - amplitude

        i1 =
            i1 shr 2

        i2 =
            i2 shr 2

        /*
         * Make sure it always moves by some amount.
         */
        if (i1 < 0) {
            i1 -= amplitude shr 2
        } else {
            i1 += amplitude shr 2
        }

        if (i2 < 0) {
            i2 -= amplitude shr 2
        } else {
            i2 += amplitude shr 2
        }

        var count =
            durationInMillis / 50

        /*
         * Make sure we always end the animation where we started.
         */
        if (count and 1 == 0) {
            count++
        }

        doShake(
            count,
            i1.toFloat(),
            i2.toFloat()
        )
    }

    fun cancelShake() {

        if (shakeTween != null) {

            shakeTween!!.cancel()
            shakeTween = null
        }
    }

    fun animateIn()

    fun close()

    fun setVisible(
        visible: Boolean
    )

    fun setLocationInternal(
        x: Int,
        y: Int
    )

    fun refresh()

    fun setupHide()

    fun cancelHide() {

        if (hideTween != null) {

            hideTween!!.cancel()
            hideTween = null
        }
    }

    fun setupMove(
        y: Float
    )

    fun cancelMove() {

        if (moveTween != null) {

            moveTween!!.cancel()
            moveTween = null
        }
    }
}