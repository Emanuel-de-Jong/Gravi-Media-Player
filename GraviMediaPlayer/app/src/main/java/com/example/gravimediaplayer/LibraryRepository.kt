package com.example.gravimediaplayer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore

class LibraryRepository(private val context: Context) {
    private val recursiveAudioItemsCache = mutableMapOf<String, List<AudioItem>>()

    fun loadBrowserEntries(rootUriString: String, folderStack: List<String>): List<BrowserEntry> {
        loadMediaStoreAudioItems(rootUriString, readTags = false)?.let { items ->
            return buildMediaStoreBrowserEntries(items, folderStack)
        }

        val rootUri = Uri.parse(rootUriString)
        val folderDocumentId = findFolderDocumentId(rootUri, folderStack) ?: return emptyList()
        val folderPath = folderStack.joinToString("/")
        return queryChildDocuments(rootUri, folderDocumentId)
            .filter { it.isDirectory || isAudioFile(it.name) }
            .sortedWith(compareBy<LibraryDocument> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .mapNotNull { document ->
                BrowserEntry(
                    name = document.name,
                    uriString = document.uriString,
                    isDirectory = document.isDirectory,
                    audioItem = if (!document.isDirectory) AudioItem(
                        document.uriString,
                        document.name,
                        folderPath
                    ) else null,
                )
            }
    }

    fun loadRecursiveAudioItems(
        rootUriString: String,
        folderStack: List<String>,
        readTags: Boolean = false,
    ): List<AudioItem> {
        val cacheKey = listOf(rootUriString, folderStack.joinToString("/"), readTags.toString())
            .joinToString("|")
        recursiveAudioItemsCache[cacheKey]?.let { return it }

        loadMediaStoreAudioItems(rootUriString, readTags)?.let { items ->
            val folderPath = folderStack.joinToString("/")
            val filteredItems = if (folderPath.isBlank()) items else items.filter {
                it.folderPath == folderPath || it.folderPath.startsWith("$folderPath/")
            }
            recursiveAudioItemsCache[cacheKey] = filteredItems
            return filteredItems
        }

        val rootUri = Uri.parse(rootUriString)
        val folderDocumentId = findFolderDocumentId(rootUri, folderStack) ?: return emptyList()
        val items =
            collectAudioItems(rootUri, folderDocumentId, folderStack.joinToString("/"), readTags)
        recursiveAudioItemsCache[cacheKey] = items
        return items
    }

    fun buildTagGroups(items: List<AudioItem>): List<TagGroup> {
        return items
            .flatMap { item -> item.tags.map { tag -> tag to item } }
            .groupBy({ it.first }, { it.second })
            .map { TagGroup(it.key, it.value.distinctBy { item -> item.uriString }) }
            .sortedBy { it.name.lowercase() }
    }

    fun clearSessionCache() {
        recursiveAudioItemsCache.clear()
    }

    private fun buildMediaStoreBrowserEntries(
        items: List<AudioItem>,
        folderStack: List<String>,
    ): List<BrowserEntry> {
        val folderPath = folderStack.joinToString("/")
        val childFolders = mutableSetOf<String>()
        val childFiles = mutableListOf<AudioItem>()

        items.forEach { item ->
            if (item.folderPath == folderPath) {
                childFiles += item
            } else {
                val childPath = if (folderPath.isBlank()) item.folderPath else item.folderPath
                    .removePrefix("$folderPath/")
                val childFolderName = childPath.substringBefore('/').takeIf { it.isNotBlank() }
                if (childFolderName != null) childFolders += childFolderName
            }
        }

        return childFolders.map { folderName ->
            BrowserEntry(
                name = folderName,
                uriString = listOf(folderPath, folderName).filter { it.isNotBlank() }
                    .joinToString("/"),
                isDirectory = true,
            )
        }.plus(
            childFiles.map { item ->
                BrowserEntry(
                    name = item.title,
                    uriString = item.uriString,
                    isDirectory = false,
                    audioItem = item,
                )
            }
        ).sortedWith(compareBy<BrowserEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    private fun loadMediaStoreAudioItems(
        rootUriString: String,
        readTags: Boolean
    ): List<AudioItem>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (!hasMediaStorePermission()) return null

        val rootPath = mediaStoreRootPath(rootUriString) ?: return null
        val normalizedRootPath = rootPath.trim('/').let {
            if (it.isBlank()) "" else "$it/"
        }
        val items = mutableListOf<AudioItem>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
        )
        val selection = if (normalizedRootPath.isBlank()) {
            "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        } else {
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        }
        val selectionArgs =
            if (normalizedRootPath.isBlank()) null else arrayOf("$normalizedRootPath%")

        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Audio.Media.RELATIVE_PATH} ASC, ${MediaStore.Audio.Media.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val relativePathColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val name = cursor.getStringOrEmpty(nameColumn)
                if (!isAudioFile(name)) continue

                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                val relativePath = cursor.getStringOrEmpty(relativePathColumn)
                    .trim('/')
                val folderPath = relativePath
                    .removePrefix(rootPath.trim('/'))
                    .trim('/')
                items += AudioItem(
                    uri.toString(),
                    name,
                    folderPath,
                    if (readTags) readGenreTags(uri) else emptyList(),
                )
            }
        }

        return items
    }

    private fun mediaStoreRootPath(rootUriString: String): String? {
        val uri = Uri.parse(rootUriString)
        val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return null
        val parts = treeDocumentId.split(':', limit = 2)
        if (parts.firstOrNull() != "primary") return null
        return parts.getOrNull(1).orEmpty()
    }

    private fun hasMediaStorePermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun collectAudioItems(
        rootUri: Uri,
        folderDocumentId: String,
        folderPath: String,
        readTags: Boolean
    ): List<AudioItem> {
        return queryChildDocuments(rootUri, folderDocumentId)
            .sortedWith(compareBy<LibraryDocument> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .flatMap { document ->
                val name = document.name
                val childFolderPath =
                    listOf(folderPath, name).filter { it.isNotBlank() }.joinToString("/")
                when {
                    document.isDirectory -> collectAudioItems(
                        rootUri,
                        document.documentId,
                        childFolderPath,
                        readTags
                    )

                    isAudioFile(name) -> listOf(
                        AudioItem(
                            document.uriString,
                            name,
                            folderPath,
                            if (readTags) readGenreTags(Uri.parse(document.uriString)) else emptyList(),
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

    private fun findFolderDocumentId(rootUri: Uri, folderStack: List<String>): String? {
        var folderDocumentId =
            runCatching { DocumentsContract.getTreeDocumentId(rootUri) }.getOrNull()
                ?: return null
        folderStack.forEach { folderName ->
            folderDocumentId = queryChildDocuments(rootUri, folderDocumentId)
                .firstOrNull { it.isDirectory && it.name == folderName }
                ?.documentId ?: return null
        }
        return folderDocumentId
    }

    private fun queryChildDocuments(rootUri: Uri, folderDocumentId: String): List<LibraryDocument> {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, folderDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val documents = mutableListOf<LibraryDocument>()
        context.contentResolver.query(childUri, projection, null, null, null)?.use { cursor ->
            val documentIdColumn =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeTypeColumn =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val documentId = cursor.getStringOrEmpty(documentIdColumn)
                val name = cursor.getStringOrEmpty(nameColumn)
                val mimeType = cursor.getStringOrEmpty(mimeTypeColumn)
                if (documentId.isBlank() || name.isBlank()) continue

                documents += LibraryDocument(
                    documentId,
                    name,
                    DocumentsContract.buildDocumentUriUsingTree(rootUri, documentId).toString(),
                    mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                )
            }
        }
        return documents
    }

    private fun isAudioFile(name: String): Boolean {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension in audioExtensions
    }

    private fun Cursor.getStringOrEmpty(columnIndex: Int): String {
        return if (isNull(columnIndex)) "" else getString(columnIndex).orEmpty()
    }

    companion object {
        val audioExtensions =
            setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus", "mid", "midi")
    }
}

private data class LibraryDocument(
    val documentId: String,
    val name: String,
    val uriString: String,
    val isDirectory: Boolean,
)