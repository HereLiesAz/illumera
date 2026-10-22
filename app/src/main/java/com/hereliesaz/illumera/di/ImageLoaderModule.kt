package com.hereliesaz.illumera.di

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
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
            // with the whole box), and a larger cache starved low-memory TV boxes.
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.15)
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
