package com.par9uet.jm.worker

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import coil.ImageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import androidx.compose.ui.graphics.asAndroidBitmap
import com.par9uet.jm.cache.getDownloadDir
import com.par9uet.jm.data.models.ComicPicImageState
import com.par9uet.jm.data.models.ImageResultState
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.database.model.UpdateComicCover
import com.par9uet.jm.database.model.UpdateComicProgress
import com.par9uet.jm.database.model.UpdateComicStatus
import com.par9uet.jm.database.model.UpdateComicZipPath
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.retrofit.model.ComicPicListResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.store.RemoteSettingManager
import com.par9uet.jm.store.DownloadToastAggregator
import com.par9uet.jm.utils.compressWebpCompat
import com.par9uet.jm.utils.tryCreateDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val DOWNLOAD_PAGE_TIMEOUT_MS = 180_000L
private const val DOWNLOAD_MAX_ATTEMPTS = 6

class DownloadComicWorker(
    private val appContext: Context,
    params: WorkerParameters,
    private val downloadComicDao: DownloadComicDao,
    private val remoteSettingManager: RemoteSettingManager,
    private val localSettingManager: LocalSettingManager,
    private val comicRepository: ComicRepository,
    private val downloadToastAggregator: DownloadToastAggregator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val comicId = inputData.getInt("comicId", -1)
        val batchId = inputData.getString("batchId").orEmpty()
        val batchTotal = inputData.getInt("batchTotal", 1)
        if (comicId == -1) {
            return Result.failure()
        }

        return try {
            downloadComic(comicId)
            downloadToastAggregator.report(batchId, batchTotal, comicId, success = true)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < DOWNLOAD_MAX_ATTEMPTS - 1) {
                Result.retry() // 如果失败了，系统会自动尝试重试
            } else {
                markDownloadError(comicId)
                downloadToastAggregator.report(batchId, batchTotal, comicId, success = false)
                Result.failure()
            }
        }
    }

    private suspend fun downloadComic(comicId: Int) {
        downloadComicDao.updateStatus(
            UpdateComicStatus(
                comicId,
                "downloading"
            )
        )
        val coverPath = downloadCover(comicId)
        downloadComicDao.updateCover(
            UpdateComicCover(
                comicId,
                coverPath
            )
        )
        val picPathList =
            downloadPicList(comicId, localSettingManager.localSettingState.value.shunt)
        val zipPath = zipPicPathList(comicId, picPathList)
        downloadComicDao.updateZipPath(
            UpdateComicZipPath(
                comicId,
                zipPath
            )
        )
        downloadComicDao.updateStatus(
            UpdateComicStatus(
                comicId,
                "complete"
            )
        )
    }

    private suspend fun markDownloadError(comicId: Int) {
        downloadComicDao.updateStatus(
            UpdateComicStatus(
                comicId,
                "error"
            )
        )
    }

    private suspend fun downloadCover(comicId: Int): String {
        return withContext(Dispatchers.IO) {
            val coverUrl =
                "${remoteSettingManager.remoteSettingState.value.imgHost}/media/albums/${comicId}_3x4.jpg"
            val loader = ImageLoader(appContext)
            val request = ImageRequest.Builder(appContext)
                .data(coverUrl)
                .allowHardware(false)
                .build()

            when (val result = loader.execute(request)) {
                is ErrorResult -> {
                    // TODO 处理错误
                    ""
                }

                is SuccessResult -> {
                    val bitmap = result.drawable.toBitmap()
                    val dir = getComicCoverDownloadDir()
                    val file = File(dir, "${comicId}.jpg")
                    FileOutputStream(file).use { out ->
                        bitmap.compressWebpCompat(50, out)
                    }
                    file.absolutePath
                }
            }
        }
    }

    private suspend fun downloadPicList(comicId: Int, shunt: String): List<String> {
        return withContext(Dispatchers.IO) {
            when (val data = comicRepository.getComicPicList(comicId, shunt)) {
                is NetWorkResult.Error -> {
                    throw IllegalStateException(data.message)
                }

                is NetWorkResult.Success<ComicPicListResponse> -> {
                    if (data.data.list.isEmpty()) {
                        throw IllegalStateException("图片列表为空")
                    }
                    val dir = getComicPicListDownloadDir(comicId)
                    val loader = ImageLoader(appContext)
                    var maxProgress = downloadComicDao.getById(comicId)?.progress ?: 0f
                    data.data.list.mapIndexed { index, url ->
                        val file = File(dir, "$index.webp")
                        if (file.exists()) {
                            maxProgress = updateProgressIfAdvanced(
                                comicId = comicId,
                                currentMaxProgress = maxProgress,
                                nextProgress = (index + 1).toFloat() / data.data.list.size
                            )
                            return@mapIndexed file.absolutePath
                        }

                        val imageState = ComicPicImageState(
                            index = index,
                            comicId = comicId,
                            originSrc = url,
                            __scrambleId = data.data.__scrambleId,
                            __speed = data.data.__speed,
                            picImageLoader = loader
                        )
                        try {
                            withTimeout(DOWNLOAD_PAGE_TIMEOUT_MS) {
                                imageState.decode(appContext)
                            }
                        } catch (e: Exception) {
                            throw IllegalStateException("第${index + 1}页下载或解码超时", e)
                        }
                        when (val result = imageState.imageResultState) {
                            is ImageResultState.Success -> {
                                FileOutputStream(file).use { out ->
                                    result.decodeImageBitmap.asAndroidBitmap().compressWebpCompat(50, out)
                                }
                                maxProgress = updateProgressIfAdvanced(
                                    comicId = comicId,
                                    currentMaxProgress = maxProgress,
                                    nextProgress = (index + 1).toFloat() / data.data.list.size
                                )
                                file.absolutePath
                            }

                            is ImageResultState.Failure -> {
                                throw IllegalStateException("第${index + 1}页下载失败：${result.reason}")
                            }

                            ImageResultState.Loading -> {
                                throw IllegalStateException("第${index + 1}页仍在加载中")
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun updateProgressIfAdvanced(
        comicId: Int,
        currentMaxProgress: Float,
        nextProgress: Float
    ): Float {
        if (nextProgress <= currentMaxProgress) {
            return currentMaxProgress
        }
        downloadComicDao.updateProgress(
            UpdateComicProgress(
                comicId,
                nextProgress
            )
        )
        return nextProgress
    }

    private fun zipPicPathList(comicId: Int, picPathList: List<String>): String {
        val zipFile = File(getDownloadDir(appContext), "$comicId.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
            picPathList.forEach { source ->
                val file = File(source)
                if (file.exists()) {
                    val entryName = "$comicId/${file.name}"
                    val zipEntry = ZipEntry(entryName)
                    zipOut.putNextEntry(zipEntry)
                    FileInputStream(file).use { fis ->
                        fis.copyTo(zipOut)
                    }
                    zipOut.closeEntry()
                }
            }
        }
        return zipFile.absolutePath
    }

    private fun getComicPicListDownloadDir(comicId: Int): File {
        val dir = getDownloadDir(appContext)
        return tryCreateDir(File(dir, "$comicId"))
    }

    private fun getComicCoverDownloadDir(): File {
        val dir = getDownloadDir(appContext)
        return tryCreateDir(File(dir, "cover"))
    }
}
