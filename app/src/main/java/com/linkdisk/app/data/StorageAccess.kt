package com.linkdisk.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

object StorageAccess {
    fun sharedRoot(): File = Environment.getExternalStorageDirectory()

    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }

    fun isWithinRoot(directory: File): Boolean {
        val rootPath = sharedRoot().absolutePath
        val path = directory.absolutePath
        return path == rootPath || path.startsWith("$rootPath/")
    }
}
