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
import java.io.FileInputStream
import java.io.InputStream

data class TransferItem(
    val name: String,
    val size: Long,
    val realPath: String? = null, // 若有物理路径，优先极速读取
    val contentUri: Uri? = null   // 系统选择器兜底
) {
    fun openStream(context: Context): InputStream? {
        return if (realPath != null) {
            FileInputStream(File(realPath))
        } else if (contentUri != null) {
            context.contentResolver.openInputStream(contentUri)
        } else null
    }
}

data class AppItem(
    val name: String,
    val packageName: String,
    val apkFile: File,
    val size: Long
)

object FileHelper {

    fun getDownloadDir(): File {
        val target = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "P2PTransfer")
        if (!target.exists()) target.mkdirs()
        return target
    }

    fun notifyMediaScanner(context: Context, file: File) {
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            null,
            null
        )
    }

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

    fun getUriDetails(context: Context, uri: Uri): Pair<String, Long> {
        var name = "unknown_${System.currentTimeMillis()}"
        var size = 0L
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

        if (size <= 0L) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    size = pfd.statSize
                }
            } catch (_: Exception) {}
        }
        return Pair(name, size)
    }

    // 毫秒级极速扫描：借助已有所有文件权限，将 URI 解析为实际物理路径直接做原生 IO
    fun collectTreeFilesFast(context: Context, treeUri: Uri): List<TransferItem> {
        val result = mutableListOf<TransferItem>()
        val path = treeUri.path ?: ""
        
        // 尝试从 treeUri (形如 /tree/primary:DCIM/Camera) 还原出实际路径
        var physicalDir: File? = null
        if (path.contains("primary:")) {
            val subPath = path.substringAfter("primary:").substringBefore("/document")
            val rawFile = File(Environment.getExternalStorageDirectory(), subPath)
            if (rawFile.exists() && rawFile.isDirectory) {
                physicalDir = rawFile
            }
        }

        if (physicalDir != null) {
            // 原生物理扫描：4000 个文件只需要数十毫秒
            val rootPath = physicalDir.absolutePath
            val rootName = physicalDir.name
            physicalDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val rel = file.absolutePath.removePrefix(rootPath).removePrefix("/")
                    val fullRel = "$rootName/$rel"
                    result.add(TransferItem(name = fullRel, size = file.length(), realPath = file.absolutePath))
                }
            }
        } else {
            // 兜底慢速扫描
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
            traverseSlow(root, "", result)
        }
        return result
    }

    private fun traverseSlow(doc: DocumentFile, parentPath: String, outList: MutableList<TransferItem>) {
        if (doc.isDirectory) {
            val currentDir = if (parentPath.isEmpty()) doc.name.orEmpty() else "$parentPath/${doc.name}"
            doc.listFiles().forEach { child -> traverseSlow(child, currentDir, outList) }
        } else if (doc.isFile) {
            val fullRelPath = if (parentPath.isEmpty()) doc.name.orEmpty() else "$parentPath/${doc.name}"
            outList.add(TransferItem(name = fullRelPath, size = doc.length(), contentUri = doc.uri))
        }
    }

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
