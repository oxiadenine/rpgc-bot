package io.github.oxiadenine.rpgcbot

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.inlineQuery
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ReplyKeyboardRemove
import com.github.kotlintelegrambot.entities.inlinequeryresults.InlineQueryResult
import com.github.kotlintelegrambot.entities.inlinequeryresults.InputMessageContent
import com.github.kotlintelegrambot.extensions.filters.Filter
import com.typesafe.config.ConfigFactory
import io.github.oxiadenine.rpgcbot.network.TelegraphApi
import io.github.oxiadenine.rpgcbot.repository.*
import io.github.oxiadenine.rpgcbot.view.CharacterPageKeyboardReplyMarkup
import io.github.oxiadenine.rpgcbot.view.GameInlineKeyboardMarkup
import io.github.oxiadenine.rpgcbot.view.UserGameSubscriptionInlineKeyboardMarkup
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import java.util.concurrent.ConcurrentHashMap

enum class Command {
    START,
    NEWGAME,
    DELETEGAME,
    SETGAMESUB,
    NEWCHARPAGE,
    EDITCHARPAGE,
    NEWCHARRANKPAGE,
    EDITCHARRANKPAGE,
    CANCEL
}

fun Application.bot(
    telegraphApi: TelegraphApi,
    userRepository: UserRepository,
    gameRepository: GameRepository,
    userGameSubscriptionRepository: UserGameSubscriptionRepository,
    characterPageRepository: CharacterPageRepository
) {
    val config =  environment.config.config("bot")

    val bot = bot {
        token = config.property("token").getString()

        val currentCommandMap = ConcurrentHashMap<Long, Command>()
        val currentGameMap = ConcurrentHashMap<Long, Game>()
        val currentCharacterPageMap = ConcurrentHashMap<Long, CharacterPage>()

        dispatch {
            message(Filter.Command) {
                val intl = message.from?.languageCode?.let { locale -> Intl(locale) } ?: Intl()

                val userId = message.chat.id

                val commandName = message.text!!.substringAfter("/").uppercase()

                val user = userRepository.read(userId)

                runCatching {
                    if (commandName == Command.START.name) {
                        bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = "command.start.message")
                        )

                        return@message
                    } else if (commandName != Command.NEWGAME.name &&
                        commandName != Command.DELETEGAME.name &&
                        commandName != Command.SETGAMESUB.name &&
                        commandName != Command.NEWCHARPAGE.name &&
                        commandName != Command.EDITCHARPAGE.name &&
                        commandName != Command.NEWCHARRANKPAGE.name &&
                        commandName != Command.EDITCHARRANKPAGE.name &&
                        commandName != Command.CANCEL.name) {
                        if (currentCommandMap[userId] != null) return@message

                        if (commandName.length < 4) return@message

                        characterPageRepository.read().filter { characterPage ->
                            characterPage.title.normalize()
                                .replace(" ", "")
                                .contains(commandName, true)
                        }.map { characterPage ->
                            bot.sendMessage(chatId = ChatId.fromId(userId), text = characterPage.url)
                        }

                        return@message
                    }

                    user?.role?.let { userRole ->
                        if (commandName == Command.CANCEL.name) {
                            val currentCommand = currentCommandMap[userId] ?: return@message

                            currentCharacterPageMap.remove(userId)
                            currentGameMap.remove(userId)
                            currentCommandMap.remove(userId)

                            bot.sendMessage(
                                chatId = ChatId.fromId(message.chat.id),
                                text = intl.translate(
                                    id = "command.cancel.message",
                                    value = "command" to currentCommand.name.lowercase()
                                ),
                                replyMarkup = ReplyKeyboardRemove()
                            )

                            return@message
                        }

                        if ((commandName == Command.NEWGAME.name ||
                            commandName == Command.DELETEGAME.name ||
                            commandName == Command.NEWCHARPAGE.name ||
                            commandName == Command.NEWCHARRANKPAGE.name) && userRole == User.Role.EDITOR) {
                            throw User.UnauthorizedError()
                        }

                        if (currentCommandMap[userId] != null) {
                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = "\u2062",
                                replyMarkup = ReplyKeyboardRemove()
                            ).getOrNull()?.let { message ->
                                bot.deleteMessage(chatId = ChatId.fromId(userId), messageId = message.messageId)
                            }

                            currentCharacterPageMap.remove(userId)
                            currentGameMap.remove(userId)
                            currentCommandMap.remove(userId)
                        }

                        if (commandName == Command.NEWGAME.name) {
                            currentCommandMap[userId] = Command.valueOf(commandName)
                            currentGameMap[userId] = Game()

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(id = "command.newgame.name.message")
                            )

                            return@message
                        }

                        val games = gameRepository.read().filter { game ->
                            when (commandName) {
                                Command.DELETEGAME.name -> characterPageRepository.read(game).isEmpty()
                                Command.EDITCHARPAGE.name -> {
                                    characterPageRepository.read(game).any { characterPage ->
                                        !characterPage.isRanking
                                    }
                                }
                                Command.EDITCHARRANKPAGE.name -> {
                                    characterPageRepository.read(game).any { characterPage ->
                                        characterPage.isRanking
                                    }
                                }
                                else -> true
                            }
                        }

                        if (games.isEmpty()) {
                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(id = "command.game.list.empty.message")
                            )
                        } else {
                            currentCommandMap[userId] = Command.valueOf(commandName)

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(id = "command.game.list.message"),
                                replyMarkup = if (commandName == Command.SETGAMESUB.name) {
                                    val userGameSubscriptions = userGameSubscriptionRepository.read()

                                    UserGameSubscriptionInlineKeyboardMarkup.create(games, userGameSubscriptions)
                                } else GameInlineKeyboardMarkup.create(games)
                            )
                        }
                    } ?: throw User.UnauthorizedError()
                }.onFailure { error ->
                    when (error) {
                        is User.UnauthorizedError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = "command.user.unauthorized.message")
                        )
                        else -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = "command.error.message")
                        )
                    }
                }
            }

            message(Filter.Text) {
                val intl = message.from?.languageCode?.let { locale -> Intl(locale) } ?: Intl()

                val userId = message.chat.id

                val currentCommand = currentCommandMap[userId] ?: return@message

                var currentGame = currentGameMap[userId] ?: return@message

                runCatching {
                    if (currentGame.name.value.isEmpty() && currentCommand == Command.NEWGAME) {
                        val gameName = Game.Name(message.text!!)
                        val gameKey = gameName.normalize()
                            .lowercase()
                            .split(" ")
                            .joinToString("") { part -> "${part[0]}" }

                        val gameExists = gameRepository.read().any { game ->
                            game.key.equals(gameKey, true) ||
                                    game.name.value.equals(gameName.value, true)
                        }

                        if (gameExists) {
                            throw Game.ExistsError()
                        }

                        currentGame = Game(key = gameKey, name = gameName)

                        gameRepository.create(currentGame)

                        bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(
                                id = "command.newgame.success.message",
                                value = "name" to currentGame.name.value
                            )
                        )

                        currentGameMap.remove(userId)
                        currentCommandMap.remove(userId)

                        return@message
                    }
                }.onFailure { error ->
                    when (error) {
                        is Game.Name.BlankError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = "command.newgame.name.blank.message")
                        )
                        is Game.Name.LengthError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = "command.newgame.name.length.message")
                        )
                        is Game.Name.InvalidError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = "command.newgame.name.invalid.message")
                        )
                        is Game.ExistsError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = "command.newgame.name.exists.message")
                        )
                        else -> {
                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate("command.error.message"),
                                replyMarkup = ReplyKeyboardRemove()
                            )

                            currentCharacterPageMap.remove(userId)
                            currentGameMap.remove(userId)
                            currentCommandMap.remove(userId)
                        }
                    }
                }

                var currentCharacterPage = currentCharacterPageMap[userId] ?: return@message

                if (currentCharacterPage.content.value.isNotEmpty()) return@message

                runCatching {
                    if (currentCharacterPage.title.value.isEmpty()) {
                        val characterPageTitle = CharacterPage.Title(message.text!!)

                        when (currentCommand) {
                            Command.NEWCHARPAGE, Command.NEWCHARRANKPAGE -> {
                                val characterPagePath = buildString {
                                    append("${currentGame.key}-")
                                    append(characterPageTitle.normalize().lowercase().replace(" ", "-"))
                                    if (currentCharacterPage.isRanking) {
                                        append("-${CharacterPage.Paths.RANKING.name.lowercase()}")
                                    }
                                }

                                val characterPageExists = if (currentCharacterPage.isRanking) {
                                    currentGame.characterPages.filter { characterPage -> characterPage.isRanking }
                                } else {
                                    currentGame.characterPages.filter { characterPage -> !characterPage.isRanking }
                                }.any { characterPage ->
                                    characterPage.path.contains(characterPagePath) ||
                                            characterPage.title.value.equals(characterPageTitle.value, true)
                                }

                                if (characterPageExists) {
                                    throw CharacterPage.ExistsError()
                                }

                                currentCharacterPage = CharacterPage(
                                    path = characterPagePath,
                                    title = characterPageTitle,
                                    isRanking = currentCharacterPage.isRanking,
                                    gameKey = currentCharacterPage.gameKey
                                )

                                currentCharacterPageMap[userId] = currentCharacterPage

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(id = if (currentCharacterPage.isRanking) {
                                        "command.newcharrankpage.content.message"
                                    } else "command.newcharpage.content.message")
                                )
                            }
                            Command.EDITCHARPAGE, Command.EDITCHARRANKPAGE -> {
                                val characterPage = if (currentCharacterPage.isRanking) {
                                    currentGame.characterPages.filter { characterPage -> characterPage.isRanking }
                                } else {
                                    currentGame.characterPages.filter { characterPage -> !characterPage.isRanking }
                                }.firstOrNull { characterPage ->
                                    characterPage.title.value.equals(characterPageTitle.value, true)
                                } ?: return@message

                                currentCharacterPage = CharacterPage(
                                    path = characterPage.path,
                                    title = characterPage.title,
                                    url = characterPage.url,
                                    isRanking = characterPage.isRanking,
                                    gameKey = characterPage.gameKey
                                )

                                currentCharacterPageMap[userId] = currentCharacterPage

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(id = if (currentCharacterPage.isRanking) {
                                        "command.editcharrankpage.content.message1"
                                    } else "command.editcharpage.content.message1"),
                                    replyMarkup = ReplyKeyboardRemove()
                                )

                                val characterPageContentHtml = buildString {
                                    Json.decodeFromString<JsonArray>(characterPage.content.value).map { node ->
                                        if (node.jsonObject["tag"]!!.jsonPrimitive.content != "figure") {
                                            append("<${node.jsonObject["tag"]!!.jsonPrimitive.content}>")
                                            node.jsonObject["children"]?.jsonArray?.let { children ->
                                                if (children.first() is JsonObject) appendLine()
                                                children.map { node ->
                                                    when (node) {
                                                        is JsonObject -> {
                                                            append("<${node.jsonObject["tag"]!!.jsonPrimitive.content}>")
                                                            node.jsonObject["children"]?.jsonArray?.let { children ->
                                                                children.map { element ->
                                                                    append(element.jsonPrimitive.content)
                                                                }
                                                            }
                                                            append("</${node.jsonObject["tag"]!!.jsonPrimitive.content}>")
                                                            appendLine()
                                                        }

                                                        else -> append(node.jsonPrimitive.content)
                                                    }
                                                }
                                                append("</${node.jsonObject["tag"]!!.jsonPrimitive.content}>")
                                                appendLine()
                                            } ?: appendLine()
                                        } else append("")
                                    }
                                }

                                bot.sendMessage(chatId = ChatId.fromId(userId), text = characterPageContentHtml)

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(id = if (currentCharacterPage.isRanking) {
                                        "command.editcharrankpage.content.message2"
                                    } else "command.editcharpage.content.message2")
                                )
                            }
                            else -> return@message
                        }
                    } else {
                        val characterPageContentJson = buildString {
                            append("[")
                            append(Jsoup.parse(message.text!!).body().select(">*")
                                .joinToString(",") { element ->
                                    if (element.tagName() == "ol" || element.tagName() == "ul") {
                                        Json.encodeToString(TelegraphApi.Node(
                                            tag = element.tagName(),
                                            children = element.select(">*").map { childElement ->
                                                TelegraphApi.NodeElement(
                                                    tag = childElement.tagName(),
                                                    children = listOf(childElement.text())
                                                )
                                            }
                                        ))
                                    } else {
                                        Json.encodeToString(TelegraphApi.NodeElement(
                                            tag = element.tagName(),
                                            children = listOf(element.text())
                                        ))
                                    }
                                })
                            append("]")
                        }

                        val characterPageContent = CharacterPage.Content(characterPageContentJson)

                        when (currentCommand) {
                            Command.NEWCHARPAGE -> {
                                val page = telegraphApi.createPage(TelegraphApi.CreatePage(
                                    title = currentCharacterPage.path,
                                    content = characterPageContent.value
                                )).getOrThrow()

                                telegraphApi.editPage(page.path, TelegraphApi.EditPage(
                                    title = currentCharacterPage.title.value,
                                    content = characterPageContent.value
                                )).getOrThrow()

                                currentCharacterPage = CharacterPage(
                                    path = page.path,
                                    title = currentCharacterPage.title,
                                    content = characterPageContent,
                                    url = page.url,
                                    isRanking = currentCharacterPage.isRanking,
                                    gameKey = currentCharacterPage.gameKey
                                )

                                characterPageRepository.create(currentCharacterPage)

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.newcharpage.success.message",
                                        value = "title" to currentCharacterPage.title.value
                                    )
                                )

                                val currentUser = userRepository.read(userId)!!

                                userGameSubscriptionRepository.read(currentGame.key)
                                    .filter { userGameSubscription -> userGameSubscription.userId != currentUser.id }
                                    .forEach { userGameSubscription ->
                                        val userIntl = bot.getChatMember(
                                            chatId = ChatId.fromId(userGameSubscription.userId),
                                            userId = userGameSubscription.userId
                                        ).getOrNull()?.user?.languageCode?.let { locale -> Intl(locale) } ?: Intl()

                                        bot.sendMessage(
                                            chatId = ChatId.fromId(userGameSubscription.userId),
                                            text = userIntl.translate(
                                                id = "command.setgamesub.characterPage.created.message",
                                                values = listOf(
                                                    "name" to currentUser.name,
                                                    "title" to currentCharacterPage.title.value
                                                )
                                            )
                                        )
                                    }

                                currentCharacterPageMap.remove(userId)
                                currentGameMap.remove(userId)
                                currentCommandMap.remove(userId)
                            }
                            Command.NEWCHARRANKPAGE -> {
                                currentCharacterPageMap[userId] = CharacterPage(
                                    path = currentCharacterPage.path,
                                    title = currentCharacterPage.title,
                                    content = characterPageContent,
                                    isRanking = currentCharacterPage.isRanking,
                                    gameKey = currentCharacterPage.gameKey
                                )

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(id = "command.newcharrankpage.content.image.message")
                                )
                            }
                            Command.EDITCHARPAGE -> {
                                telegraphApi.editPage(currentCharacterPage.path, TelegraphApi.EditPage(
                                    title = currentCharacterPage.title.value,
                                    content = characterPageContent.value
                                )).getOrThrow()

                                currentCharacterPage = CharacterPage(
                                    path = currentCharacterPage.path,
                                    title = currentCharacterPage.title,
                                    content = characterPageContent,
                                    url = currentCharacterPage.url,
                                    isRanking = currentCharacterPage.isRanking,
                                    gameKey = currentCharacterPage.gameKey
                                )

                                characterPageRepository.update(currentCharacterPage)

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.editcharpage.success.message",
                                        value = "title" to currentCharacterPage.title.value
                                    )
                                )

                                val currentUser = userRepository.read(userId)!!

                                userGameSubscriptionRepository.read(currentGame.key)
                                    .filter { userGameSubscription -> userGameSubscription.userId != currentUser.id }
                                    .forEach { userGameSubscription ->
                                        val userIntl = bot.getChatMember(
                                            chatId = ChatId.fromId(userGameSubscription.userId),
                                            userId = userGameSubscription.userId
                                        ).getOrNull()?.user?.languageCode?.let { locale -> Intl(locale) } ?: Intl()

                                        bot.sendMessage(
                                            chatId = ChatId.fromId(userGameSubscription.userId),
                                            text = userIntl.translate(
                                                id = "command.setgamesub.characterPage.edited.message",
                                                values = listOf(
                                                    "name" to currentUser.name,
                                                    "title" to currentCharacterPage.title.value
                                                )
                                            )
                                        )
                                    }

                                currentCharacterPageMap.remove(userId)
                                currentGameMap.remove(userId)
                                currentCommandMap.remove(userId)
                            }
                            Command.EDITCHARRANKPAGE -> {
                                currentCharacterPageMap[userId] = CharacterPage(
                                    path = currentCharacterPage.path,
                                    title = currentCharacterPage.title,
                                    content = characterPageContent,
                                    url = currentCharacterPage.url,
                                    isRanking = currentCharacterPage.isRanking,
                                    gameKey = currentCharacterPage.gameKey
                                )

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(id = "command.editcharrankpage.content.image.message")
                                )
                            }
                            else -> return@message
                        }
                    }
                }.onFailure { error ->
                    when (error) {
                        is CharacterPage.Title.BlankError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = if (currentCharacterPage.isRanking) {
                                "command.newcharrankpage.title.blank.message"
                            } else "command.newcharpage.title.blank.message")
                        )
                        is CharacterPage.Title.LengthError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = if (currentCharacterPage.isRanking) {
                                "command.newcharrankpage.title.length.message"
                            } else "command.newcharpage.title.length.message")
                        )
                        is CharacterPage.Title.InvalidError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = if (currentCharacterPage.isRanking) {
                                "command.newcharrankpage.title.invalid.message"
                            } else "command.newcharpage.title.invalid.message")
                        )
                        is CharacterPage.Content.BlankError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = if (currentCharacterPage.isRanking) {
                                "command.newcharrankpage.content.blank.message"
                            } else "command.newcharpage.content.blank.message")
                        )
                        is CharacterPage.Content.LengthError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = if (currentCharacterPage.isRanking) {
                                "command.newcharrankpage.content.length.message"
                            } else "command.newcharpage.content.length.message")
                        )
                        is CharacterPage.ExistsError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = if (currentCharacterPage.isRanking) {
                                "command.newcharrankpage.title.exists.message"
                            } else "command.newcharpage.title.exists.message")
                        )
                        else -> {
                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate("command.error.message"),
                                replyMarkup = ReplyKeyboardRemove()
                            )

                            currentCharacterPageMap.remove(userId)
                            currentGameMap.remove(userId)
                            currentCommandMap.remove(userId)
                        }
                    }
                }
            }

            message(Filter.Photo) {
                val intl = message.from?.languageCode?.let { locale -> Intl(locale) } ?: Intl()

                val userId = message.chat.id

                val currentCommand = currentCommandMap[userId] ?: return@message
                val currentGame = currentGameMap[userId] ?: return@message

                var currentCharacterPage = currentCharacterPageMap[userId] ?: return@message

                if (currentCharacterPage.content.value.isEmpty()) return@message

                runCatching {
                    val characterPageImage = bot.getFile(message.photo!!.last().fileId).first?.let { response ->
                        if (response.isSuccessful) {
                            bot.downloadFile(response.body()?.result!!.filePath!!)
                        } else error(response.message())
                    }?.first?.let { response ->
                        if (response.isSuccessful) {
                            val imageBytes = response.body()!!.bytes()
                            val imageSrc = telegraphApi.uploadImage(imageBytes)

                            CharacterPage.Image(imageSrc, imageBytes)
                        } else error(response.message())
                    } ?: return@message

                    val characterPageContentImageJson = Json.encodeToString(buildJsonArray {
                        Json.decodeFromString<JsonArray>(currentCharacterPage.content.value)
                            .map { jsonElement -> add(jsonElement) }
                        add(Json.encodeToJsonElement(TelegraphApi.Node(
                            tag = "figure",
                            children = listOf(TelegraphApi.NodeElement(
                                tag = "img",
                                attrs = TelegraphApi.Attributes(src = characterPageImage.src)
                            ))
                        )))
                    })

                    val characterPageContent = CharacterPage.Content(characterPageContentImageJson)

                    when (currentCommand) {
                        Command.NEWCHARRANKPAGE -> {
                            val page = telegraphApi.createPage(TelegraphApi.CreatePage(
                                title = currentCharacterPage.path,
                                content = characterPageContent.value
                            )).getOrThrow()

                            telegraphApi.editPage(currentCharacterPage.path, TelegraphApi.EditPage(
                                title = currentCharacterPage.title.value,
                                content = characterPageContent.value
                            )).getOrThrow()

                            currentCharacterPage = CharacterPage(
                                path = page.path,
                                title = currentCharacterPage.title,
                                content = characterPageContent,
                                url = page.url,
                                isRanking = currentCharacterPage.isRanking,
                                image = characterPageImage.bytes,
                                gameKey = currentCharacterPage.gameKey
                            )

                            characterPageRepository.create(currentCharacterPage)

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(
                                    id = "command.newcharrankpage.success.message",
                                    value = "title" to currentCharacterPage.title.value
                                )
                            )

                            val currentUser = userRepository.read(userId)!!

                            userGameSubscriptionRepository.read(currentGame.key)
                                .filter { userGameSubscription -> userGameSubscription.userId != currentUser.id }
                                .forEach { userGameSubscription ->
                                    val userIntl = bot.getChatMember(
                                        chatId = ChatId.fromId(userGameSubscription.userId),
                                        userId = userGameSubscription.userId
                                    ).getOrNull()?.user?.languageCode?.let { locale -> Intl(locale) } ?: Intl()

                                    bot.sendMessage(
                                        chatId = ChatId.fromId(userGameSubscription.userId),
                                        text = userIntl.translate(
                                            id = "command.setgamesub.characterPage.ranking.created.message",
                                            values = listOf(
                                                "name" to currentUser.name,
                                                "title" to currentCharacterPage.title.value
                                            )
                                        )
                                    )
                                }
                        }
                        Command.EDITCHARRANKPAGE -> {
                            telegraphApi.editPage(currentCharacterPage.path, TelegraphApi.EditPage(
                                title = currentCharacterPage.title.value,
                                content = characterPageContent.value
                            )).getOrThrow()

                            currentCharacterPage = CharacterPage(
                                path = currentCharacterPage.path,
                                title = currentCharacterPage.title,
                                content = characterPageContent,
                                url = currentCharacterPage.url,
                                isRanking = currentCharacterPage.isRanking,
                                image = characterPageImage.bytes,
                                gameKey = currentCharacterPage.gameKey
                            )

                            characterPageRepository.update(currentCharacterPage)

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(
                                    id = "command.editcharrankpage.success.message",
                                    value = "title" to currentCharacterPage.title.value
                                )
                            )

                            val currentUser = userRepository.read(userId)!!

                            userGameSubscriptionRepository.read(currentGame.key)
                                .filter { userGameSubscription -> userGameSubscription.userId != currentUser.id }
                                .forEach { userGameSubscription ->
                                    val userIntl = bot.getChatMember(
                                        chatId = ChatId.fromId(userGameSubscription.userId),
                                        userId = userGameSubscription.userId
                                    ).getOrNull()?.user?.languageCode?.let { locale -> Intl(locale) } ?: Intl()

                                    bot.sendMessage(
                                        chatId = ChatId.fromId(userGameSubscription.userId),
                                        text = userIntl.translate(
                                            id = "command.setgamesub.characterPage.ranking.edited.message",
                                            values = listOf(
                                                "name" to currentUser.name,
                                                "title" to currentCharacterPage.title.value
                                            )
                                        )
                                    )
                                }
                        }
                        else -> return@message
                    }

                    currentCharacterPageMap.remove(userId)
                    currentGameMap.remove(userId)
                    currentCommandMap.remove(userId)
                }.onFailure {
                    bot.sendMessage(
                        chatId = ChatId.fromId(userId),
                        text = intl.translate(id = "command.error.message"),
                        replyMarkup = ReplyKeyboardRemove()
                    )

                    currentCharacterPageMap.remove(userId)
                    currentGameMap.remove(userId)
                    currentCommandMap.remove(userId)
                }
            }

            callbackQuery {
                val intl = callbackQuery.from.languageCode?.let { locale -> Intl(locale) } ?: Intl()

                val userId = callbackQuery.message?.chat?.id ?: return@callbackQuery

                val currentCommand = currentCommandMap[userId] ?: return@callbackQuery

                runCatching {
                    val game = gameRepository.read(callbackQuery.data)!!.let { game ->
                        game.characterPages = characterPageRepository.read(game)

                        game
                    }

                    when (currentCommand) {
                        Command.DELETEGAME -> {
                            userGameSubscriptionRepository.delete(game.key)
                            gameRepository.delete(game.key)

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(
                                    id = "command.deletegame.success.message",
                                    value = "name" to game.name.value
                                )
                            )

                            currentCommandMap.remove(userId)
                        }
                        Command.SETGAMESUB -> {
                            userGameSubscriptionRepository.read(userId, game.key)?.run {
                                userGameSubscriptionRepository.delete(userId, game.key)

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.setgamesub.unsubscribe.success.message",
                                        value = "name" to game.name.value
                                    )
                                )
                            } ?: run {
                                val userGameSubscription = UserGameSubscription(userId, game.key)

                                userGameSubscriptionRepository.create(userGameSubscription)

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.setgamesub.subscribe.success.message",
                                        value = "name" to game.name.value
                                    )
                                )
                            }

                            currentCommandMap.remove(userId)
                        }
                        Command.NEWCHARPAGE, Command.NEWCHARRANKPAGE -> {
                            val characterPageIsRanking = currentCommand == Command.NEWCHARRANKPAGE

                            game.characterPages = if (characterPageIsRanking) {
                                characterPageRepository.read(game).filter { characterPage -> characterPage.isRanking }
                            } else {
                                characterPageRepository.read(game).filter { characterPage -> !characterPage.isRanking }
                            }

                            currentGameMap[userId] = game
                            currentCharacterPageMap[userId] = CharacterPage(
                                isRanking = characterPageIsRanking,
                                gameKey = game.key
                            )

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(id = if (characterPageIsRanking) {
                                    "command.newcharrankpage.title.message"
                                } else "command.newcharpage.title.message")
                            )
                        }
                        Command.EDITCHARPAGE, Command.EDITCHARRANKPAGE -> {
                            val characterPageIsRanking = currentCommand == Command.EDITCHARRANKPAGE

                            game.characterPages = if (characterPageIsRanking) {
                                characterPageRepository.read(game).filter { characterPage -> characterPage.isRanking }
                            } else {
                                characterPageRepository.read(game).filter { characterPage -> !characterPage.isRanking }
                            }

                            currentGameMap[userId] = game
                            currentCharacterPageMap[userId] = CharacterPage(
                                isRanking = characterPageIsRanking,
                                gameKey = game.key
                            )

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(id = if (characterPageIsRanking) {
                                    "command.editcharrankpage.characterPage.list.message"
                                } else "command.editcharpage.characterPage.list.message"),
                                replyMarkup = CharacterPageKeyboardReplyMarkup.create(game.characterPages)
                            )
                        }
                        else -> return@callbackQuery
                    }
                }.onFailure {
                    bot.sendMessage(
                        chatId = ChatId.fromId(userId),
                        text = intl.translate(id = "command.error.message")
                    )

                    currentCharacterPageMap.remove(userId)
                    currentGameMap.remove(userId)
                    currentCommandMap.remove(userId)
                }
            }

            inlineQuery {
                val pageTitleQuery = inlineQuery.query

                if (pageTitleQuery.isBlank() or pageTitleQuery.isEmpty()) return@inlineQuery

                val characterPageTitle = try {
                    CharacterPage.Title(pageTitleQuery)
                } catch (_: Error) {
                    return@inlineQuery
                }

                val pageInlineQueryResults = characterPageRepository.read()
                    .filter { characterPage ->
                        characterPage.title.normalize().contains(characterPageTitle.normalize(), true)
                    }
                    .map { characterPage ->
                        InlineQueryResult.Article(
                            id = characterPage.path,
                            title = characterPage.title.value,
                            inputMessageContent = InputMessageContent.Text(characterPage.url),
                            description = gameRepository.read(characterPage.gameKey)!!.name.value
                        )
                    }

                bot.answerInlineQuery(inlineQuery.id, pageInlineQueryResults)
            }
        }
    }

    bot.startPolling()
}

fun Application.api(
    telegraphApi: TelegraphApi,
    userRepository: UserRepository,
    gameRepository: GameRepository,
    characterPageRepository: CharacterPageRepository
) {
    install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
        json()
    }
    install(Routing) {
        post("/users") {
            val body = call.receive<JsonObject>()

            val users = body["users"]!!.jsonArray.map { jsonElement ->
                val userId = jsonElement.jsonObject["id"]!!.jsonPrimitive.content.toLong()
                val userName = jsonElement.jsonObject["name"]!!.jsonPrimitive.content
                val userRole = jsonElement.jsonObject["role"]!!.jsonPrimitive.content.uppercase()

                val user = User(userId, userName, User.Role.valueOf(userRole))

                if (userRepository.read(user.id) == null) {
                    userRepository.create(user)
                } else userRepository.update(user)

                user
            }

            val response = buildJsonObject {
                put("ok", true)
                put("result", buildJsonArray {
                    users.map { user ->
                        add(buildJsonObject {
                            put("id", user.id)
                            put("name", user.name)
                            put("role", user.role.name.lowercase())
                        })
                    }
                })
            }

            call.respond(response)
        }
        post("/games") {
            val body = call.receive<JsonObject>()

            val games = body["games"]!!.jsonArray.map { jsonElement ->
                val gameName = Game.Name(jsonElement.jsonPrimitive.content)
                val gameKey = gameName.value
                    .lowercase()
                    .split(" ")
                    .joinToString("") { part -> "${part[0]}" }

                val game = Game(gameKey, gameName)

                if (gameRepository.read(game.key) == null) {
                    gameRepository.create(game)
                } else gameRepository.update(game)

                game
            }

            val response = buildJsonObject {
                put("ok", true)
                put("result", buildJsonArray {
                    games.map { game ->
                        add(buildJsonObject {
                            put("key", game.key)
                            put("name", game.name.value)
                        })
                    }
                })
            }

            call.respond(response)
        }
        post("/characterPages") {
            val pageCount = telegraphApi.getAccountInfo(TelegraphApi.GetAccountInfo(
                fields = listOf("page_count")
            )).getOrThrow().pageCount!!

            val pages = mutableListOf<TelegraphApi.Page>()

            var offset = 0
            val limit = 50

            while (offset < pageCount) {
                val pageList = telegraphApi.getPageList(TelegraphApi.GetPageList(
                    offset = offset,
                    limit = limit
                )).getOrThrow()

                pages.addAll(pageList.pages)

                offset += limit
            }

            val characterPages = pages.map { partialPage ->
                val page = telegraphApi.getPage(partialPage.path, TelegraphApi.GetPage()).getOrThrow()

                val characterPageImage = if (page.content!!.last().jsonObject["tag"]!!.jsonPrimitive.content == "figure") {
                    val imageSrc = page.content.last().jsonObject["children"]!!.jsonArray
                        .last().jsonObject["attrs"]!!.jsonObject["src"]!!.jsonPrimitive.content

                    telegraphApi.downloadImage(imageSrc)
                } else null

                val characterPage = CharacterPage(
                    page.path,
                    CharacterPage.Title(page.title),
                    CharacterPage.Content(Json.encodeToString(page.content)),
                    page.url,
                    page.path.contains(CharacterPage.Paths.RANKING.name, true),
                    characterPageImage,
                    page.path.substringBefore("-")
                )

                if (characterPageRepository.read(characterPage.path) == null) {
                    characterPageRepository.create(characterPage)
                } else characterPageRepository.update(characterPage)

                characterPage
            }

            val response = buildJsonObject {
                put("ok", true)
                put("result", buildJsonArray {
                    characterPages.map { characterPage ->
                        add(buildJsonObject {
                            put("path", characterPage.path)
                            put("title", characterPage.title.value)
                            put("url", characterPage.url)
                        })
                    }
                })
            }

            call.respond(response)
        }
    }
}

fun main() {
    val appConfig = HoconApplicationConfig(ConfigFactory.load())

    val httpClient = HttpClient(io.ktor.client.engine.cio.CIO) {
        install(ContentNegotiation) {
            json(Json {
                explicitNulls = false
                encodeDefaults = true
            })
        }
        install(DefaultRequest) {
            contentType(ContentType.Application.Json)
        }

        expectSuccess = true
    }
    val database = Database.create(appConfig.config("database"))

    val telegraphApi = TelegraphApi(appConfig.config("telegraph"), httpClient)

    val userRepository = UserRepository(database)
    val gameRepository = GameRepository(database)
    val userGameSubscriptionRepository = UserGameSubscriptionRepository(database)
    val characterPageRepository = CharacterPageRepository(database)

    val appEngineEnv = applicationEngineEnvironment {
        config = appConfig

        module {
            bot(telegraphApi, userRepository, gameRepository, userGameSubscriptionRepository, characterPageRepository)
            api(telegraphApi, userRepository, gameRepository, characterPageRepository)
        }

        connector {
            host = config.property("server.host").getString()
            port = config.property("server.port").getString().toInt()
        }
    }

    embeddedServer(io.ktor.server.cio.CIO, appEngineEnv).start(true)
}