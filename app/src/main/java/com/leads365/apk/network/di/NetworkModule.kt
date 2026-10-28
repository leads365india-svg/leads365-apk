package com.leads365.apk.network.di

import android.content.Context
import com.leads365.apk.BuildConfig
import com.leads365.apk.network.api.ApiService
import com.leads365.apk.network.api.AuthApiService
import com.leads365.apk.network.auth.AccessTokenInterceptor
import com.leads365.apk.network.auth.EncryptedTokenProvider
import com.leads365.apk.network.auth.SharedPreferencesTokenProvider
import com.leads365.apk.network.auth.TokenAuthenticator
import com.leads365.apk.network.auth.TokenProvider
import com.leads365.apk.network.models.TokenRefreshRequest
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideEncryptedTokenProvider(
        @ApplicationContext context: Context
    ): EncryptedTokenProvider = EncryptedTokenProvider(context)

    @Provides
    @Singleton
    fun provideTokenProvider(
        encryptedTokenProvider: EncryptedTokenProvider
    ): TokenProvider = encryptedTokenProvider

    @Provides
    @Singleton
    @Named("baseUrl")
    fun provideBaseUrl(): String = BuildConfig.BASE_URL

    @Provides
    @Singleton
    fun provideAccessTokenInterceptor(
        tokenProvider: TokenProvider
    ): AccessTokenInterceptor = AccessTokenInterceptor(tokenProvider)

    @Provides
    @Singleton
    fun provideOkHttpClient(
        tokenProvider: TokenProvider,
        accessTokenInterceptor: AccessTokenInterceptor,
        @Named("baseUrl") baseUrl: String,
        json: Json
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        // Lazy auth service to avoid circular dependency in Authenticator
        lateinit var authApiService: ApiService
        val refreshClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val tokenAuthenticator = TokenAuthenticator(
            tokenProvider = tokenProvider,
            tokenRefresher = { refreshToken ->
                try {
                    val rawRetrofit = Retrofit.Builder()
                        .baseUrl(baseUrl)
                        .client(refreshClient)
                        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                        .build()
                    val authService = rawRetrofit.create(ApiService::class.java)
                    runBlocking {
                        authService.refreshToken(TokenRefreshRequest(refreshToken))
                    }
                } catch (e: Exception) {
                    null
                }
            }
        )

        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(accessTokenInterceptor)
            .authenticator(tokenAuthenticator)
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        json: Json,
        @Named("baseUrl") baseUrl: String
    ): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(
        retrofit: Retrofit
    ): ApiService = retrofit.create(ApiService::class.java)

    @Provides
    @Singleton
    fun provideAuthApiService(
        retrofit: Retrofit
    ): AuthApiService = retrofit.create(AuthApiService::class.java)

    @Provides
    @Singleton
    fun provideLeadApiService(
        retrofit: Retrofit
    ): com.leads365.apk.network.api.LeadApiService = retrofit.create(com.leads365.apk.network.api.LeadApiService::class.java)

    @Provides
    @Singleton
    fun provideCallApiService(
        retrofit: Retrofit
    ): com.leads365.apk.network.api.CallApiService = retrofit.create(com.leads365.apk.network.api.CallApiService::class.java)

    @Provides
    @Singleton
    fun providePolicyApiService(
        retrofit: Retrofit
    ): com.leads365.apk.network.api.PolicyApiService = retrofit.create(com.leads365.apk.network.api.PolicyApiService::class.java)

    @Provides
    @Singleton
    fun provideFollowUpApiService(
        retrofit: Retrofit
    ): com.leads365.apk.network.api.FollowUpApiService = retrofit.create(com.leads365.apk.network.api.FollowUpApiService::class.java)

    @Provides
    @Singleton
    fun provideCloudTelephonyApiService(
        retrofit: Retrofit
    ): com.leads365.apk.telephony.CloudTelephonyApiService = retrofit.create(com.leads365.apk.telephony.CloudTelephonyApiService::class.java)
}
