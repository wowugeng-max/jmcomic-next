package com.par9uet.jm.store

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicChapter
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.worker.DownloadComicWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val DOWNLOAD_RETRY_BACKOFF_SECONDS = 30L

class DownloadManager(
    private val context: Context,
    private val downloadComicDao: DownloadComicDao,
    private val scope: CoroutineScope,
    private val toastManager: ToastManager,
) {
    fun downloadComic(comic: Comic) {
        scope.launch(Dispatchers.IO) {
            insertDownloadTask(
                comic = comic,
                parentId = comic.id,
                parentName = comic.name,
                chapterIndex = 0,
                chapterName = "",
                chapterCount = if (comic.comicChapterList.isEmpty()) 1 else comic.comicChapterList.size
            )
            enqueueDownloadRequest(comic.id)
            toastManager.showAsync("创建下载任务成功")
        }
    }

    fun downloadComicChapters(parentComic: Comic, chapters: List<ComicChapter>) {
        if (chapters.isEmpty()) {
            return
        }

        val chapterIndexById = parentComic.comicChapterList
            .mapIndexed { index, chapter -> chapter.id to index }
            .toMap()

        scope.launch(Dispatchers.IO) {
            chapters.forEach { chapter ->
                val chapterIndex = chapterIndexById[chapter.id] ?: chapters.indexOfFirst { it.id == chapter.id }
                val normalizedChapterIndex = chapterIndex.coerceAtLeast(0)
                val normalizedChapterName = chapter.name.ifBlank { "第${normalizedChapterIndex + 1}话" }
                insertDownloadTask(
                    comic = Comic.create(
                        id = chapter.id,
                        name = parentComic.name,
                        authorList = parentComic.authorList
                    ),
                    parentId = parentComic.id,
                    parentName = parentComic.name,
                    chapterIndex = normalizedChapterIndex,
                    chapterName = normalizedChapterName,
                    chapterCount = parentComic.comicChapterList.size.coerceAtLeast(chapters.size)
                )
            }
            enqueueDownloadRequests(chapters.map { it.id })
            toastManager.showAsync("已创建 ${chapters.size} 个下载任务")
        }
    }

    fun downloadComics(comics: List<Comic>) {
        if (comics.isEmpty()) {
            return
        }

        scope.launch(Dispatchers.IO) {
            comics.forEachIndexed { index, comic ->
                insertDownloadTask(
                    comic = comic,
                    parentId = comic.id,
                    parentName = comic.name,
                    chapterIndex = index,
                    chapterName = "",
                    chapterCount = comics.size
                )
            }
            enqueueDownloadRequests(comics.map { it.id })
            toastManager.showAsync("已创建 ${comics.size} 个下载任务")
        }
    }

    private suspend fun insertDownloadTask(
        comic: Comic,
        parentId: Int,
        parentName: String,
        chapterIndex: Int,
        chapterName: String,
        chapterCount: Int
    ) {
        downloadComicDao.insert(
            DownloadComic(
                id = comic.id,
                name = comic.name,
                authorList = comic.authorList,
                coverPath = "",
                zipPath = "",
                progress = 0f,
                status = "pending",
                createTime = System.currentTimeMillis(),
                parentId = parentId,
                parentName = parentName,
                chapterIndex = chapterIndex,
                chapterName = chapterName,
                chapterCount = chapterCount.coerceAtLeast(1)
            )
        )
    }

    private fun enqueueDownloadRequest(comicId: Int) {
        enqueueDownloadRequests(listOf(comicId))
    }

    private fun enqueueDownloadRequests(comicIds: List<Int>) {
        if (comicIds.isEmpty()) return
        val distinctComicIds = comicIds.distinct()
        val batchId = if (distinctComicIds.size > 1) UUID.randomUUID().toString() else ""
        val workManager = WorkManager.getInstance(context)
        distinctComicIds.forEach { comicId ->
            val downloadRequest = OneTimeWorkRequestBuilder<DownloadComicWorker>()
                .setConstraints(downloadConstraints())
                .setInputData(
                    workDataOf(
                        "comicId" to comicId,
                        "batchId" to batchId,
                        "batchTotal" to distinctComicIds.size
                    )
                ) // 传递参数
                .setBackoffCriteria(BackoffPolicy.LINEAR, DOWNLOAD_RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS) // 重试策略
                .build()
            workManager.enqueue(downloadRequest)
        }
    }

    private fun downloadConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED) // 必须有网
            .build()
    }
}
