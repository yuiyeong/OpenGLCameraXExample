package com.joyuiyeongl.ypreview

import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException


class SingleThreadHandlerExecutor(private val mThreadName: String, priority: Int) : Executor {
    private val mHandlerThread: HandlerThread = HandlerThread(mThreadName, priority)
    val handler: Handler

    override fun execute(command: Runnable) {
        if (!handler.post(command)) {
            throw RejectedExecutionException("$mThreadName is shutting down.")
        }
    }

    fun shutdown(): Boolean {
        return mHandlerThread.quitSafely()
    }

    init {
        mHandlerThread.start()
        handler = Handler(mHandlerThread.looper)
    }
}
