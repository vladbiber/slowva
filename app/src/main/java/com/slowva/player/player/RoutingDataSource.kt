package com.slowva.player.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/** Sends a uri to the source registered for its scheme in [routes], everything else to [fallback]. */
@UnstableApi
class RoutingDataSourceFactory(
    private val fallback: DataSource.Factory,
    private val routes: Map<String, () -> DataSource> = emptyMap(),
) : DataSource.Factory {
    override fun createDataSource(): DataSource = Routing(fallback.createDataSource(), routes)

    private class Routing(
        private val other: DataSource,
        private val routes: Map<String, () -> DataSource>,
    ) : DataSource by other {
        private var active: DataSource = other

        override fun open(dataSpec: DataSpec): Long {
            active = routes[dataSpec.uri.scheme]?.invoke() ?: other
            return active.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int) = active.read(buffer, offset, length)
        override fun getUri(): Uri? = active.uri
        override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders
        override fun close() = active.close()
    }
}
