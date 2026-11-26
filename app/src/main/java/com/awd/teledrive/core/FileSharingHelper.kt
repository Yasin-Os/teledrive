package com.awd.teledrive.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.awd.teledrive.R
import java.io.File

object FileSharingHelper {
    private const val VAULT_PREVIEW_TEMP_DIR = "vault_preview_tmp"

    /**
     * H11: returns true when [filePath] points to a private decrypted vault
     * preview/temp file or an encrypted vault blob that must never be handed
     * to external apps via FileProvider.
     */
    fun isPrivateVaultTempPath(context: Context, filePath: String): Boolean {
        return try {
            val file = File(filePath)
            val name = file.name
            if (name.startsWith("prev_dec_") || name.startsWith("decrypt_") || name.endsWith(".enc", ignoreCase = true)) {
                return true
            }
            val vaultTempDir = File(context.cacheDir, VAULT_PREVIEW_TEMP_DIR).canonicalPath
            val canonicalFile = file.canonicalPath
            canonicalFile == vaultTempDir || canonicalFile.startsWith(vaultTempDir + File.separator)
        } catch (_: Exception) {
            true
        }
    }

    fun shareFile(context: Context, filePath: String, mimeType: String) {
        if (isPrivateVaultTempPath(context, filePath)) {
            Toast.makeText(context, context.getString(R.string.err_open_app), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val file = File(filePath)
            if (!file.exists()) return

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, context.getString(R.string.err_open_app), Toast.LENGTH_SHORT).show()
        }
    }

    fun openFileExternally(context: Context, filePath: String, mimeType: String) {
        if (isPrivateVaultTempPath(context, filePath)) {
            Toast.makeText(context, context.getString(R.string.err_open_app), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val file = File(filePath)
            if (!file.exists()) return

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(Intent.createChooser(intent, context.getString(R.string.open_with_other)))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, context.getString(R.string.err_open_app), Toast.LENGTH_SHORT).show()
        }
    }
}
