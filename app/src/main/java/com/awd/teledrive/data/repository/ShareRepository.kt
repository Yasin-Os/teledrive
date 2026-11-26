package com.awd.teledrive.data.repository

import com.awd.teledrive.data.remote.TelegramClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShareRepository @Inject constructor(
    private val telegramClient: TelegramClient
) {
    // H5: TDLib invokes result handlers on a background thread with no Looper;
    // deliver UI callbacks (which may show Toasts or update Compose state) on Main.
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun postMain(block: () -> Unit) {
        mainScope.launch { block() }
    }

    fun getFolderInviteLink(chatId: Long, callback: (String?) -> Unit) {
        telegramClient.send(TdApi.GetChat(chatId)) { result ->
            if (result is TdApi.Chat) {
                // In some versions, the link is in Chat.inviteLink, in others we might need to get full info
                // Let's try to get full info if it's a supergroup
                val type = result.type
                if (type is TdApi.ChatTypeSupergroup) {
                    telegramClient.send(TdApi.GetSupergroupFullInfo(type.supergroupId)) { fullInfoResult ->
                        if (fullInfoResult is TdApi.SupergroupFullInfo) {
                            val inviteLink = fullInfoResult.inviteLink
                            if (inviteLink != null) {
                                postMain { callback(inviteLink.inviteLink) }
                            } else {
                                createInviteLink(chatId, callback)
                            }
                        } else {
                            createInviteLink(chatId, callback)
                        }
                    }
                } else {
                    createInviteLink(chatId, callback)
                }
            } else {
                postMain { callback(null) }
            }
        }
    }

    private fun createInviteLink(chatId: Long, callback: (String?) -> Unit) {
        telegramClient.send(TdApi.CreateChatInviteLink(chatId, "MyCloud Share", 0, 0, false)) { inviteResult ->
            if (inviteResult is TdApi.ChatInviteLink) {
                postMain { callback(inviteResult.inviteLink) }
            } else {
                postMain { callback(null) }
            }
        }
    }

    /**
     * M24: fetches actual chat members for supergroups and basic groups
     * (falling back to administrators only when member listing is restricted),
     * safely filters non-user senders, and synchronizes async GetUser aggregation.
     */
    fun getChatMembers(chatId: Long, callback: (List<TdApi.User>) -> Unit) {
        telegramClient.send(TdApi.GetChat(chatId)) { chatResult ->
            if (chatResult !is TdApi.Chat) {
                postMain { callback(emptyList()) }
                return@send
            }
            when (val type = chatResult.type) {
                is TdApi.ChatTypeSupergroup -> {
                    telegramClient.send(TdApi.GetSupergroupMembers(type.supergroupId, null, 0, 200)) { membersResult ->
                        if (membersResult is TdApi.ChatMembers) {
                            val userIds = membersResult.members
                                .mapNotNull { (it.memberId as? TdApi.MessageSenderUser)?.userId }
                                .distinct()
                            fetchUsersByIds(userIds, callback)
                        } else {
                            fetchChatAdministrators(chatId, callback)
                        }
                    }
                }
                is TdApi.ChatTypeBasicGroup -> {
                    telegramClient.send(TdApi.GetBasicGroupFullInfo(type.basicGroupId)) { infoResult ->
                        if (infoResult is TdApi.BasicGroupFullInfo) {
                            val userIds = infoResult.members
                                .mapNotNull { (it.memberId as? TdApi.MessageSenderUser)?.userId }
                                .distinct()
                            fetchUsersByIds(userIds, callback)
                        } else {
                            fetchChatAdministrators(chatId, callback)
                        }
                    }
                }
                is TdApi.ChatTypePrivate -> fetchUsersByIds(listOf(type.userId), callback)
                is TdApi.ChatTypeSecret -> fetchUsersByIds(listOf(type.userId), callback)
                else -> fetchChatAdministrators(chatId, callback)
            }
        }
    }

    private fun fetchChatAdministrators(chatId: Long, callback: (List<TdApi.User>) -> Unit) {
        telegramClient.send(TdApi.GetChatAdministrators(chatId)) { adminResult ->
            val userIds = when (adminResult) {
                is TdApi.ChatAdministrators -> adminResult.administrators.map { it.userId }.distinct()
                is TdApi.ChatMembers -> adminResult.members
                    .mapNotNull { (it.memberId as? TdApi.MessageSenderUser)?.userId }
                    .distinct()
                else -> emptyList()
            }
            fetchUsersByIds(userIds, callback)
        }
    }

    private fun fetchUsersByIds(userIds: List<Long>, callback: (List<TdApi.User>) -> Unit) {
        if (userIds.isEmpty()) {
            postMain { callback(emptyList()) }
            return
        }
        val lock = Any()
        val users = mutableListOf<TdApi.User>()
        var remaining = userIds.size

        userIds.forEach { userId ->
            telegramClient.send(TdApi.GetUser(userId)) { userResult ->
                val finished: List<TdApi.User>? = synchronized(lock) {
                    if (userResult is TdApi.User) {
                        users.add(userResult)
                    }
                    remaining--
                    if (remaining == 0) users.toList() else null
                }
                if (finished != null) {
                    postMain { callback(finished) }
                }
            }
        }
    }

    /**
     * M24 + H5: creates/opens the recipient's private chat before calling
     * ForwardMessages (TDLib rejects an uncached userId as "Chat not found"),
     * and posts the callback on Main so UI toasts never crash.
     */
    fun shareFileToPhone(phoneNumber: String, messageId: Long, fromChatId: Long, callback: (Boolean, String?) -> Unit) {
        telegramClient.send(TdApi.SearchUserByPhoneNumber(phoneNumber, false)) { result ->
            when (result) {
                is TdApi.User -> {
                    telegramClient.send(TdApi.CreatePrivateChat(result.id, false)) { chatResult ->
                        when (chatResult) {
                            is TdApi.Chat -> {
                                val options = TdApi.MessageSendOptions().apply {
                                    disableNotification = false
                                    fromBackground = false
                                }
                                telegramClient.send(
                                    TdApi.ForwardMessages(
                                        chatResult.id,
                                        null, // topicId
                                        fromChatId,
                                        longArrayOf(messageId),
                                        options,
                                        false,
                                        false
                                    )
                                ) { forwardResult ->
                                    when (forwardResult) {
                                        is TdApi.Messages -> postMain { callback(true, null) }
                                        is TdApi.Error -> postMain { callback(false, forwardResult.message) }
                                        else -> postMain { callback(false, "Failed to forward message") }
                                    }
                                }
                            }
                            is TdApi.Error -> postMain { callback(false, chatResult.message) }
                            else -> postMain { callback(false, "Could not open chat with user") }
                        }
                    }
                }
                is TdApi.Error -> postMain { callback(false, result.message) }
                else -> postMain { callback(false, "User not found") }
            }
        }
    }
}
