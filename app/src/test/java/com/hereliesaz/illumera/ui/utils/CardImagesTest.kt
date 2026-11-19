package com.hereliesaz.illumera.ui.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.Options
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import coil.size.Size
import coil.size.pxOrElse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CardImagesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val url = "https://image.tmdb.org/t/p/w500/poster.jpg"
    private val density = 4f / 3f

    private fun ImageRequest.sizePx(): Pair<Int, Int> = runBlocking {
        val size = sizeResolver.size()
        size.width.pxOrElse { -1 } to size.height.pxOrElse { -1 }
    }

    @Test fun aPrefetchedPosterHasTheCardsKeySizeAndScale() {
        val card = CardImages.poster(context, url, density, POSTER_CARD_WIDTH_DP)
        val prefetch = ImagePrefetcher.posterRequest(context, url, POSTER_CARD_WIDTH_DP, density)

        assertEquals(CardImages.memoryKey(url), card.memoryCacheKey)
        assertEquals(card.memoryCacheKey, prefetch.memoryCacheKey)
        assertEquals(card.diskCacheKey, prefetch.diskCacheKey)
        assertEquals(card.sizePx(), prefetch.sizePx())
        assertEquals(Scale.FILL, prefetch.scale)
        assertEquals(card.scale, prefetch.scale)
        assertEquals(Precision.INEXACT, prefetch.precision)
        assertEquals(card.allowHardware, prefetch.allowHardware)
        assertEquals(196 to 294, card.sizePx())
    }

    @Test fun aPrefetchedLandscapeCardHasTheCardsKeyAndSize() {
        val card = CardImages.landscape(context, url, density)
        val prefetch = ImagePrefetcher.landscapeRequest(context, url, density)
        assertEquals(card.memoryCacheKey, prefetch.memoryCacheKey)
        assertEquals(card.sizePx(), prefetch.sizePx())
        assertEquals(card.scale, prefetch.scale)
    }

    @Test fun thePreviewBackdropIsPrefetchedAtThePreviewsSize() {
        val preview = CardImages.inlineBackdrop(context, url, density)
        val prefetch = ImagePrefetcher.backdropRequest(context, url, fullScreen = false, density = density)
        assertEquals(preview.memoryCacheKey, prefetch.memoryCacheKey)
        assertEquals(preview.sizePx(), prefetch.sizePx())
        // Nothing near the 1920x1080 a full-screen backdrop is.
        assertTrue(prefetch.sizePx().first < 600)
    }

    @Test fun theFullScreenBackdropKeepsTheFullSize() {
        val prefetch = ImagePrefetcher.backdropRequest(context, url, fullScreen = true, density = density)
        assertEquals(backdropDecodeSize(context), runBlocking { prefetch.sizeResolver.size() })
    }

    @Test fun gridPostersKeepTheLegacySizeOnBothSides() {
        val card = CardImages.legacyPoster(context, url)
        val prefetch = ImagePrefetcher.posterRequest(context, url, widthDp = null)
        assertEquals(280 to 420, card.sizePx())
        assertEquals(card.sizePx(), prefetch.sizePx())
        assertEquals(card.memoryCacheKey, prefetch.memoryCacheKey)
    }

    @Test fun differentImagesNeverShareAKey() {
        assertNotEquals(
            CardImages.poster(context, url, density).memoryCacheKey,
            CardImages.poster(context, "$url?other", density).memoryCacheKey
        )
    }

    /** A fetcher that "decodes" any URL to a bitmap of the size the request asked for. */
    private class SizedBitmapFetcher(private val options: Options, private val fetches: IntArray) : Fetcher {
        override suspend fun fetch(): FetchResult {
            fetches[0]++
            val size = options.size
            val w = size.width.pxOrElse { 10 }
            val h = size.height.pxOrElse { 10 }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            return DrawableResult(
                BitmapDrawable(options.context.resources, bitmap),
                isSampled = true,
                dataSource = DataSource.NETWORK
            )
        }
    }

    /**
     * Runs [request] without hopping threads: the test blocks the (Robolectric) main thread, which
     * Coil's default dispatchers would need to hand the result back to.
     */
    private suspend fun ImageLoader.run(request: ImageRequest) =
        execute(request.newBuilder().dispatcher(Dispatchers.Unconfined).build())

    private fun loader(fetches: IntArray) = ImageLoader.Builder(context)
        .memoryCache { MemoryCache.Builder(context).maxSizeBytes(8 * 1024 * 1024).build() }
        .components {
            add(Fetcher.Factory<Uri> { _, options, _ -> SizedBitmapFetcher(options, fetches) })
        }
        .build()

    @Test fun theCardIsServedFromTheMemoryCacheOnceItsPrefetchRan() = runBlocking {
        val fetches = IntArray(1)
        val loader = loader(fetches)

        // The prefetch runs first and decodes the poster...
        loader.run(ImagePrefetcher.posterRequest(context, url, POSTER_CARD_WIDTH_DP, density))
        assertEquals(1, fetches[0])

        // ...so the card, which asks for the same request, never fetches or decodes again.
        val result = loader.run(CardImages.poster(context, url, density, POSTER_CARD_WIDTH_DP))
        assertTrue(result is SuccessResult)
        assertEquals(DataSource.MEMORY_CACHE, (result as SuccessResult).dataSource)
        assertEquals(1, fetches[0])
    }

    @Test fun theFocusedPreviewIsServedFromTheMemoryCacheOnceItsPrefetchRan() = runBlocking {
        val fetches = IntArray(1)
        val loader = loader(fetches)
        loader.run(ImagePrefetcher.backdropRequest(context, url, fullScreen = false, density = density))
        val result = loader.run(CardImages.inlineBackdrop(context, url, density))
        assertEquals(DataSource.MEMORY_CACHE, (result as SuccessResult).dataSource)
        assertEquals(1, fetches[0])
    }

    @Test fun aPrefetchAtAnotherSizeDoesNotServeTheCard() = runBlocking {
        // What the old code did: a card and a prefetch that disagree on size decode twice.
        val fetches = IntArray(1)
        val loader = loader(fetches)
        loader.run(CardImages.request(context, url, Size(100, 150)))
        val result = loader.run(CardImages.poster(context, url, density, POSTER_CARD_WIDTH_DP))
        assertNotEquals(DataSource.MEMORY_CACHE, (result as SuccessResult).dataSource)
        assertEquals(2, fetches[0])
    }
}
