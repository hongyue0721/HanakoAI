package `fun`.kirari.hanako.network

import `fun`.kirari.hanako.debug.AppDebugLogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class SseStreamClient(
    private val client: OkHttpClient
) {
    private val tag = "HanakoSseClient"

    internal data class StreamEventResult(
        val delta: String? = null,
        val done: Boolean = false
    )

    suspend fun stream(
        request: Request,
        onEvent: (eventSource: EventSource, type: String?, id: String?, data: String) -> StreamEventResult?,
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val builder = StringBuilder()
            val finished = AtomicBoolean(false)
            var eventCount = 0

            fun finish(block: () -> Unit) {
                if (finished.compareAndSet(false, true)) {
                    block()
                }
            }

            val listener = object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    AppDebugLogStore.i(tag, "stream opened code=${response.code} url=${request.url}")
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    try {
                        eventCount += 1
                        if (eventCount <= 5 || eventCount % 25 == 0) {
                            AppDebugLogStore.d(tag, "stream event#$eventCount type=$type id=$id dataLength=${data.length}")
                        }
                        val result = onEvent(eventSource, type, id, data)
                        val delta = result?.delta
                        if (!delta.isNullOrEmpty()) {
                            builder.append(delta)
                            onDelta(delta)
                        }
                        if (result?.done == true) {
                            AppDebugLogStore.i(tag, "stream completed by protocol signal totalEvents=$eventCount outputLength=${builder.length} url=${request.url}")
                            finish { cont.resume(builder.toString()) }
                            eventSource.cancel()
                        }
                    } catch (t: Throwable) {
                        finish { cont.resumeWithException(t) }
                        eventSource.cancel()
                    }
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    AppDebugLogStore.e(tag, "stream failure code=${response?.code} url=${request.url}", t)
                    finish {
                        cont.resumeWithException(
                            t ?: IllegalStateException(
                                "Stream failed: ${response?.code ?: "unknown"}"
                            )
                        )
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    AppDebugLogStore.i(tag, "stream closed totalEvents=$eventCount outputLength=${builder.length} url=${request.url}")
                    finish { cont.resume(builder.toString()) }
                }
            }

            val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
            cont.invokeOnCancellation { eventSource.cancel() }
        }
    }
}
