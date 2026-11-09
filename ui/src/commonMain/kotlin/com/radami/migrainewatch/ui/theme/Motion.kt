package com.radami.migrainewatch.ui.theme

/**
 * How long screens take to settle onto new data, kept short: the point is to show a value
 * moved, not to make the reader wait for it.
 */
object Motion {

    /** Text leaving on a value change, plus the wait before its replacement arrives. */
    const val CONTENT_EXIT_MILLIS = 90

    /** Text arriving. Longer than the exit so the new value settles rather than snaps in. */
    const val CONTENT_ENTER_MILLIS = 180

    /** A day marker changing silhouette. Long enough for the eye to follow the shape change. */
    const val SHAPE_MORPH_MILLIS = 300

    /** Colour and opacity settling — a weekday receding, a ring fading in behind a number. */
    const val EMPHASIS_MILLIS = 250

    /** A whole panel sliding out for the next one. Longer than a value settling in place. */
    const val PANEL_SLIDE_MILLIS = 300

    /** How far arriving text slides, as a fraction of its own height; small, just for direction. */
    const val CONTENT_SLIDE_FRACTION = 6
}
