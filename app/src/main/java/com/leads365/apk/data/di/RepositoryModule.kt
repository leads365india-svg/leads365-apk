package com.leads365.apk.data.di

import com.leads365.apk.data.repository.CallRepository
import com.leads365.apk.data.repository.CallRepositoryImpl
import com.leads365.apk.data.repository.LeadRepository
import com.leads365.apk.data.repository.LeadRepositoryImpl
import com.leads365.apk.data.repository.PolicyRepository
import com.leads365.apk.data.repository.PolicyRepositoryImpl
import com.leads365.apk.data.repository.UploadQueueRepository
import com.leads365.apk.data.repository.UploadQueueRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindLeadRepository(impl: LeadRepositoryImpl): LeadRepository

    @Binds
    @Singleton
    abstract fun bindCallRepository(impl: CallRepositoryImpl): CallRepository

    @Binds
    @Singleton
    abstract fun bindUploadQueueRepository(impl: UploadQueueRepositoryImpl): UploadQueueRepository

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: com.leads365.apk.data.repository.AuthRepositoryImpl): com.leads365.apk.data.repository.AuthRepository

    @Binds
    @Singleton
    abstract fun bindPolicyRepository(impl: PolicyRepositoryImpl): PolicyRepository

    @Binds
    @Singleton
    abstract fun bindFollowUpRepository(impl: com.leads365.apk.data.repository.FollowUpRepositoryImpl): com.leads365.apk.data.repository.FollowUpRepository
}

