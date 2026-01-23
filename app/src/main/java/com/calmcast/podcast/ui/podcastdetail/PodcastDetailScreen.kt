package com.calmcast.podcast.ui.podcastdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import com.mudita.mmd.components.bottom_sheet.ModalBottomSheetMMD
import com.calmapps.calmcast.R
import com.calmcast.podcast.data.PlaybackPosition
import com.calmcast.podcast.data.PodcastRepository.Episode
import com.calmcast.podcast.data.Podcast
import com.calmcast.podcast.data.download.Download
import com.calmcast.podcast.data.download.DownloadStatus
import com.calmcast.podcast.ui.common.SafeHtmlText
import com.calmcast.podcast.utils.DateTimeFormatter
import com.calmcast.podcast.ui.common.DashedDivider
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.progress_indicator.CircularProgressIndicatorMMD
import com.mudita.mmd.components.progress_indicator.LinearProgressIndicatorMMD

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastDetailScreen(
    podcast: Podcast,
    episodes: List<Episode> = emptyList(),
    downloads: List<Download> = emptyList(),
    playbackPositions: Map<String, PlaybackPosition> = emptyMap(),
    isLoadingEpisodes: Boolean = false,
    isBuffering: Boolean = false,
    currentPlayingEpisodeId: String? = null,
    onEpisodeClick: (Episode) -> Unit = {},
    onDownloadClick: (Episode) -> Unit = {},
    onDeleteClick: (Episode) -> Unit = {},
    onPauseClick: (Episode) -> Unit = {},
    onCancelClick: (Episode) -> Unit = {},
    onResumeClick: (Episode) -> Unit = {},
    onRefreshClick: () -> Unit = {},
    removeDividers: Boolean = false
) {
    val showDescriptionModal = remember { mutableStateOf(false) }
    var isDescriptionTruncated by remember { mutableStateOf(false) }

    var selectedEpisodeForInfo by remember { mutableStateOf<Episode?>(null) }

    if (showDescriptionModal.value) {
        ModalBottomSheetMMD(
            onDismissRequest = { showDescriptionModal.value = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                SafeHtmlText(
                    html = podcast.description
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (selectedEpisodeForInfo != null) {
        ModalBottomSheetMMD(
            onDismissRequest = { selectedEpisodeForInfo = null }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp)
            ) {
                Text(
                    text = selectedEpisodeForInfo?.title?.takeIf { it.isNotBlank() } ?: "Episode",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    modifier = Modifier.padding(bottom = 16.dp, top = 16.dp)
                )
                if (!selectedEpisodeForInfo?.description.isNullOrEmpty()) {
                    SafeHtmlText(
                        html = selectedEpisodeForInfo?.description ?: "",
                        forceBlackText = true
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    val downloadsByEpisodeId = remember(downloads) { downloads.associateBy { it.episode.id } }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                SafeHtmlText(
                    html = podcast.description,
                    maxLines = 1,
                    onTruncated = { isTruncated -> isDescriptionTruncated = isTruncated }
                )
            }

            Column {
                if (isDescriptionTruncated) {
                    Text(
                        text = "read more",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showDescriptionModal.value = true }
                    )
                }
            }
        }

        Column(
            modifier = Modifier.padding(start = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Episodes (${episodes.size})",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 16.dp)
                ) {
                    if (isLoadingEpisodes) {
                        CircularProgressIndicatorMMD(
                            modifier = Modifier
                                .size(20.dp)
                                .padding(end = 8.dp)
                        )
                    }
                    IconButton(
                        onClick = onRefreshClick,
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Refresh episodes",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDividerMMD(thickness = 3.dp)
        }

        LazyColumnMMD(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, end = 0.dp, top = 0.dp, bottom = 0.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when {
                isLoadingEpisodes && episodes.isEmpty() -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicatorMMD()
                        }
                    }
                }

                episodes.isEmpty() -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(stringResource(R.string.no_episodes))
                        }
                    }
                }

                else -> {
                    itemsIndexed(episodes, key = { _, episode -> episode.id }) { index, episode ->
                        val download = downloadsByEpisodeId[episode.id]
                        val playbackPosition = playbackPositions[episode.id]
                        val isCurrentlyPlaying = episode.id == currentPlayingEpisodeId

                        EpisodeItem(
                            episode = episode,
                            download = download,
                            isLastItem = index == episodes.lastIndex,
                            playbackPosition = playbackPosition,
                            isCurrentlyPlaying = isCurrentlyPlaying,
                            isBuffering = isBuffering && isCurrentlyPlaying,
                            onClick = { onEpisodeClick(episode) },
                            onDownloadClick = { onDownloadClick(episode) },
                            onDeleteClick = { onDeleteClick(episode) },
                            onPauseClick = { onPauseClick(episode) },
                            onCancelClick = { onCancelClick(episode) },
                            onResumeClick = { onResumeClick(episode) },
                            onInfoClick = { selectedEpisodeForInfo = episode }, // Pass event up
                            removeDividers = removeDividers,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeItem(
    episode: Episode,
    download: Download?,
    isLastItem: Boolean,
    playbackPosition: PlaybackPosition?,
    isCurrentlyPlaying: Boolean,
    isBuffering: Boolean = false,
    showPodcastName: Boolean = false,
    onClick: () -> Unit = {},
    onDownloadClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    onPauseClick: () -> Unit = {},
    onCancelClick: () -> Unit = {},
    onResumeClick: () -> Unit = {},
    onInfoClick: () -> Unit = {},
    removeDividers: Boolean = false,
    customPaddingValues: PaddingValues? = null
) {
    val formattedDate = remember(episode.id, episode.publishDate) {
        DateTimeFormatter.formatPublishDate(episode.publishDate)
    }

    fun formatMillis(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    val durationText = remember(episode.id, episode.duration, playbackPosition?.position) {
        val totalDurationStr = DateTimeFormatter.formatDurationFromString(episode.duration)
        val position = playbackPosition?.position ?: 0L

        if (position > 0) {
            "${formatMillis(position)} - $totalDurationStr"
        } else {
            totalDurationStr
        }
    }

    Column(
        modifier = Modifier
            .padding(start = 0.dp, end = 0.dp, top = 0.dp, bottom = 0.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .padding(end = 16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start
            ) {
                if (isCurrentlyPlaying) {
                    if (isBuffering) {
                        Box(
                            modifier = Modifier.size(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicatorMMD()
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Headphones,
                            contentDescription = "Playing",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
                when (download?.status) {
                    DownloadStatus.DOWNLOADED -> {
                        IconButton(
                            onClick = onDeleteClick,
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Delete",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    DownloadStatus.DOWNLOADING -> {
                        Box(
                            modifier = Modifier.size(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicatorMMD()
                        }
                    }
                    DownloadStatus.PAUSED -> {
                        Icon(
                            imageVector = Icons.Outlined.Pause,
                            contentDescription = "Paused",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                    DownloadStatus.FAILED, DownloadStatus.CANCELED, DownloadStatus.STORAGE_UNAVAILABLE -> {
                        IconButton(
                            onClick = onDeleteClick,
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Delete",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    else -> {
                        IconButton(
                            onClick = onDownloadClick,
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = "Download",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        customPaddingValues ?: PaddingValues(
                            start = 0.dp,
                            end = 0.dp,
                            top = 0.dp,
                            bottom = 0.dp
                        )
                    )
            ) {
                Column(
                    modifier = Modifier
                        .padding(
                            PaddingValues(
                                start = 0.dp,
                                end = 16.dp,
                                top = 0.dp,
                                bottom = 0.dp
                            )
                        )
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = episode.title,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (!episode.description.isNullOrEmpty()) {
                            IconButton(
                                onClick = onInfoClick,
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = "View description",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    if (showPodcastName) {
                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = episode.podcastTitle,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .padding(
                            PaddingValues(
                                start = 0.dp,
                                end = 16.dp,
                                top = 0.dp,
                                bottom = 0.dp
                            )
                        )
                ) {
                    when (download?.status) {
                        DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                LinearProgressIndicatorMMD(
                                    progress = { download.progress.coerceIn(0f, 1f) },
                                    modifier = Modifier.weight(1f)
                                )

                                IconButton(
                                    onClick = if (download.status == DownloadStatus.DOWNLOADING) onPauseClick else onResumeClick,
                                    modifier = Modifier.size(16.dp)
                                ) {
                                    Icon(
                                        imageVector = if (download.status == DownloadStatus.DOWNLOADING) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                        contentDescription = if (download.status == DownloadStatus.DOWNLOADING) "Pause" else "Resume",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                IconButton(
                                    onClick = onCancelClick,
                                    modifier = Modifier.size(16.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Cancel",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        DownloadStatus.STORAGE_UNAVAILABLE -> {
                            Text(
                                text = "Ext storage unavailable",
                                fontSize = 12.sp,
                                color = Color.Red,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        else -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = formattedDate,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Normal
                                )

                                Text(
                                    text = durationText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                if (!removeDividers && !isLastItem) {
                    DashedDivider(
                        thickness = 1.dp,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }
    }
}