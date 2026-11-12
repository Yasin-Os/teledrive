package com.awd.teledrive

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.awd.teledrive.data.worker.TeleDriveWorkerManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent

@HiltAndroidApp
class TeleDriveApplication : Application(), ImageLoaderFactory {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CoreRefreshEntryPoint {
        fun workerManager(): TeleDriveWorkerManager
    }

    override fun onCreate() {
        super.onCreate()
        // P1-14: 6-ঘণ্টার core refresh (BackupWorker) আগে কেউ
        // schedule করত না। KEEP নীতি বলে একবারই এনকিউ হবে; ব্যর্থ
        // হলে অ্যাপ চালুতে বাধা দেওয়া যাবে না।
        try {
            EntryPointAccessors.fromApplication(this, CoreRefreshEntryPoint::class.java)
                .workerManager()
                .scheduleCoreRefresh()
        } catch (e: Exception) {
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_thumb_cache"))
                    .maxSizeBytes(120L * 1024L * 1024L)
                    .build()
            }
            .allowHardware(true)
            .crossfade(120)
            .build()
    }
}
