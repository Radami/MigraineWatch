package com.radami.migrainewatch.ui.theme

/**
 * How long the screens take to settle onto new data.
 *
 * A refresh can rewrite the whole forecast under a reader who is already looking at it, and an
 * instant swap reads as a glitch rather than as an update. These are deliberately short: the
 * point is to show that a value moved, not to make the reader wait for it.
 */
object Motion {

    /** Text leaving on a value change, and the wait before its replacement starts arriving. */
    const val CONTENT_EXIT_MILLIS = 90

    /** Text arriving. Longer than the exit so the new value settles rather than snaps in. */
    const val CONTENT_ENTER_MILLIS = 180

    /**
     * A day marker changing silhouette. Longer than the text: the corner rounding travels a
     * visible distance, and the eye has to be able to follow it to read the change as one day
     * turning risky rather than as the strip redrawing.
     */
    const val SHAPE_MORPH_MILLIS = 300

    /** Colour and opacity settling — a weekday receding, a ring fading in behind a number. */
    const val EMPHASIS_MILLIS = 250

    /**
     * How far arriving text slides, as a fraction of its own height. Small on purpose: enough
     * to give the fade a direction, not enough to read as the line moving somewhere.
     */
    const val CONTENT_SLIDE_FRACTION = 6
}
