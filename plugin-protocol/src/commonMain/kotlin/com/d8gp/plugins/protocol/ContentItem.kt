package com.d8gp.plugins.protocol

import kotlinx.serialization.Serializable

// Port of sdk/contentItem.ts — the feed item a plugin returns. `source` is a
// free string (the plugin's sourceKey); the host validates and pins it before
// the item enters the feed.
@Serializable
data class ContentItem(
    val id: String, // the host prefixes this with the plugin's sourceKey
    val source: String,
    val kind: String, // 'native' | 'embed'
    val title: String? = null,
    val text: String? = null,
    val author: String? = null,
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val embedUrl: String? = null,
    val embedMode: String? = null, // 'iframe' | 'page'
    val linkUrl: String,
    val likes: Long? = null,
    val comments: Long? = null,
)
