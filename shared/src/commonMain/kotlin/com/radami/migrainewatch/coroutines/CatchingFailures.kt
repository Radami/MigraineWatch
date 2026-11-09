package com.radami.migrainewatch.coroutines

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] for coroutine code: failures become a [Result], but cancellation is rethrown.
 * `runCatching` would swallow it, so a cancelled caller would carry on as if its work had
 * failed, reporting an error nobody made and writing results nobody wants any more.
 *
 * The one way every layer catches failures, so cancellation behaves the same everywhere.
 */
inline fun <T> catchingFailures(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
