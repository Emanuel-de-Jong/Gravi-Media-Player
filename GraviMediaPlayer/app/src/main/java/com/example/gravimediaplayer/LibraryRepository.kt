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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

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

    fun loadCachedGenreAudioItems(rootUriString: String): List<AudioItem> {
        val audioFiles = loadAudioFiles(rootUriString)
        val cachedFiles = loadGenreCache(rootUriString)
            .associateBy { it.uriString }
        val updatedFiles = audioFiles.map { audioFile ->
            val cachedFile = cachedFiles[audioFile.uriString]
            if (cachedFile != null && cachedFile.matches(audioFile)) {
                cachedFile.copy(
                    title = audioFile.title,
                    folderPath = audioFile.folderPath,
                )
            } else {
                GenreCacheFile(
                    audioFile.uriString,
                    audioFile.title,
                    audioFile.folderPath,
                    audioFile.lastModifiedMs,
                    audioFile.sizeBytes,
                    readGenreTags(Uri.parse(audioFile.uriString)),
                )
            }
        }
        saveGenreCache(rootUriString, updatedFiles)
        return updatedFiles.map { cachedFile ->
            AudioItem(
                cachedFile.uriString,
                cachedFile.title,
                cachedFile.folderPath,
                cachedFile.tags,
            )
        }
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
        return loadMediaStoreAudioFiles(rootUriString)?.map { audioFile ->
            AudioItem(
                audioFile.uriString,
                audioFile.title,
                audioFile.folderPath,
                if (readTags) readGenreTags(Uri.parse(audioFile.uriString)) else emptyList(),
            )
        }
    }

    private fun loadMediaStoreAudioFiles(rootUriString: String): List<AudioFileSnapshot>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (!hasMediaStorePermission()) return null

        val rootPath = mediaStoreRootPath(rootUriString) ?: return null
        val normalizedRootPath = rootPath.trim('/').let {
            if (it.isBlank()) "" else "$it/"
        }
        val audioFiles = mutableListOf<AudioFileSnapshot>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.SIZE,
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
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            while (cursor.moveToNext()) {
                val name = cursor.getStringOrEmpty(nameColumn)
                if (!isAudioFile(name)) continue

                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                val relativePath = cursor.getStringOrEmpty(relativePathColumn)
                    .trim('/')
                val folderPath = relativePath
                    .removePrefix(rootPath.trim('/'))
                    .trim('/')
                audioFiles += AudioFileSnapshot(
                    uri.toString(),
                    name,
                    folderPath,
                    cursor.getLongOrZero(modifiedColumn) * 1000,
                    cursor.getLongOrZero(sizeColumn),
                )
            }
        }

        return audioFiles
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
        return collectAudioFiles(rootUri, folderDocumentId, folderPath).map { audioFile ->
            AudioItem(
                audioFile.uriString,
                audioFile.title,
                audioFile.folderPath,
                if (readTags) readGenreTags(Uri.parse(audioFile.uriString)) else emptyList(),
            )
        }
    }

    private fun loadAudioFiles(rootUriString: String): List<AudioFileSnapshot> {
        loadMediaStoreAudioFiles(rootUriString)?.let { return it }

        val rootUri = Uri.parse(rootUriString)
        val folderDocumentId = findFolderDocumentId(rootUri, emptyList()) ?: return emptyList()
        return collectAudioFiles(rootUri, folderDocumentId, "")
    }

    private fun collectAudioFiles(
        rootUri: Uri,
        folderDocumentId: String,
        folderPath: String,
    ): List<AudioFileSnapshot> {
        return queryChildDocuments(rootUri, folderDocumentId)
            .sortedWith(compareBy<LibraryDocument> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .flatMap { document ->
                val name = document.name
                val childFolderPath =
                    listOf(folderPath, name).filter { it.isNotBlank() }.joinToString("/")
                when {
                    document.isDirectory -> collectAudioFiles(
                        rootUri,
                        document.documentId,
                        childFolderPath,
                    )

                    isAudioFile(name) -> listOf(
                        AudioFileSnapshot(
                            document.uriString,
                            name,
                            folderPath,
                            document.lastModifiedMs,
                            document.sizeBytes,
                        )
                    )

                    else -> emptyList()
                }
            }
    }

    private fun loadGenreCache(rootUriString: String): List<GenreCacheFile> {
        val cacheFile = genreCacheFile(rootUriString)
        if (!cacheFile.exists()) return emptyList()

        return runCatching {
            val json = JSONObject(cacheFile.readText())
            if (json.optString("rootUriString") != rootUriString) return emptyList()

            val files = json.optJSONArray("files") ?: JSONArray()
            (0 until files.length()).mapNotNull { index ->
                val file = files.optJSONObject(index) ?: return@mapNotNull null
                GenreCacheFile(
                    file.optString("uriString"),
                    file.optString("title"),
                    file.optString("folderPath"),
                    file.optLong("lastModifiedMs"),
                    file.optLong("sizeBytes"),
                    file.optJSONArray("tags").orEmptyStringList(),
                ).takeIf { it.uriString.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveGenreCache(rootUriString: String, files: List<GenreCacheFile>) {
        val jsonFiles = JSONArray()
        files.forEach { file ->
            jsonFiles.put(
                JSONObject()
                    .put("uriString", file.uriString)
                    .put("title", file.title)
                    .put("folderPath", file.folderPath)
                    .put("lastModifiedMs", file.lastModifiedMs)
                    .put("sizeBytes", file.sizeBytes)
                    .put("tags", JSONArray(file.tags))
            )
        }
        val json = JSONObject()
            .put("rootUriString", rootUriString)
            .put("scannedAtMs", System.currentTimeMillis())
            .put("files", jsonFiles)
        genreCacheFile(rootUriString).writeText(json.toString())
    }

    private fun genreCacheFile(rootUriString: String): File {
        return File(context.filesDir, "genre_cache_${rootUriString.sha256()}.json")
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
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val documents = mutableListOf<LibraryDocument>()
        context.contentResolver.query(childUri, projection, null, null, null)?.use { cursor ->
            val documentIdColumn =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeTypeColumn =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modifiedColumn =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
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
                    cursor.getLongOrZero(modifiedColumn),
                    cursor.getLongOrZero(sizeColumn),
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

    private fun Cursor.getLongOrZero(columnIndex: Int): Long {
        return if (isNull(columnIndex)) 0 else getLong(columnIndex)
    }

    private fun JSONArray?.orEmptyStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotBlank() } }
    }

    private fun String.sha256(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
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
    val lastModifiedMs: Long,
    val sizeBytes: Long,
)

private data class AudioFileSnapshot(
    val uriString: String,
    val title: String,
    val folderPath: String,
    val lastModifiedMs: Long,
    val sizeBytes: Long,
)

private data class GenreCacheFile(
    val uriString: String,
    val title: String,
    val folderPath: String,
    val lastModifiedMs: Long,
    val sizeBytes: Long,
    val tags: List<String>,
) {
    fun matches(audioFile: AudioFileSnapshot): Boolean {
        return uriString == audioFile.uriString &&
                lastModifiedMs == audioFile.lastModifiedMs &&
                sizeBytes == audioFile.sizeBytes
    }
}