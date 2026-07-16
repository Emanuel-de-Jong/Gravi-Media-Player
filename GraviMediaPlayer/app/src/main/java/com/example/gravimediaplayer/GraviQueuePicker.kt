package com.example.gravimediaplayer

import kotlin.random.Random

class GraviQueuePicker(
    private val random: Random = Random.Default,
) {
    fun buildGraviQueue(
        items: List<AudioItem>,
        settings: GraviPickerSettings,
        scopeFolderPath: String = "",
    ): List<AudioItem> {
        val safeSettings = settings.sanitized()
        val categoryEntries = buildCategoryEntries(items, safeSettings, scopeFolderPath)
        if (categoryEntries.isEmpty()) return emptyList()

        val allItems = categoryEntries
            .flatMap { it.items }
            .distinctBy { it.audioItem.uriString }
            .sortedBy { it.audioItem.folderPath + "/" + it.audioItem.title }
        var remainingUriStrings = allItems.map { it.audioItem.uriString }.toSet()
        val selectedItems = mutableListOf<AudioItem>()

        while (selectedItems.size < safeSettings.queueEntries) {
            if (remainingUriStrings.isEmpty()) {
                remainingUriStrings = allItems.map { it.audioItem.uriString }.toSet()
            }

            val eligibleCategoryEntries = categoryEntries.filter { categoryEntry ->
                categoryEntry.items.any { it.audioItem.uriString in remainingUriStrings }
            }
            if (eligibleCategoryEntries.isEmpty()) break

            val selectedCategory = pickWeighted(eligibleCategoryEntries) { it.weight }
            val selectedItem = pickWeighted(
                selectedCategory.items.filter { it.audioItem.uriString in remainingUriStrings }
            ) { selectedCategory.itemWeightsByUri[it.audioItem.uriString] ?: 1.0 }
            remainingUriStrings = remainingUriStrings - selectedItem.audioItem.uriString
            selectedItems.add(selectedItem.audioItem)
        }

        return selectedItems
    }

    fun buildTrueShuffleQueue(items: List<AudioItem>, queueEntries: Int): List<AudioItem> {
        val distinctItems = items.distinctBy { it.uriString }
        if (distinctItems.isEmpty()) return emptyList()

        val selectedItems = mutableListOf<AudioItem>()
        var remainingItems = distinctItems.shuffled(random)
        val safeQueueEntries = queueEntries.coerceAtLeast(1)

        while (selectedItems.size < safeQueueEntries) {
            if (remainingItems.isEmpty()) {
                remainingItems = distinctItems.shuffled(random)
            }
            selectedItems.add(remainingItems.first())
            remainingItems = remainingItems.drop(1)
        }

        return selectedItems
    }

    private fun buildCategoryEntries(
        items: List<AudioItem>,
        settings: GraviPickerSettings,
        scopeFolderPath: String,
    ): List<GraviCategoryEntry> {
        val normalizedScopeFolderPath = scopeFolderPath.normalizedFolderPath()
        val pickerItems = items
            .distinctBy { it.uriString }
            .map {
                GraviPickerItem(
                    audioItem = it,
                    folderPath = it.folderPath.normalizedFolderPath().relativeToScopeFolder(
                        normalizedScopeFolderPath
                    ),
                )
            }
            .filterNot { isBlacklisted(it.folderPath, settings.blacklistFolders) }
        if (pickerItems.isEmpty()) return emptyList()

        val itemsByFolder = pickerItems.groupBy { it.folderPath }
        val folderPaths = buildFolderPaths(pickerItems)
        val descendantCountByFolder = buildDescendantCountByFolder(folderPaths, itemsByFolder)
        val categoryFolders = folderPaths.filter { folderPath ->
            val folderDepth = folderPath.folderParts().size
            val targetDepth = resolveTargetDepth(folderPath, settings)
            val hasDirectItems = itemsByFolder.containsKey(folderPath)
            val hasDescendantItems = descendantCountByFolder.getOrDefault(folderPath, 0) > 0

            when {
                hasDirectItems && folderDepth <= targetDepth -> true
                folderDepth == targetDepth && hasDescendantItems -> true
                else -> false
            }
        }

        val categoryFolderSet = categoryFolders.toSet()
        var categoryEntries = categoryFolders.mapNotNull { categoryFolder ->
            val categoryItems = pickerItems.filter { pickerItem ->
                if (!isSameOrDescendant(pickerItem.folderPath, categoryFolder)) return@filter false
                val blockingCategory = parentFoldersBetween(pickerItem.folderPath, categoryFolder)
                    .firstOrNull { parentFolder ->
                        parentFolder in categoryFolderSet &&
                                parentFolder.folderParts().size <= resolveTargetDepth(
                            parentFolder,
                            settings
                        )
                    }
                blockingCategory == null
            }.sortedBy { it.audioItem.folderPath + "/" + it.audioItem.title }

            if (categoryItems.isEmpty()) return@mapNotNull null

            val categoryWeight = if (settings.evenOddsMinFileCount != -1 &&
                categoryItems.size <= settings.evenOddsMinFileCount
            ) {
                1.0 / settings.lessLikelyDivisor
            } else {
                1.0
            }

            GraviCategoryEntry(
                folderPath = categoryFolder,
                items = categoryItems,
                itemWeightsByUri = buildItemWeights(categoryFolder, categoryItems, settings),
                weight = categoryWeight,
            )
        }

        if (settings.parentOdds) {
            categoryEntries = applyParentOdds(categoryEntries)
        }

        return categoryEntries
    }

    private fun buildFolderPaths(items: List<GraviPickerItem>): List<String> {
        return items
            .flatMap { pickerItem ->
                val parts = pickerItem.folderPath.folderParts()
                if (parts.isEmpty()) listOf("") else parts.indices.map { index ->
                    parts.take(index + 1).joinToString("/")
                }
            }
            .toSet()
            .sortedWith(compareBy<String> { it.folderParts().size }.thenBy { it })
    }

    private fun buildDescendantCountByFolder(
        folderPaths: List<String>,
        itemsByFolder: Map<String, List<GraviPickerItem>>,
    ): Map<String, Int> {
        return folderPaths.associateWith { folderPath ->
            itemsByFolder.entries.sumOf { (itemFolder, folderItems) ->
                if (isSameOrDescendant(itemFolder, folderPath)) folderItems.size else 0
            }
        }
    }

    private fun buildItemWeights(
        categoryFolder: String,
        categoryItems: List<GraviPickerItem>,
        settings: GraviPickerSettings,
    ): Map<String, Double> {
        val itemsByParentFolder = categoryItems.groupBy { it.folderPath }
        val descendantCountByFolder = mutableMapOf<String, Int>()
        categoryItems.forEach { pickerItem ->
            foldersFromChildToParent(pickerItem.folderPath, categoryFolder).forEach { folderPath ->
                descendantCountByFolder[folderPath] =
                    descendantCountByFolder.getOrDefault(folderPath, 0) + 1
            }
        }
        val childrenByFolder = mutableMapOf<String, Set<String>>()
        itemsByParentFolder.keys.forEach { folderPath ->
            val parts = folderPath.folderParts()
            val categoryParts = categoryFolder.folderParts()
            for (index in categoryParts.size until parts.size) {
                val parentFolder = parts.take(index).joinToString("/")
                val childFolder = parts.take(index + 1).joinToString("/")
                childrenByFolder[parentFolder] =
                    childrenByFolder.getOrDefault(parentFolder, emptySet()) + childFolder
            }
        }

        return categoryItems.associate { pickerItem ->
            var itemWeight = 1.0
            if (settings.childOdds) {
                val parts = pickerItem.folderPath.folderParts()
                val categoryParts = categoryFolder.folderParts()
                for (index in categoryParts.size until parts.size) {
                    val parentFolder = parts.take(index).joinToString("/")
                    val childFolders = childrenByFolder.getOrDefault(parentFolder, emptySet())
                    if (childFolders.isNotEmpty()) {
                        itemWeight *= 1.0 / childFolders.size
                    }
                }
            }

            if (settings.evenOddsMinFileCount != -1) {
                foldersFromChildToParent(
                    pickerItem.folderPath,
                    categoryFolder
                ).forEach { folderPath ->
                    val descendantCount = descendantCountByFolder.getOrDefault(folderPath, 0)
                    if (descendantCount <= settings.evenOddsMinFileCount) {
                        itemWeight *= 1.0 / settings.lessLikelyDivisor
                    }
                }
            }

            pickerItem.audioItem.uriString to itemWeight
        }
    }

    private fun applyParentOdds(categoryEntries: List<GraviCategoryEntry>): List<GraviCategoryEntry> {
        val childrenByFolder = mutableMapOf<String, Set<String>>()
        categoryEntries.forEach { categoryEntry ->
            val parts = categoryEntry.folderPath.folderParts()
            for (index in parts.indices) {
                val parentFolder = parts.take(index).joinToString("/")
                val childFolder = parts.take(index + 1).joinToString("/")
                childrenByFolder[parentFolder] =
                    childrenByFolder.getOrDefault(parentFolder, emptySet()) + childFolder
            }
        }

        return categoryEntries.map { categoryEntry ->
            var categoryMultiplier = 1.0
            val parts = categoryEntry.folderPath.folderParts()
            for (index in parts.indices) {
                val parentFolder = parts.take(index).joinToString("/")
                val childFolders = childrenByFolder.getOrDefault(parentFolder, emptySet())
                if (childFolders.isNotEmpty()) {
                    categoryMultiplier *= 1.0 / childFolders.size
                }
            }
            categoryEntry.copy(weight = categoryEntry.weight * categoryMultiplier)
        }
    }

    private fun resolveTargetDepth(folderPath: String, settings: GraviPickerSettings): Int {
        val normalizedFolderPath = folderPath.normalizedFolderPath()
        var targetDepth = settings.depth
        settings.edgeCaseFolderDepths.forEach { (edgeCaseFolder, depthIncrement) ->
            if (normalizedFolderPath == edgeCaseFolder || normalizedFolderPath.startsWith("$edgeCaseFolder/")) {
                targetDepth = maxOf(targetDepth, settings.depth + depthIncrement)
            }
        }
        return targetDepth
    }

    private fun isBlacklisted(folderPath: String, blacklistFolders: Set<String>): Boolean {
        return foldersFromChildToParent(folderPath, "").any { it in blacklistFolders }
    }

    private fun isSameOrDescendant(folderPath: String, parentFolderPath: String): Boolean {
        val normalizedFolderPath = folderPath.normalizedFolderPath()
        val normalizedParentFolderPath = parentFolderPath.normalizedFolderPath()
        return normalizedFolderPath == normalizedParentFolderPath ||
                normalizedParentFolderPath.isBlank() ||
                normalizedFolderPath.startsWith("$normalizedParentFolderPath/")
    }

    private fun parentFoldersBetween(folderPath: String, parentFolderPath: String): List<String> {
        val folderParts = folderPath.folderParts()
        val parentParts = parentFolderPath.folderParts()
        if (folderParts.size <= parentParts.size) return emptyList()

        return (parentParts.size + 1 until folderParts.size).map { index ->
            folderParts.take(index).joinToString("/")
        }
    }

    private fun foldersFromChildToParent(
        folderPath: String,
        parentFolderPath: String
    ): List<String> {
        val folderParts = folderPath.folderParts()
        val parentParts = parentFolderPath.folderParts()
        return (folderParts.size downTo parentParts.size).map { index ->
            folderParts.take(index).joinToString("/")
        }
    }

    private fun String.relativeToScopeFolder(scopeFolderPath: String): String {
        val normalizedPath = normalizedFolderPath()
        val normalizedScopeFolderPath = scopeFolderPath.normalizedFolderPath()
        if (normalizedScopeFolderPath.isBlank()) return normalizedPath
        if (normalizedPath == normalizedScopeFolderPath) return ""
        return normalizedPath.removePrefix("$normalizedScopeFolderPath/")
    }

    private fun <Item> pickWeighted(items: List<Item>, weight: (Item) -> Double): Item {
        val weights = items.map { weight(it).coerceAtLeast(0.0) }
        val totalWeight = weights.sum()
        if (totalWeight <= 0.0) return items[random.nextInt(items.size)]

        var selectedWeight = random.nextDouble(totalWeight)
        items.forEachIndexed { index, item ->
            selectedWeight -= weights[index]
            if (selectedWeight <= 0.0) return item
        }

        return items.last()
    }
}