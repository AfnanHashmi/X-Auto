package com.clipcut.di

import com.clipcut.data.stream.ConfigPlaylistRepository
import com.clipcut.data.stream.PlaylistRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class StreamModule {
    @Binds
    abstract fun bindPlaylistRepository(impl: ConfigPlaylistRepository): PlaylistRepository
}
