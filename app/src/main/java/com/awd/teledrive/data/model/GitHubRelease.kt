package com.awd.teledrive.data.model

import kotlinx.serialization.Serializable

@Serializable
data class GitHubAsset(
    val name: String = "",
    val browser_download_url: String = "",
    val size: Long = 0L
)

@Serializable
data class GitHubRelease(
    val id: Long = 0L,
    val tag_name: String = "",
    val name: String = "",
    val body: String = "",
    val html_url: String = "",
    val published_at: String = "",
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList()
)
