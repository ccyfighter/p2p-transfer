package com.transfer.p2p

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream

data class TransferItem(
    val name: String,
    val size: Long,
    val openStream: (Context) -> InputStream?
)

data class AppItem(
    val name: String,
    val packageName: String,
    val apkFile: File,
    val size: Long
)

object FileHelper {

    // 获取公共存储目录：内部存储/Download/P2PTransfer
    fun getDownloadDir(): File {
        val target = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "P2PTransfer")
        if (!target.exists()) target.mkdirs()
        return target
    }

    // 关键：通知系统媒体扫描器，让相册、视频、文件管理器立即展示新接收的文件
    fun notifyMediaScanner(context: Context, file: File) {
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            null
        ) { path, uri ->
            // 媒体库索引更新完成
        }
    }

    // 获取非系统应用列表
    fun getInstalledApps(context: Context): List<AppItem> {
        val pm = context.packageManager
        val list = mutableListOf<AppItem>()
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (app in packages) {
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!isSystem && app.packageName != context.packageName) {
                val file = File(app.sourceDir)
                if (file.exists()) {
                    list.add(
                        AppItem(
                            name = pm.getApplicationLabel(app).toString(),
                            packageName = app.packageName,
                            apkFile = file,
                            size = file.length()
                        )
                    )
                }
            }
        }
        return list.sortedBy { it.name }
    }

    // 严谨获取 URI 文件大小（解决图片选择时返回 0 的问题）
    fun getUriDetails(context: Context, uri: Uri): Pair<String, Long> {
        var name = "unknown_${System.currentTimeMillis()}"
        var size = 0L

        // 尝试通过 ContentResolver 查询
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (_: Exception) {}

        // 如果未取到大小，回退到 FileDescriptor 查询
        if (size <= 0L) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    size = pfd.statSize
                }
            } catch (_: Exception) {}
        }
        return Pair(name, size)
    }

    // 递归遍历用户选中的文件夹，并平铺所有零散子文件
    fun collectTreeFiles(context: Context, treeUri: Uri): List<TransferItem> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val list = mutableListOf<TransferItem>()
        traverseDocument(context, root, "", list)
        return list
    }

    private fun traverseDocument(
        context: Context,
        doc: DocumentFile,
        parentPath: String,
        outList: MutableList<TransferItem>
    ) {
        if (doc.isDirectory) {
            val currentDir = if (parentPath.isEmpty()) doc.name.orEmpty() else "$parentPath/${doc.name}"
            doc.listFiles().forEach { child ->
                traverseDocument(context, child, currentDir, outList)
            }
        } else if (doc.isFile) {
            val fullRelPath = if (parentPath.isEmpty()) doc.name.orEmpty() else "$parentPath/${doc.name}"
            outList.add(
                TransferItem(
                    name = fullRelPath,
                    size = doc.length(),
                    openStream = { ctx -> ctx.contentResolver.openInputStream(doc.uri) }
                )
            )
        }
    }

    // 调起系统安装器
    fun installApk(context: Context, apkFile: File) {
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
        } else {
            Uri.fromFile(apkFile)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }
}
