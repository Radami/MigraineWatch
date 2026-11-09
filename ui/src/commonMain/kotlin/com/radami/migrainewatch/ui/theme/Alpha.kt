package com.radami.migrainewatch.ui.theme

// Shared alpha steps for how far something recedes (text, an unselected control), named by
// emphasis level rather than by use, so callers pick a step off the scale, not a raw number.

/** The thing a card is about: a figure, a headline, the value being reported. */
const val FULL_ALPHA = 1f

/** A label at nearly full strength — a row heading standing over what it introduces. */
const val SUBDUED_ALPHA = 0.8f

/** A word attached to something louder: the unit beside a number, a placeholder's title. */
const val SUPPORTING_ALPHA = 0.7f

/** Present but not the point: timestamps, subtitles, date ranges, helper lines under a field. */
const val SECONDARY_ALPHA = 0.6f

/** An empty card, a row the chart cannot show, the letters over a calendar's columns. */
const val MUTED_ALPHA = 0.5f

/** The faintest thing still meant to be seen: a version footer, a step not yet reached. */
const val FAINT_ALPHA = 0.4f
