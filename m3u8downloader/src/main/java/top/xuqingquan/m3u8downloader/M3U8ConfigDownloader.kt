package top.xuqingquan.m3u8downloader

import android.net.Uri
import android.util.Log
import com.liulishuo.okdownload.*
import com.liulishuo.okdownload.core.cause.EndCause
import com.liulishuo.okdownload.core.cause.ResumeFailedCause
import com.liulishuo.okdownload.core.listener.DownloadListener1
import com.liulishuo.okdownload.core.listener.assist.Listener1Assist
import top.xuqingquan.m3u8downloader.entity.*
import java.io.File

/**
 * Created by 许清泉 on 2019-10-15 22:13
 */
internal object M3U8ConfigDownloader {

    private val downloadList = arrayListOf<String>()
    private val TAG = "M3U8ConfigDownloader"

    //清楚所有任务
    fun clear() {
        downloadList.clear()
    }

    /**
     * @return 如果返回空则不需要下载，如果返回的文件存在了，则开始下载，否则等待下载完成
     */
    fun start(entity: VideoDownloadEntity): File? {
        if (entity.status == DELETE) {
            return null
        }
        if (downloadList.contains(entity.originalUrl)) {
            return null
        }
        if (entity.createTime == 0L) {
            entity.createTime = System.currentTimeMillis()
        }
        entity.redirectUrl = ""
        val path = FileDownloader.getDownloadPath(entity.originalUrl)
        val config = FileDownloader.getConfigFile(entity.originalUrl)
        val realEntity = if (!config.exists()) {
            entity.toFile()
            entity
        } else {
            parseJsonToVideoDownloadEntity(config.readText()) ?: entity
        }
        if (entity.status == DELETE) {
            path.deleteRecursively()
            return null
        }
        val m3u8ListFile = File(path, "m3u8.list")
        return if (realEntity.status != COMPLETE) {//没有完成的才有必要下载
            Log.d(TAG, "init")
            if (m3u8ListFile.exists()) {
                Log.d(TAG, "从文件下载")
            } else {
                Log.d(TAG, "从0开始下载")
                realEntity.status = PREPARE
                FileDownloader.downloadCallback.postValue(realEntity)
                entity.toFile()
                //进入下载m3u8
                downloadM3U8File(path, realEntity)
            }
            m3u8ListFile
        } else {
            null
        }
    }


    /**
     * 下载单个文件
     */
    private fun downloadM3U8File(path: File, entity: VideoDownloadEntity) {
        if (entity.status == DELETE) {
            return
        }
        val fileName: String
        val url = if (entity.redirectUrl.isNotEmpty()) {//如果有了重定向的url
            fileName = "real.m3u8"
            entity.redirectUrl
        } else {//否则就用初始的url
            fileName = "original.m3u8"
            entity.originalUrl
        }
        Log.d(TAG, "downloadM3U8File-url=$url,fileName=$fileName")
        val downloadFile = File(path, fileName)
        DownloadTask.Builder(url, downloadFile.parentFile)
            .setFilename(downloadFile.name)
            .build()
            .enqueue(object : DownloadListener1() {
                override fun taskStart(task: DownloadTask, model: Listener1Assist.Listener1Model) {
                    if (entity.downloadTask == null) {
                        entity.downloadTask = task
                    }
                    Log.d(TAG, "taskStart-->")
                    downloadList.add(task.url)
                }

                override fun taskEnd(
                    task: DownloadTask, cause: EndCause, realCause: Exception?,
                    model: Listener1Assist.Listener1Model
                ) {
                    if (entity.downloadTask == null) {
                        entity.downloadTask = task
                    }
                    Log.d(TAG, "taskEnd-->${cause.name},${realCause?.message}")
                    if (cause == EndCause.COMPLETED) {
                        getFileContent(path, entity)
                    } else {
                        entity.status = ERROR
                        downloadList.remove(entity.originalUrl)
                        entity.startDownload = {
                            start(entity)
                        }
                        entity.toFile()
                        FileDownloader.downloadCallback.postValue(entity)
                    }
                }

                override fun progress(task: DownloadTask, currentOffset: Long, totalLength: Long) {
                    if (entity.downloadTask == null) {
                        entity.downloadTask = task
                    }
                }

                override fun connected(
                    task: DownloadTask, blockCount: Int, currentOffset: Long, totalLength: Long
                ) {
                    if (entity.downloadTask == null) {
                        entity.downloadTask = task
                    }
                    Log.d(TAG, "connected-->")
                }

                override fun retry(task: DownloadTask, cause: ResumeFailedCause) {
                    if (entity.downloadTask == null) {
                        entity.downloadTask = task
                    }
                }
            })
    }

    /**
     * 分析文件内容
     */
    private fun getFileContent(path: File, entity: VideoDownloadEntity) {
        if (entity.status == DELETE) {
            return
        }
        Log.d(TAG, "getFileContent---$entity")
        val url = if (entity.redirectUrl.isNotEmpty()) {//如果有了重定向的url
            entity.redirectUrl
        } else {//否则就用初始的url
            entity.originalUrl
        }
        val uri = Uri.parse(url)
        val realM3U8File = File(path, "real.m3u8")
        var file = realM3U8File
        if (!file.exists()) {//直接判断真实的m3u8文件是否存在，存在则读取
            file = File(path, "original.m3u8")
        }
        Log.d(TAG, "getFileContent---${file.name}")
        val list = file.readLines().filter { !it.startsWith("#") }//读取m3u8文件
        if (list.size > 1) {//直接的m3u8的ts链接
            entity.tsSize = list.size
            entity.toFile()
            if (file != realM3U8File) {
                file.copyTo(realM3U8File)
            }
            val m3u8ListFile = File(path, "m3u8.list")

            val localPlaylist = File(path, "localPlaylist.m3u8")
            file.readLines().forEach {
                var str = it
                if (!str.startsWith("#")) {
                    str = if (str.contains("/")) {
                        ".ts${it.substring(it.lastIndexOf("/"))}"
                    } else {
                        ".ts/$it"
                    }
                }else if(str.contains("KEY")){//此处获取KEY信息，并且下载到本地目录
                    //#EXT-X-KEY:METHOD=AES-128,URI="key.key",IV=XXXXXXX 根据此格式仅需要解析出URI信息进行替换
                    var arr = str.split(",")
                    var key = ""
                    for (na in arr){
                        if (na.startsWith("URI"))
                        {
                            key = na.substring(5,na.length -1)
                            break
                        }
                    }
                    if (key.isNotEmpty()) {
                        str = str.replace(
                            key, if (key.contains("/")) {
                                ".ts${key.substring(key.lastIndexOf("/"))}"
                            } else {
                                ".ts/$key"
                            }
                        )

                        val keyUrl = if (!key.startsWith("/")) {
                            url.substring(0, url.lastIndexOf("/") + 1) + key
                        } else {
                            "${uri.scheme}://${uri.host}$key"
                        }
                        m3u8ListFile.appendText("$keyUrl\n")//此处先下载key，方便边下边播功能
                    }

                }
                localPlaylist.appendText("$str\n")
            }
            //开始追加ts文件下载地址
            list.forEach {
                val ts = if (!it.startsWith("/")) {
                    url.substring(0, url.lastIndexOf("/") + 1) + it
                } else {
                    "${uri.scheme}://${uri.host}$it"
                }
                m3u8ListFile.appendText("$ts\n")
            }
            Log.d(TAG, "start--->$entity")
        } else {//重定向
            val newUrl = list[0]
            entity.redirectUrl = if (newUrl.startsWith("/")) {
                "${uri.scheme}://${uri.host}$newUrl"
            } else {
                url.substring(0, url.lastIndexOf("/") + 1) + newUrl
            }
            entity.toFile()
            downloadM3U8File(path, entity)
        }
    }

}