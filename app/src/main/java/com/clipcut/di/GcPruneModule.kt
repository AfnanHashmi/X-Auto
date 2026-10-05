package com.clipcut.di

import com.clipcut.data.gcprune.AndroidAuditSink
import com.clipcut.data.gcprune.WhitelistStore
import com.clipcut.domain.gcprune.AuditSink
import com.clipcut.domain.gcprune.GcPruneApi
import com.clipcut.domain.gcprune.GcPruneService
import com.clipcut.domain.gcprune.RealGcPruneApi
import com.clipcut.domain.gcprune.WhitelistSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class GcPruneBindings {
    @Binds abstract fun bindApi(impl: RealGcPruneApi): GcPruneApi

    @Binds abstract fun bindWhitelist(impl: WhitelistStore): WhitelistSource

    @Binds abstract fun bindAudit(impl: AndroidAuditSink): AuditSink
}

@Module
@InstallIn(SingletonComponent::class)
object GcPruneProvides {
    @Provides
    @Singleton
    fun provideService(api: GcPruneApi, whitelist: WhitelistSource, audit: AuditSink): GcPruneService =
        GcPruneService(api, whitelist, audit)
}
