package com.calmcast.podcast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.calmcast.podcast.api.ItunesApiService
import com.calmcast.podcast.data.PodcastDatabase
import com.calmcast.podcast.data.PodcastRepository
import com.calmcast.podcast.data.SubscriptionManager

import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient

import java.io.File
import java.util.concurrent.TimeUnit

sealed class PlaybackError {
    data class NetworkError(val message: String) : PlaybackError()
    data class GeneralError(val message: String) : PlaybackError()
}

class PlaybackService : MediaLibraryService() {
    private var mediaLibrarySession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Data access for Android Auto media tree
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var repository: PodcastRepository

    companion object {
        private const val NOTIFICATION_ID = 123
        private const val CHANNEL_ID = "playback_channel"
        private const val TAG = "PlaybackService"

        // Media tree node IDs
        private const val MEDIA_ROOT_ID = "[rootID]"
        private const val MEDIA_SUBSCRIPTIONS_ID = "[subscriptionsID]"
        private const val MEDIA_DOWNLOADS_ID = "[downloadsID]"
        private const val MEDIA_SEARCH_ID = "[searchID]"
        private const val MEDIA_SUBSCRIPTION_PREFIX = "[sub]"
        private const val MEDIA_SEARCH_PODCAST_PREFIX = "[searchPodcast]"

        private var errorCallback: ((PlaybackException) -> Unit)? = null

        fun setErrorCallback(callback: ((PlaybackException) -> Unit)?) {
            errorCallback = callback
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializeDataAccess()

        val player = ExoPlayer.Builder(this).build()

        // Configure audio attributes for podcast content
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        // Set audioAttributes with handleAudioFocus=true to let ExoPlayer manage audio focus
        player.setAudioAttributes(audioAttributes, true)

        // Handle headphone unplug and audio becoming noisy
        player.setHandleAudioBecomingNoisy(true)

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorCallback?.invoke(error)
                super.onPlayerError(error)
            }
        })

        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, LibrarySessionCallback())
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setNotificationId(NOTIFICATION_ID)
            .build()

        setMediaNotificationProvider(notificationProvider)
    }

    private fun initializeDataAccess() {
        val database = PodcastDatabase.getDatabase(this)
        val podcastDao = database.podcastDao()
        val playbackPositionDao = database.playbackPositionDao()
        subscriptionManager = SubscriptionManager(this, podcastDao)

        val cacheDir = File(cacheDir, "http_rss_cache_auto")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val cache = Cache(cacheDir, 10L * 1024 * 1024) // 10MB for Auto

        val httpClient = OkHttpClient.Builder()
            .cache(cache)
            .addInterceptor(Interceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", "CalmCast/1.0 (Android; Podcast Player)")
                    .build()
                chain.proceed(req)
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val apiService = ItunesApiService(httpClient)
        repository = PodcastRepository(apiService, subscriptionManager, podcastDao, playbackPositionDao)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Playback"
            val descriptionText = "Podcast playback controls"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        mediaLibrarySession?.player?.let { player ->
            if (!player.playWhenReady || player.mediaItemCount == 0) {
                stopSelf()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaLibrarySession

    override fun onDestroy() {
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        super.onDestroy()
    }

    // ==================== MediaLibrarySession.Callback ====================

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {

        // Cached search results for the current search session
        private var searchResults: List<MediaItem> = emptyList()

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId(MEDIA_ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(getString(R.string.app_name))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()

            serviceScope.launch {
                try {
                    val children = when {
                        parentId == MEDIA_ROOT_ID -> getRootChildren()
                        parentId == MEDIA_SUBSCRIPTIONS_ID -> getSubscriptionItems()
                        parentId == MEDIA_DOWNLOADS_ID -> getDownloadItems()
                        parentId == MEDIA_SEARCH_ID -> ImmutableList.copyOf(searchResults)
                        parentId.startsWith(MEDIA_SUBSCRIPTION_PREFIX) -> {
                            val podcastId = parentId.removePrefix(MEDIA_SUBSCRIPTION_PREFIX)
                            getEpisodeItems(podcastId)
                        }
                        parentId.startsWith(MEDIA_SEARCH_PODCAST_PREFIX) -> {
                            val podcastId = parentId.removePrefix(MEDIA_SEARCH_PODCAST_PREFIX)
                            getEpisodeItems(podcastId)
                        }
                        else -> ImmutableList.of()
                    }
                    future.set(LibraryResult.ofItemList(children, params))
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting children for $parentId", e)
                    future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                }
            }

            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // Return an empty result - Android Auto will use onGetChildren for browsing
            return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            // Trigger async search and notify when results are ready
            serviceScope.launch {
                try {
                    val results = performSearch(query)
                    searchResults = results
                    session.notifySearchResultChanged(browser, query, results.size, params)
                } catch (e: Exception) {
                    Log.e(TAG, "Error performing search", e)
                    searchResults = emptyList()
                    session.notifySearchResultChanged(browser, query, 0, params)
                }
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            // If we already have results cached, return them
            if (searchResults.isNotEmpty()) {
                val start = page * pageSize
                val end = minOf(start + pageSize, searchResults.size)
                val pageItems = if (start < searchResults.size) {
                    searchResults.subList(start, end)
                } else {
                    emptyList()
                }
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.copyOf(pageItems), params)
                )
            }

            // Otherwise perform search now
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                try {
                    val results = performSearch(query)
                    searchResults = results
                    val start = page * pageSize
                    val end = minOf(start + pageSize, results.size)
                    val pageItems = if (start < results.size) {
                        results.subList(start, end)
                    } else {
                        emptyList()
                    }
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(pageItems), params))
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting search results", e)
                    future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                }
            }
            return future
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            // Resolve media items that may only have a mediaId (from Android Auto browse)
            // into fully playable items with URIs
            val future = SettableFuture.create<MutableList<MediaItem>>()
            serviceScope.launch {
                try {
                    val resolved = mediaItems.map { item ->
                        if (item.localConfiguration != null) {
                            // Already has a URI, use as-is
                            item
                        } else {
                            // Try to resolve from the media ID
                            resolveMediaItem(item.mediaId) ?: item
                        }
                    }.toMutableList()
                    future.set(resolved)
                } catch (e: Exception) {
                    Log.e(TAG, "Error resolving media items", e)
                    future.set(mediaItems)
                }
            }
            return future
        }

        // ==================== Media Tree Builders ====================

        private fun getRootChildren(): ImmutableList<MediaItem> {
            val subscriptionsItem = MediaItem.Builder()
                .setMediaId(MEDIA_SUBSCRIPTIONS_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(getString(R.string.auto_subscriptions))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS)
                        .build()
                )
                .build()

            val downloadsItem = MediaItem.Builder()
                .setMediaId(MEDIA_DOWNLOADS_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(getString(R.string.auto_downloads))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS)
                        .build()
                )
                .build()

            return ImmutableList.of(subscriptionsItem, downloadsItem)
        }

        private suspend fun getSubscriptionItems(): ImmutableList<MediaItem> {
            val podcasts = subscriptionManager.getSubscriptions()
            val items = podcasts.sortedBy { it.title }.map { podcast ->
                MediaItem.Builder()
                    .setMediaId("$MEDIA_SUBSCRIPTION_PREFIX${podcast.id}")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(podcast.title)
                            .setArtist(podcast.author)
                            .setArtworkUri(podcast.imageUrl?.toUri())
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
                            .build()
                    )
                    .build()
            }
            return ImmutableList.copyOf(items)
        }

        private suspend fun getDownloadItems(): ImmutableList<MediaItem> {
            val database = PodcastDatabase.getDatabase(this@PlaybackService)
            val downloadDao = database.downloadDao()
            val downloads = downloadDao.getAllDownloadsOnce()

            val items = downloads
                .filter { download ->
                    download.status.name != "DELETED" &&
                            download.episode.id.isNotBlank() &&
                            download.episode.title.isNotBlank() &&
                            download.episode.audioUrl.isNotBlank()
                }
                .sortedByDescending { it.episode.publishDateMillis }
                .map { download ->
                    val episode = download.episode
                    val audioUri = (download.downloadUri ?: episode.audioUrl).toUri()

                    MediaItem.Builder()
                        .setMediaId(episode.id)
                        .setUri(audioUri)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(episode.title)
                                .setArtist(episode.podcastTitle)
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                                .setExtras(Bundle().apply {
                                    putString("episodeId", episode.id)
                                    putString("podcastId", episode.podcastId)
                                    putString("podcastTitle", episode.podcastTitle)
                                    putString("description", episode.description)
                                    putString("publishDate", episode.publishDate)
                                    putLong("publishDateMillis", episode.publishDateMillis)
                                    putString("duration", episode.duration)
                                    putString("audioUrl", episode.audioUrl)
                                    putString("downloadedPath", episode.downloadedPath)
                                })
                                .build()
                        )
                        .build()
                }
            return ImmutableList.copyOf(items)
        }

        private suspend fun getEpisodeItems(podcastId: String): ImmutableList<MediaItem> {
            try {
                val result = repository.getPodcastDetails(podcastId).first()
                val podcastWithEpisodes = result.getOrNull() ?: return ImmutableList.of()
                val podcast = podcastWithEpisodes.podcast
                val artworkUri = podcast.imageUrl?.toUri()

                val items = podcastWithEpisodes.episodes.map { episode ->
                    MediaItem.Builder()
                        .setMediaId(episode.id)
                        .setUri(episode.audioUrl.toUri())
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(episode.title)
                                .setArtist(podcast.title)
                                .setArtworkUri(artworkUri)
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                                .setExtras(Bundle().apply {
                                    putString("episodeId", episode.id)
                                    putString("podcastId", episode.podcastId)
                                    putString("podcastTitle", episode.podcastTitle)
                                    putString("description", episode.description)
                                    putString("publishDate", episode.publishDate)
                                    putLong("publishDateMillis", episode.publishDateMillis)
                                    putString("duration", episode.duration)
                                    putString("audioUrl", episode.audioUrl)
                                    putString("downloadedPath", episode.downloadedPath)
                                })
                                .build()
                        )
                        .build()
                }
                return ImmutableList.copyOf(items)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting episodes for podcast $podcastId", e)
                return ImmutableList.of()
            }
        }

        private suspend fun performSearch(query: String): List<MediaItem> {
            if (query.isBlank()) return emptyList()

            try {
                val searchResult = repository.searchPodcasts(query).first()
                val podcasts = searchResult.getOrNull() ?: return emptyList()

                return podcasts.map { podcast ->
                    MediaItem.Builder()
                        .setMediaId("$MEDIA_SEARCH_PODCAST_PREFIX${podcast.id}")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(podcast.title)
                                .setArtist(podcast.author)
                                .setArtworkUri(podcast.imageUrl?.toUri())
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
                                .build()
                        )
                        .build()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error performing search for '$query'", e)
                return emptyList()
            }
        }

        private suspend fun resolveMediaItem(mediaId: String): MediaItem? {
            // Check downloads first
            try {
                val database = PodcastDatabase.getDatabase(this@PlaybackService)
                val downloadDao = database.downloadDao()
                val downloads = downloadDao.getAllDownloadsOnce()
                val download = downloads.find { it.episode.id == mediaId }
                if (download != null) {
                    val episode = download.episode
                    val audioUri = (download.downloadUri ?: episode.audioUrl).toUri()
                    return MediaItem.Builder()
                        .setMediaId(episode.id)
                        .setUri(audioUri)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(episode.title)
                                .setArtist(episode.podcastTitle)
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                                .setExtras(Bundle().apply {
                                    putString("episodeId", episode.id)
                                    putString("podcastId", episode.podcastId)
                                    putString("podcastTitle", episode.podcastTitle)
                                    putString("description", episode.description)
                                    putString("publishDate", episode.publishDate)
                                    putLong("publishDateMillis", episode.publishDateMillis)
                                    putString("duration", episode.duration)
                                    putString("audioUrl", episode.audioUrl)
                                    putString("downloadedPath", episode.downloadedPath)
                                })
                                .build()
                        )
                        .build()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving download for $mediaId", e)
            }

            // Not found in downloads - it could be an episode from a podcast
            // The media item would have been built with a URI when browsing episodes,
            // so this case is unlikely. Return null and let the caller handle it.
            return null
        }
    }
}