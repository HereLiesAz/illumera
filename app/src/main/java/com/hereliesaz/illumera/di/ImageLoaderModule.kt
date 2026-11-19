package com.hereliesaz.illumera.di

import android.app.ActivityManager
import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.hereliesaz.illumera.data.memory.imageCacheLimitBytes
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ImageLoaderModule {
    
    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient
    ): ImageLoader {
        return ImageLoader.Builder(context)
            // Use shared OkHttpClient with connection pooling
            .okHttpClient(okHttpClient)
            // Memory cache at 15% of app memory: bitmaps are hardware (graphics memory, shared
            // with the whole box), and a larger cache starved low-memory TV boxes. On a small
            // device it is held to a share of the RAM too; any device with RAM for the 15% keeps it.
            .memoryCache {
                val defaultBytes = MemoryCache.Builder(context).maxSizePercent(0.15).build().maxSize
                val totalRam = ActivityManager.MemoryInfo().also {
                    (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(it)
                }.totalMem
                MemoryCache.Builder(context)
                    .maxSizeBytes(imageCacheLimitBytes(defaultBytes.toLong(), totalRam).toInt())
                    .strongReferencesEnabled(true)
                    .build()
            }
            // Disk cache for offline/fast reload
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100MB
                    .build()
            }
            // Enable hardware bitmaps for GPU-accelerated rendering on TV
            .allowHardware(true)
            // Respect cache headers less aggressively for media content
            .respectCacheHeaders(false)
            // Crossfade disabled globally - we control it per-request based on scroll state
            .crossfade(false)
            // Dispatch immediately to reduce latency for first images
            .build()
    }
}
