package com.clipcut.di

import com.clipcut.domain.ClipExporter
import com.clipcut.domain.Media3ClipExporter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ExporterModule {
    @Binds
    abstract fun bindClipExporter(impl: Media3ClipExporter): ClipExporter
}
