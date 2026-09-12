package com.hereliesaz.illumera.di

import com.hereliesaz.illumera.data.remote.StremioApiService
import com.hereliesaz.illumera.data.remote.IntroDbService
import com.hereliesaz.illumera.data.remote.TmdbApiService
import com.hereliesaz.illumera.data.remote.TraktApiService
import com.hereliesaz.illumera.data.remote.TraktSyncApiService
import com.hereliesaz.illumera.data.trakt.TraktAuthInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TmdbRetrofit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TraktRetrofit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TraktAuthenticatedRetrofit

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://stremio-addons.netlify.app/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    @TmdbRetrofit
    fun provideTmdbRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.themoviedb.org/3/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideStremioApi(retrofit: Retrofit): StremioApiService {
        return retrofit.create(StremioApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideIntroDbService(retrofit: Retrofit): IntroDbService {
        return retrofit.create(IntroDbService::class.java)
    }

    @Provides
    @Singleton
    fun provideTmdbApiService(@TmdbRetrofit retrofit: Retrofit): TmdbApiService {
        return retrofit.create(TmdbApiService::class.java)
    }

    @Provides
    @Singleton
    @TraktRetrofit
    fun provideTraktRetrofit(okHttpClient: OkHttpClient): Retrofit {
        // Token/device-auth calls must use a dispatcher independent from authenticated
        // Trakt traffic. Otherwise several authenticated requests blocked in the auth
        // interceptor can exhaust the shared per-host dispatcher and deadlock the refresh.
        val authClient = okHttpClient.newBuilder()
            .dispatcher(Dispatcher())
            .build()
        return Retrofit.Builder()
            .baseUrl("https://api.trakt.tv/")
            .client(authClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideTraktApiService(@TraktRetrofit retrofit: Retrofit): TraktApiService {
        return retrofit.create(TraktApiService::class.java)
    }

    @Provides
    @Singleton
    @TraktAuthenticatedRetrofit
    fun provideTraktAuthenticatedRetrofit(
        okHttpClient: OkHttpClient,
        traktAuthInterceptor: TraktAuthInterceptor
    ): Retrofit {
        val authenticatedClient = okHttpClient.newBuilder()
            .addInterceptor(traktAuthInterceptor)
            .build()
        return Retrofit.Builder()
            .baseUrl("https://api.trakt.tv/")
            .client(authenticatedClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideTraktSyncApiService(@TraktAuthenticatedRetrofit retrofit: Retrofit): TraktSyncApiService {
        return retrofit.create(TraktSyncApiService::class.java)
    }
}
