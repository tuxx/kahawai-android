package com.kolktech.kahawai.ui.player.subtitle

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import okhttp3.Call
import okhttp3.Response

/**
 * Own the response until [read] returns, and close the socket when the caller
 * cancels, including while execute() or a body read is blocked. Completion
 * handlers cannot do this: the blocked reader prevents its job completing.
 * https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-job/
 */
internal suspend fun <T> Call.useCancellableResponse(read: (Response) -> T): T = coroutineScope {
    // Socket closure must not queue behind readers occupying every IO worker.
    // This watcher only waits and calls thread-safe Call.cancel(); it never
    // reads a response or touches UI state on the cancelling thread.
    val closeOnCancel = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            this@useCancellableResponse.cancel()
        }
    }
    try {
        withContext(Dispatchers.IO) {
            try {
                execute().use(read)
            } catch (e: IOException) {
                // Closing a blocked socket throws IOException. A cancelled
                // reader must not fail its parent or enter the retry path.
                // Check the outer scope: it is marked cancelling before the
                // watcher closes the socket, while this withContext job may
                // not be yet when the read fails.
                this@coroutineScope.ensureActive()
                throw e
            }
        }
    } finally {
        closeOnCancel.cancel()
    }
}
