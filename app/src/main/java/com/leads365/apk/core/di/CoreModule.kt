package com.leads365.apk.core.di

import com.leads365.apk.core.PhoneNumberNormalizer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    @Singleton
    fun providePhoneNumberNormalizer(): PhoneNumberNormalizer {
        return PhoneNumberNormalizer()
    }
}
