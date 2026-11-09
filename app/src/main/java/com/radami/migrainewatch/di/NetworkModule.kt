package com.radami.migrainewatch.di

import com.radami.migrainewatch.data.remote.KtorOpenMeteoApi
import com.radami.migrainewatch.data.remote.KtorOpenMeteoArchiveApi
import com.radami.migrainewatch.data.remote.OpenMeteoApi
import com.radami.migrainewatch.data.remote.OpenMeteoArchiveApi
import com.radami.migrainewatch.data.remote.openMeteoHttpClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Runs on the app's own [OkHttpClient] rather than a fresh one, so its interceptors (mock
     * weather, request logging) and the test modules that replace it keep applying.
     */
    @Provides
    @Singleton
    fun provideHttpClient(okHttpClient: OkHttpClient): HttpClient =
        openMeteoHttpClient(OkHttp.create { preconfigured = okHttpClient })

    @Provides
    @Singleton
    fun provideOpenMeteoApi(client: HttpClient): OpenMeteoApi = KtorOpenMeteoApi(client)

    @Provides
    @Singleton
    fun provideOpenMeteoArchiveApi(client: HttpClient): OpenMeteoArchiveApi =
        KtorOpenMeteoArchiveApi(client)
}
