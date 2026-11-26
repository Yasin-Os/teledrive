package com.awd.teledrive.data.remote

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramClient @Inject constructor() {
    private var client: Client? = null

    // H24: updates must never be silently dropped. tryEmit() on a
    // bounded SharedFlow returns false — and drops the update — when
    // the buffer is full, so a burst of updates could lose an
    // UpdateAuthorizationState. Every update is handed to this
    // single-threaded emitter instead: the TDLib callback never blocks,
    // updates are emitted in arrival order, and emit() suspends
    // (instead of dropping) when subscribers are behind.
    private val emitScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val _updates = MutableSharedFlow<TdApi.Object>(extraBufferCapacity = 500)
    val updates = _updates.asSharedFlow()

    val fileUpdates = updates.filterIsInstance<TdApi.UpdateFile>()

    init {
        try {
            // Load the native library explicitly before creating the client
            System.loadLibrary("tdjni")
            Log.d("TelegramClient", "TDLib native library loaded successfully")
            
            // Initialize TDLib. The two exception handlers used to
            // be null, so fatal TDLib errors were swallowed silently.
            client = Client.create(
                { update ->
                    emitScope.launch { _updates.emit(update) }
                },
                Client.ExceptionHandler { e ->
                    Log.e("TelegramClient", "TDLib update handler error", e)
                },
                Client.ExceptionHandler { e ->
                    Log.e("TelegramClient", "TDLib fatal error", e)
                },
            )
        } catch (e: UnsatisfiedLinkError) {
            Log.e("TelegramClient", "Failed to load TDLib native library", e)
        } catch (e: Exception) {
            Log.e("TelegramClient", "Error creating TDLib client", e)
        }
    }

    fun send(function: TdApi.Function<out TdApi.Object>, resultHandler: Client.ResultHandler = Client.ResultHandler { }) {
        val currentClient = client
        if (currentClient != null) {
            currentClient.send(function, resultHandler)
        } else {
            Log.e("TelegramClient", "Cannot send request: client is null (initialization failed?)")
            resultHandler.onResult(TdApi.Error(500, "TDLib client not initialized"))
        }
    }
}
