package com.spautifaille.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Client HTTP partagé de l'application (NewPipe, lecteur, Coil...). Non qualifié : à réutiliser tel quel.
 * Pas d'intercepteur de compression ici : le Downloader NewPipe ajoute le sien, et Media3 gère
 * lui-même `Accept-Encoding`.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(maxIdleConnections = 8, keepAliveDuration = 5, timeUnit = TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()
}
