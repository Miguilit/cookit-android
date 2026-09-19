package be.cookit.pos.android.data.fiscal

import android.content.Context

/** Release variant: the embedded Mock FDM does not exist operationally. */
class EmbeddedMockFdmServer(@Suppress("UNUSED_PARAMETER") context: Context) {
    fun start(): EmbeddedMockFdmStatus = EmbeddedMockFdmStatus(available = false, running = false)
    fun stop(): EmbeddedMockFdmStatus = EmbeddedMockFdmStatus(available = false, running = false)
    fun status(): EmbeddedMockFdmStatus = EmbeddedMockFdmStatus(available = false, running = false)
}
