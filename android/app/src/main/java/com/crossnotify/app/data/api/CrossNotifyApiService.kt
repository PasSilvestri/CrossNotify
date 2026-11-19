package com.crossnotify.app.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface CrossNotifyApiService {

    @POST("api/v1/relay/init")
    suspend fun initRelay(
        @Header("Authorization") authHeader: String,
        @Body body: RelayInitRequest
    ): Response<RelayInitResponse>

    @PUT("api/v1/channels/{channelId}/relay")
    suspend fun updateRelay(
        @Header("Authorization") authHeader: String,
        @Path("channelId") channelId: String,
        @Body body: RelayUpdateRequest
    ): Response<RelayUpdateResponse>

    @GET("api/v1/channels/{channelId}")
    suspend fun getChannelInfo(
        @Header("Authorization") authHeader: String,
        @Path("channelId") channelId: String
    ): Response<ChannelInfoResponse>

    @GET("api/v1/channels/{channelId}/subscribers")
    suspend fun getChannelSubscribers(
        @Header("Authorization") authHeader: String,
        @Path("channelId") channelId: String
    ): Response<ChannelSubscribersResponse>

    @DELETE("api/v1/channels/{channelId}")
    suspend fun deleteChannel(
        @Header("Authorization") authHeader: String,
        @Path("channelId") channelId: String
    ): Response<StatusOkResponse>

    @POST("api/v1/channels/{channelId}/notifications")
    suspend fun publishNotification(
        @Header("Authorization") authHeader: String,
        @Path("channelId") channelId: String,
        @Body body: PublishNotificationRequest
    ): Response<PublishNotificationResponse>

    @POST("api/v1/channels/{channelId}/subscribers/keys")
    suspend fun mintSubscriberKey(
        @Header("Authorization") authHeader: String,
        @Path("channelId") channelId: String,
        @Body body: MintSubscriberKeyRequest = MintSubscriberKeyRequest()
    ): Response<MintSubscriberKeyResponse>

    @PUT("api/v1/channels/{channelId}/subscribers/{subscriberId}")
    suspend fun updateSubscriberPermissions(
        @Header("Authorization") authHeader: String,
        @Path("channelId") channelId: String,
        @Path("subscriberId") subscriberId: String,
        @Body body: UpdateSubscriberPermissionsRequest
    ): Response<UpdateSubscriberPermissionsResponse>

    @POST("api/v1/channels/{channelId}/notifications/dismiss")
    suspend fun dismissNotifications(
        @Header("Authorization") authHeader: String,
        @Path("channelId") channelId: String,
        @Body body: DismissNotificationsRequest
    ): Response<DismissNotificationsResponse>

    @POST("api/v1/channels/{channelId}/notifications/{notificationId}/actions")
    suspend fun executeNotificationAction(
        @Header("Authorization") authHeader: String,
        @Path("channelId") channelId: String,
        @Path("notificationId") notificationId: String,
        @Body body: ExecuteActionRequest
    ): Response<ExecuteActionResponse>

    @POST("api/v1/channels/{channelId}/subscribers/init")
    suspend fun initSubscriber(
        @Header("Authorization") authHeader: String,
        @Path("channelId") channelId: String,
        @Body body: SubscriberInitRequest
    ): Response<SubscriberInitResponse>

    @GET("api/v1/channels/{channelId}/notifications")
    suspend fun catchUpNotifications(
        @Header("Authorization") authHeader: String,
        @Path("channelId") channelId: String,
        @Query("since") since: Long,
        @Query("limit") limit: Int = 50
    ): Response<CatchUpResponse>

    @GET("api/v1/channels/{channelId}/notifications/{notificationId}")
    suspend fun getNotification(
        @Header("Authorization") authHeader: String,
        @Path("channelId") channelId: String,
        @Path("notificationId") notificationId: String
    ): Response<RemoteNotificationDto>

    @PUT("api/v1/channels/{channelId}/subscribers/self")
    suspend fun updateSubscriberSelf(
        @Header("Authorization") authHeader: String,
        @Path("channelId") channelId: String,
        @Body body: SubscriberUpdateSelfRequest
    ): Response<SubscriberUpdateSelfResponse>

    @DELETE("api/v1/channels/{channelId}/subscribers/self")
    suspend fun unsubscribeSelf(
        @Header("Authorization") authHeader: String,
        @Path("channelId") channelId: String
    ): Response<StatusOkResponse>

    @GET("api/v1/channels/{channelId}/subscribers/self/silence")
    suspend fun getSubscriberSilences(
        @Header("Authorization") authHeader: String,
        @Path("channelId") channelId: String
    ): Response<SubscriberSilencesResponse>

    @PUT("api/v1/channels/{channelId}/subscribers/self/silence")
    suspend fun setSubscriberSilence(
        @Header("Authorization") authHeader: String,
        @Path("channelId") channelId: String,
        @Body body: SubscriberSilenceRequest
    ): Response<SubscriberSilenceResponse>

    @DELETE("api/v1/channels/{channelId}/subscribers/self/silence")
    suspend fun clearSubscriberSilence(
        @Header("Authorization") authHeader: String,
        @Path("channelId") channelId: String,
        @Query("appName") appName: String? = null,
        @Query("all") all: Boolean? = null
    ): Response<StatusOkResponse>
}
