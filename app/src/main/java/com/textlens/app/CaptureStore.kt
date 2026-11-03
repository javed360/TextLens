package com.textlens.app

import android.graphics.Bitmap

/** In-memory hand-off of the latest screenshot between the capture service and the selection screen. */
object CaptureStore {
    @Volatile
    var bitmap: Bitmap? = null

    /** Called on the main thread when a capture attempt finished (true = bitmap available). */
    @Volatile
    var listener: ((Boolean) -> Unit)? = null

    fun clear() {
        bitmap = null
        listener = null
    }
}
