package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.database.model.DownloadComic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadFilter(
    val status: String,
)

data class DownloadEditState(
    val editing: Boolean = false,
    val selectedIds: Set<Int> = emptySet()
)

enum class DownloadGroupSection {
    Complete,
    Active,
    Error
}

data class DownloadComicGroup(
    val key: String,
    val parentId: Int,
    val parentName: String,
    val chapters: List<DownloadComic>
) {
    val sortedChapters: List<DownloadComic> = chapters.sortedWith(
        compareBy<DownloadComic> { it.chapterIndex }
            .thenBy { it.createTime }
            .thenBy { it.id }
    )
    val primary: DownloadComic = sortedChapters.first()
    val displayName: String = parentName.ifBlank { primary.name }
    val coverComic: DownloadComic = sortedChapters.firstOrNull { it.coverPath.isNotBlank() } ?: primary
    val authorList: List<String> = primary.authorList
    val ids: List<Int> = sortedChapters.map { it.id }
    val chapterSize: Int = sortedChapters.size
    val totalChapters: Int = sortedChapters
        .maxOfOrNull { it.chapterCount.coerceAtLeast(1) }
        ?.coerceAtLeast(chapterSize)
        ?: chapterSize
    val latestCreateTime: Long = sortedChapters.maxOfOrNull { it.createTime } ?: 0L
    val progress: Float = if (sortedChapters.isEmpty()) {
        0f
    } else {
        sortedChapters.map { it.effectiveProgress }.average().toFloat()
    }
    val completeCount: Int = sortedChapters.count { it.status == "complete" }
    val downloadingCount: Int = sortedChapters.count { it.status == "downloading" }
    val pendingCount: Int = sortedChapters.count { it.status == "pending" }
    val pausedCount: Int = sortedChapters.count { it.status == "paused" }
    val errorCount: Int = sortedChapters.count { it.status == "error" }
    val activeCount: Int = downloadingCount + pendingCount + pausedCount
    val section: DownloadGroupSection = when {
        activeCount > 0 -> DownloadGroupSection.Active
        errorCount > 0 -> DownloadGroupSection.Error
        else -> DownloadGroupSection.Complete
    }
}

class DownloadViewModel(
    private val downloadComicDao: DownloadComicDao
) : ViewModel() {
    private val _downloadFilterState = MutableStateFlow(DownloadFilter("downloading"))
    val downloadFilterState = _downloadFilterState.asStateFlow()

    private val _editState = MutableStateFlow(DownloadEditState())
    val editState = _editState.asStateFlow()

    private val allGroups = downloadComicDao.observeAllList()
        .map(::groupDownloadComics)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val completeGroups = allGroups
        .map { groups -> groups.filter { it.section == DownloadGroupSection.Complete } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeGroups = allGroups
        .map { groups -> groups.filter { it.section == DownloadGroupSection.Active } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val errorGroups = allGroups
        .map { groups -> groups.filter { it.section == DownloadGroupSection.Error } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun updateDownloadStatusFilter(status: String) {
        _downloadFilterState.update { it.copy(status = status) }
        clearSelection()
    }

    fun enterEdit(id: Int) {
        enterEdit(listOf(id))
    }

    fun enterEdit(ids: Collection<Int>) {
        val normalizedIds = ids.toSet()
        if (normalizedIds.isEmpty()) return
        _editState.update {
            it.copy(editing = true, selectedIds = it.selectedIds + normalizedIds)
        }
    }

    fun toggleSelected(id: Int) {
        toggleSelected(listOf(id))
    }

    fun toggleSelected(ids: Collection<Int>) {
        val normalizedIds = ids.toSet()
        if (normalizedIds.isEmpty()) return
        _editState.update {
            val allSelected = normalizedIds.all { id -> id in it.selectedIds }
            val selected = if (allSelected) {
                it.selectedIds - normalizedIds
            } else {
                it.selectedIds + normalizedIds
            }
            it.copy(editing = selected.isNotEmpty(), selectedIds = selected)
        }
    }

    fun clearSelection() {
        _editState.update { DownloadEditState() }
    }

    fun deleteSelected() {
        val ids = _editState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            downloadComicDao.deleteByIds(ids)
            clearSelection()
        }
    }

    fun deleteOne(id: Int) {
        deleteOne(listOf(id))
    }

    fun deleteOne(ids: Collection<Int>) {
        val normalizedIds = ids.toSet()
        if (normalizedIds.isEmpty()) return
        viewModelScope.launch {
            downloadComicDao.deleteByIds(normalizedIds.toList())
            _editState.update {
                val selected = it.selectedIds - normalizedIds
                it.copy(editing = selected.isNotEmpty(), selectedIds = selected)
            }
        }
    }

    fun pauseSelected() {
        updateSelectedStatus("paused")
    }

    fun startSelected() {
        updateSelectedStatus("pending")
    }

    private fun updateSelectedStatus(status: String) {
        val ids = _editState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            downloadComicDao.updateStatusByIds(ids, status)
            clearSelection()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val downloadPager = _downloadFilterState.flatMapLatest { filter ->
        Pager(
            config = PagingConfig(
                pageSize = 20,
                prefetchDistance = 6,
                initialLoadSize = 20
            ),
        ) {
            when (filter.status) {
                "complete" -> downloadComicDao.getCompleteList()
                "error" -> downloadComicDao.getErrorList()
                else -> downloadComicDao.getActiveList()
            }
        }.flow
    }.cachedIn(viewModelScope)
}

private fun groupDownloadComics(comics: List<DownloadComic>): List<DownloadComicGroup> {
    return comics
        .groupBy { it.normalizedGroupKey }
        .mapNotNull { (groupKey, chapters) ->
            val latest = chapters.maxByOrNull { it.createTime } ?: return@mapNotNull null
            DownloadComicGroup(
                key = groupKey,
                parentId = latest.normalizedParentId,
                parentName = latest.normalizedGroupName,
                chapters = chapters
            )
        }
        .sortedByDescending { it.latestCreateTime }
}

private val DownloadComic.normalizedParentId: Int
    get() = parentId.takeIf { it != 0 } ?: id

private val DownloadComic.normalizedParentName: String
    get() = parentName.ifBlank { name }

private val DownloadComic.normalizedGroupKey: String
    get() = if (hasParentMetadata) {
        "parent:$normalizedParentId"
    } else {
        "legacy:${name.chapterGroupName()}"
    }

private val DownloadComic.normalizedGroupName: String
    get() = if (hasParentMetadata) {
        normalizedParentName
    } else {
        name.chapterGroupName()
    }

private val DownloadComic.hasParentMetadata: Boolean
    get() = parentId != 0 && parentName.isNotBlank() &&
        (parentId != id || chapterCount > 1)

private fun String.chapterGroupName(): String {
    return replace(Regex("\\s*[-—–]?\\s*第\\s*\\d+\\s*[话話回集].*$"), "")
        .trim()
        .ifBlank { this }
}

private val DownloadComic.effectiveProgress: Float
    get() = if (status == "complete") 1f else progress.coerceIn(0f, 1f)
