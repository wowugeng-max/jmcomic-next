package com.par9uet.jm.database.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_comics")
data class DownloadComic(
    @PrimaryKey
    val id: Int,
    val name: String,
    val authorList: List<String>,
    val coverPath: String,
    val zipPath: String,
    val progress: Float,
    val status: String, // pending || downloading || complete
    val createTime: Long,
    val parentId: Int = id,
    val parentName: String = name,
    val chapterIndex: Int = 0,
    val chapterName: String = "",
    val chapterCount: Int = 1,
)
