package com.awd.teledrive.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.awd.teledrive.data.repository.DriveRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        // P1-14: ব্যর্থতা চিরকাল retry হবে না — 3 চেষ্টার পর failure,
        // যাতে WorkManager backoff শেষে কাজটা ছেড়ে দেয়।
        private const val MAX_RETRY_ATTEMPTS = 3
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BackupWorkerEntryPoint {
        fun driveRepository(): DriveRepository
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                BackupWorkerEntryPoint::class.java
            )
            val driveRepository = entryPoint.driveRepository()

            // Refresh files to update cache
            driveRepository.fetchFiles()

            Result.success()
        } catch (e: Exception) {
            retryOrFail()
        }
    }

    private fun retryOrFail(): Result =
        if (runAttemptCount >= MAX_RETRY_ATTEMPTS) Result.failure() else Result.retry()
}
