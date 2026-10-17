package com.clipcut.di

import android.content.Context
import com.clipcut.BuildConfig
import com.clipcut.domain.publish.ConfiguredKeyVault
import com.clipcut.domain.publish.KeyVault
import com.clipcut.domain.publish.KeystoreKeyVault
import com.clipcut.domain.publish.OkHttpWebsiteHttp
import com.clipcut.domain.publish.PrefsPendingStore
import com.clipcut.domain.publish.WebsitePublisher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PublishModule {
    @Provides
    @Singleton
    fun provideKeyVault(@ApplicationContext context: Context): KeyVault = ConfiguredKeyVault(BuildConfig.WEBSITE_PUBLISH_KEY, KeystoreKeyVault(context))

    /** App-wide, so an update still finishes when the user leaves the screen. Separate from every screen's own work. */
    @Provides
    @Singleton
    fun providePublisher(@ApplicationContext context: Context, client: OkHttpClient, vault: KeyVault): WebsitePublisher =
        WebsitePublisher(OkHttpWebsiteHttp(client), vault, PrefsPendingStore(context), CoroutineScope(SupervisorJob() + Dispatchers.IO))
}
