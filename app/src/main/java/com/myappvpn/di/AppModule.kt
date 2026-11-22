package com.myappvpn.di

import android.content.Context
import androidx.room.Room
import com.myappvpn.data.db.AppDatabase
import com.myappvpn.data.db.ServerDao
import com.myappvpn.data.remote.GithubApiService
import com.myappvpn.data.remote.PublicOnlyDns
import com.myappvpn.data.remote.SubscriptionFetcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            // Never log subscription URLs in release — custom subscriptions may carry tokens.
            level = if (com.myappvpn.BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        })
        .build()

    /**
     * Client for ALL subscription fetches (built-in mirrors + user-added URLs), used by
     * SubscriptionFetcher: SSRF-safe DNS (public addresses only), redirects followed manually so
     * every hop is re-validated, and a hard callTimeout so a stuck mirror can't hold a refresh.
     * Shares the base client's connection pool / dispatcher.
     */
    @Provides
    @Singleton
    @Named(SubscriptionFetcher.SUBSCRIPTION_CLIENT)
    fun provideSubscriptionOkHttpClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .dns(PublicOnlyDns)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("https://raw.githubusercontent.com/")
        .client(okHttpClient)
        .addConverterFactory(ScalarsConverterFactory.create())
        .build()

    @Provides
    @Singleton
    fun provideGithubApiService(retrofit: Retrofit): GithubApiService =
        retrofit.create(GithubApiService::class.java)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "myappvpn.db")
            .addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideServerDao(db: AppDatabase): ServerDao = db.serverDao()
}
