package com.example.gravimediaplayer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

class LibraryRepository(private val context: Context) {
    fun loadBrowserEntries(rootUriString: String, folderStack: List<String>): List<BrowserEntry> {
        val folder = findFolder(rootUriString, folderStack) ?: return emptyList()
        val folderPath = folderStack.joinToString("/")
        return folder.listFiles()
            .filter { it.isDirectory || isAudioFile(it.name.orEmpty()) }
            .sortedAudioFirst()
            .mapNotNull { document ->
                val name = document.name ?: return@mapNotNull null
                BrowserEntry(
                    name = name,
                    uriString = document.uri.toString(),
                    isDirectory = document.isDirectory,
                    audioItem = if (document.isFile) AudioItem(
                        document.uri.toString(),
                        name,
                        folderPath
                    ) else null,
                    trackCount = if (document.isDirectory) countAudioFiles(document) else null,
                )
            }
    }

    fun loadRecursiveAudioItems(
        rootUriString: String,
        folderStack: List<String>,
        readTags: Boolean = false,
    ): List<AudioItem> {
        val folder = findFolder(rootUriString, folderStack) ?: return emptyList()
        return collectAudioItems(folder, folderStack.joinToString("/"), readTags)
    }

    fun buildTagGroups(items: List<AudioItem>): List<TagGroup> {
        return items
            .flatMap { item -> item.tags.map { tag -> tag to item } }
            .groupBy({ it.first }, { it.second })
            .map { TagGroup(it.key, it.value.distinctBy { item -> item.uriString }) }
            .sortedBy { it.name.lowercase() }
    }

    private fun collectAudioItems(
        folder: DocumentFile,
        folderPath: String,
        readTags: Boolean
    ): List<AudioItem> {
        return folder.listFiles()
            .asList()
            .sortedAudioFirst()
            .flatMap { document ->
                val name = document.name.orEmpty()
                val childFolderPath =
                    listOf(folderPath, name).filter { it.isNotBlank() }.joinToString("/")
                when {
                    document.isDirectory -> collectAudioItems(document, childFolderPath, readTags)
                    document.isFile && isAudioFile(name) -> listOf(
                        AudioItem(
                            document.uri.toString(),
                            name,
                            folderPath,
                            if (readTags) readGenreTags(document.uri) else emptyList(),
                        )
                    )

                    else -> emptyList()
                }
            }
    }

    private fun readGenreTags(uri: Uri): List<String> {
        val retriever = MediaMetadataRetriever()
        return try {
            runCatching {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                    .orEmpty()
                    .split('|')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
            }.getOrDefault(emptyList())
        } finally {
            retriever.release()
        }
    }

    private fun countAudioFiles(folder: DocumentFile): Int {
        return folder.listFiles().sumOf { document ->
            when {
                document.isDirectory -> countAudioFiles(document)
                document.isFile && isAudioFile(document.name.orEmpty()) -> 1
                else -> 0
            }
        }
    }

    private fun findFolder(rootUriString: String, folderStack: List<String>): DocumentFile? {
        var folder = DocumentFile.fromTreeUri(context, Uri.parse(rootUriString)) ?: return null
        folderStack.forEach { folderName ->
            folder = folder.listFiles().firstOrNull { it.isDirectory && it.name == folderName }
                ?: return null
        }
        return folder
    }

    private fun Iterable<DocumentFile>.sortedAudioFirst(): List<DocumentFile> {
        return sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy {
            it.name.orEmpty().lowercase()
        })
    }

    private fun isAudioFile(name: String): Boolean {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension in audioExtensions
    }

    companion object {
        val audioExtensions =
            setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus", "mid", "midi")
    }
}