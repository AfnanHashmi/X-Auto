package com.clipcut.di

import android.util.Log
import com.clipcut.BuildConfig
import com.clipcut.domain.llm.LlmClient
import com.clipcut.domain.llm.LlmConfig
import com.clipcut.domain.llm.OkHttpLlmHttp
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LlmModule {
    /** xAI (Grok) first unless LLM_PROVIDER=openai; the other provider is the fallback. All from local.properties. */
    @Provides
    @Singleton
    fun provideLlmConfig(): LlmConfig = LlmConfig.of(
        provider = BuildConfig.LLM_PROVIDER,
        xaiKey = BuildConfig.XAI_API_KEY,
        openAiKey = BuildConfig.OPENAI_API_KEY,
        xaiAnalysis = BuildConfig.XAI_MODEL_ANALYSIS,
        xaiCaption = BuildConfig.XAI_MODEL_CAPTION,
        xaiHigh = BuildConfig.XAI_MODEL_HIGH,
        openAiAnalysis = BuildConfig.OPENAI_MODEL_ANALYSIS,
        openAiCaption = BuildConfig.OPENAI_MODEL_CAPTION,
        openAiHigh = BuildConfig.OPENAI_MODEL_HIGH,
    )

    /** Each call is logged under the tag "LLM" (see it with `adb logcat -s LLM`): provider, model, result, time, tokens. */
    @Provides
    @Singleton
    fun provideLlmClient(config: LlmConfig, client: OkHttpClient): LlmClient =
        LlmClient(config, OkHttpLlmHttp(client), log = { Log.i("LLM", it) })
}
