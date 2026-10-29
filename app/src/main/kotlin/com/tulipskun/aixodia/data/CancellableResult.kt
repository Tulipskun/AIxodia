package com.tulipskun.aixodia.data

import kotlinx.coroutines.CancellationException

/** Best-effort work may fail, but cancellation must still stop its caller. */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (err: CancellationException) {
    throw err
} catch (err: Exception) {
    Result.failure(err)
}
