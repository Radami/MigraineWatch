package com.radami.migrainewatch.domain

import java.time.LocalDate

/** A logged day as streak counting sees it: which day, and how bad it was. */
data class SymptomDay(
    val date: LocalDate,
    val severity: Severity
)
