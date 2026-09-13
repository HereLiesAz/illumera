package com.hereliesaz.illumera

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import org.acra.ReportField
import org.acra.config.httpSender
import org.acra.config.toast
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import org.acra.sender.HttpSender
import javax.inject.Inject

@HiltAndroidApp
class LumeraApplication : Application(), ImageLoaderFactory {

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var startupOptimizer: StartupOptimizer

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        if (BuildConfig.ACRA_URL.isBlank() || BuildConfig.ACRA_TOKEN.isBlank()) return

        initAcra {
            buildConfigClass = BuildConfig::class.java
            sendReportsInDevMode = false
            reportFormat = StringFormat.JSON
            reportContent = listOf(
                ReportField.REPORT_ID,
                ReportField.APP_VERSION_CODE,
                ReportField.APP_VERSION_NAME,
                ReportField.PACKAGE_NAME,
                ReportField.ANDROID_VERSION,
                ReportField.BRAND,
                ReportField.PHONE_MODEL,
                ReportField.PRODUCT,
                ReportField.STACK_TRACE,
                ReportField.STACK_TRACE_HASH,
                ReportField.THREAD_DETAILS,
                ReportField.USER_APP_START_DATE,
                ReportField.USER_CRASH_DATE,
                ReportField.TOTAL_MEM_SIZE,
                ReportField.AVAILABLE_MEM_SIZE
            )

            httpSender {
                uri = BuildConfig.ACRA_URL
                httpMethod = HttpSender.Method.POST
                basicAuthLogin = "acra"
                basicAuthPassword = BuildConfig.ACRA_TOKEN
            }

            toast {
                text = getString(R.string.acra_toast_text)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startupOptimizer.warmup()
    }

    override fun newImageLoader(): ImageLoader = imageLoader
}
