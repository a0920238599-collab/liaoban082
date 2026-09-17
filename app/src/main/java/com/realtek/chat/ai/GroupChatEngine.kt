package com.realtek.chat.ai

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.realtek.chat.storage.AppDb
import com.realtek.chat.storage.Contact
import com.realtek.chat.storage.GroupMessage
import com.realtek.chat.storage.ProfileStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GroupChatEngine(context: Context) {
    private val appContext =
        context.applicationContext

    private val db =
        AppDb.get(appContext)

    private val gateway =
        RunApiGateway(appContext)

    private val personaEvolution =
        PersonaEvolutionEngine(
            appContext
        )

    private val behaviorController =
        SocialBehaviorController(
            appContext
        )

    private val profile =
        ProfileStore(appContext)

    private val episodeMutex =
        Mutex()

    suspend fun sendUserText(
        groupId: Int,
        text: String,
        onChanged: () -> Unit = {},
        onTyping: (String?) -> Unit = {}
    ) {
        val clean = text.trim()
        if (clean.isBlank()) return

        db.addGroupMessage(
            groupId = groupId,
            senderKind = "user",
            senderContactId = null,
            senderName =
                profile.nickname
                    .ifBlank {
                        "我"
                    },
            type = "text",
            text = clean
        )

        onChanged()

        runCatching {
            personaEvolution
                .maybeLearnBeforeGroupReply(
                    groupId = groupId,
                    latestUserText = clean
                )
        }

        // 用户可以在群里继续发消息，不会取消已经在进行的群聊思路。
        // 新消息会成为下一次行为判断的最新事件。
        episodeMutex.withLock {
            runGroupEpisode(
                groupId = groupId,
                onChanged = onChanged,
                onTyping = onTyping
            )
        }
    }

    suspend fun processPersonaEvolution(
        groupId: Int
    ) = coroutineScope {
        db.getGroupMembers(groupId)
            .map {
                contact ->
                async {
                    runCatching {
                        personaEvolution
                            .learnFromGroupChat(
                                groupId,
                                contact
                            )
                    }
                }
            }
            .awaitAll()
    }

private suspend fun runGroupEpisode(
    groupId: Int,
    onChanged: () -> Unit,
    onTyping: (String?) -> Unit
) {
    var safetyIterations = 0
    val safetyLimit = 16

    while (
        safetyIterations <
            safetyLimit
    ) {
        val latest =
            db.getLastGroupMessage(
                groupId
            )
                ?: return

        val members =
            db.getGroupMembers(
                groupId
            )

        if (
            members.size < 2
        ) {
            return
        }

        val recent =
            db.getRecentGroupMessages(
                groupId,
                20
            )

        val aiTurnsSinceUser =
            recent.asReversed()
                .takeWhile {
                    it.senderKind ==
                        "contact"
                }
                .size

        // 群聊每一步只调用一次“系统行为判断模型”。
        // 不再让 A/B/C 各自调用自己的聊天 API 先做发言判断。
        val decision =
            behaviorController
                .decideGroupNextSpeaker(
                    groupId =
                        groupId,
                    members =
                        members,
                    aiTurnsSinceUser =
                        aiTurnsSinceUser
                )

        if (
            !decision.speak ||
            decision.speakerContactId ==
                null
        ) {
            onTyping(null)
            return
        }

        val speaker =
            members.firstOrNull {
                it.id ==
                    decision.speakerContactId
            }
                ?: return

        // 正常的“继续还是停止”完全由系统行为判断模型决定。
        // 本地只保留 safetyLimit 作为异常死循环保险。

        delay(
            delayFor(
                decision,
                aiTurnsSinceUser
            )
        )

        val beforeGenerate =
            db.getLastGroupMessage(
                groupId
            )
                ?: return

        if (
            beforeGenerate.id !=
                latest.id
        ) {
            // 期间用户或其他成员又发了消息：
            // 不取消群聊，只让统一判断模型基于新上下文重新选下一位。
            safetyIterations += 1
            continue
        }

        onTyping(
            speaker.name
        )

        val reply =
            generateForMember(
                groupId =
                    groupId,
                contact =
                    speaker,
                decision =
                    decision
            )

        onTyping(null)

        if (
            reply.isNullOrBlank()
        ) {
            return
        }

        db.addGroupMessage(
            groupId =
                groupId,
            senderKind =
                "contact",
            senderContactId =
                speaker.id,
            senderName =
                speaker.name,
            type = "text",
            text =
                reply.take(
                    1200
                )
        )

        onChanged()
        safetyIterations += 1
    }

    onTyping(null)
}

    private suspend fun generateForMember(
        groupId: Int,
        contact: Contact,
        decision: GroupSystemDecision
    ): String? {
        val members =
            db.getGroupMembers(
                groupId
            )

        val recent =
            db.getRecentGroupMessages(
                groupId,
                20
            )

        val roster =
            buildRoster(
                members
            )

        val transcript =
            buildTranscript(
                recent
            )

        val adaptive =
            personaEvolution
                .effectiveRules(
                    contact.id
                )

        return runCatching {
            gateway.chat(
                provider =
                    contact.provider,
                model =
                    contact.model,
                systemPrompt = """
                    你是群聊成员“${contact.name}”。

                    【群成员身份表】
                    $roster

                    【严格身份规则】
                    - [USER:user|...] 是用户本人。
                    - [CONTACT:${contact.id}|${contact.name}] 是你自己。
                    - 其他 [CONTACT:id|name] 是其他独立联系人。
                    - A说的话只能算A说的，B说的话只能算B说的。
                    - 不要把多个成员的话合并成“用户说的”。
                    - 不要把群聊消息写成私聊上下文。
                    - 只以“${contact.name}”自己的身份发言。

                    【初始人格】
                    ${contact.corePersona}

                    【初始说话方式】
                    ${contact.styleRules}

                    【相处过程中学到的动态规则，优先于初始表达习惯】
                    $adaptive

                    初始人物卡中“每次必须、固定几条、固定结尾表情、每次追问”等动作不能机械执行；
                    用户后来的明确反馈和动态相处规则优先。

                    ${SocialConstitution.runtimeRules}

                    【本次行为决策】
                    mode=${decision.mode}
                    target=${decision.targetLabel}
                    direction=${decision.direction}

                    这是群聊，不要求所有话都围着用户。
                    你可以自然回应另一个联系人，
                    也可以对用户说话。
                    只发“${contact.name}”这一条消息本身。
                    不要在正文前写名字或 speaker ID。
                """.trimIndent(),
                userPrompt = """
                    最近群聊：
                    $transcript

                    现在按上面的行为决策自然说这一条。
                """.trimIndent(),
                maxTokens = 260
            ).trim()
        }.getOrNull()
            ?.takeIf {
                it.isNotBlank()
            }
    }

    private fun buildRoster(
        members: List<Contact>
    ): String {
        val contacts =
            members.joinToString("\n") {
                "[CONTACT:${it.id}|${it.name}]"
            }

        return """
            [USER:user|${profile.nickname.ifBlank { "我" }}]
            $contacts
        """.trimIndent()
    }

    private fun buildTranscript(
        messages: List<GroupMessage>
    ): String =
        messages.joinToString("\n") {
            "${speakerLabel(it)}：${it.text}"
        }
            .ifBlank {
                "暂无"
            }

    private fun speakerLabel(
        message: GroupMessage
    ): String =
        if (
            message.senderKind ==
                "user"
        ) {
            "[USER:user|${message.senderName}]"
        } else {
            val stableId =
                message.senderKey
                    .removePrefix(
                        "contact:"
                    )

            "[CONTACT:$stableId|${message.senderName}]"
        }

    private fun delayFor(
        decision: GroupSystemDecision,
        aiTurnsSinceUser: Int
    ): Long {
        val base =
            when (
                decision.delayClass
            ) {
                "quick" -> 650L
                "thoughtful" -> 2_300L
                else -> 1_250L
            }

        return (
            base +
                aiTurnsSinceUser *
                    240L
            ).coerceIn(
            450L,
            4_800L
        )
    }

    private fun parseJson(
        raw: String
    ): JsonObject? {
        val clean =
            raw.trim()
                .removePrefix(
                    "```json"
                )
                .removePrefix(
                    "```JSON"
                )
                .removePrefix(
                    "```"
                )
                .removeSuffix(
                    "```"
                )
                .trim()

        runCatching {
            JsonParser.parseString(
                clean
            ).asJsonObject
        }.getOrNull()?.let {
            return it
        }

        val start =
            clean.indexOf('{')

        val end =
            clean.lastIndexOf('}')

        if (
            start >= 0 &&
            end > start
        ) {
            return runCatching {
                JsonParser.parseString(
                    clean.substring(
                        start,
                        end + 1
                    )
                ).asJsonObject
            }.getOrNull()
        }

        return null
    }
}
