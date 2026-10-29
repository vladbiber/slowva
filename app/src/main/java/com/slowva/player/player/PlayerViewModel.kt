package com.slowva.player.player

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.slowva.player.SlowvaApp
import com.slowva.player.data.MusicRepository
import com.slowva.player.data.Track
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Library actions plus the connection to [PlaybackService]; playback logic lives in [PlaybackController]. */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** off in screenshot tests, where no media session can be bound */
        @Volatile var connectService = true
    }

    val repository: MusicRepository = (app as SlowvaApp).repository

    private var controller: MediaController? = null

    val ui: StateFlow<PlayerUiState> = PlayerHub.ui

    init {
        PlayerHub.init(app)
        viewModelScope.launch { repository.load(includeDevice = false) }
        if (connectService) connectController()
    }

    fun refreshLibrary(includeDevice: Boolean) {
        viewModelScope.launch { repository.load(includeDevice) }
    }

    fun importFile(uri: android.net.Uri) {
        viewModelScope.launch { repository.importFile(uri) }
    }

    fun importFolder(treeUri: android.net.Uri) {
        viewModelScope.launch {
            val result = repository.importFolder(treeUri)
            val msg = when {
                result == null -> "Couldn't read that folder"
                result.second == 0 -> "No audio files found in \"${result.first}\""
                else -> "Imported ${result.second} songs · playlist \"${result.first}\" created"
            }
            android.widget.Toast.makeText(getApplication(), msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun connectController() {
        val ctx = getApplication<Application>()
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            PlayerHub.remote = c
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun playQueue(tracks: List<Track>, startIndex: Int) = PlayerHub.playQueue(tracks.map { it.id }, startIndex)
    fun addToQueue(trackId: String) = PlayerHub.addToQueue(trackId)
    fun playQueueIndex(index: Int) = PlayerHub.playQueueIndex(index)
    fun toggleShuffle() = PlayerHub.toggleShuffle()
    fun togglePlay() = PlayerHub.togglePlay()
    fun next() = PlayerHub.next()
    fun toggleFavorite(id: String) = repository.toggleFavorite(id)

    override fun onCleared() {
        if (PlayerHub.remote === controller) PlayerHub.remote = null
        controller?.release()
        controller = null
        super.onCleared()
    }
}

fun Track.toMediaItem(): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setArtworkUri(coverUri?.let(Uri::parse))
        .build()
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setMediaMetadata(metadata)
        .build()
}
