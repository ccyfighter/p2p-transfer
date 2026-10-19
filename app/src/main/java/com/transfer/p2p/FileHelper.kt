package com.transfer.p2p

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
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

    fun getDownloadDir(): File {
        val target = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "P2PTransfer")
        if (!target.exists()) target.mkdirs()
        return target
    }
}