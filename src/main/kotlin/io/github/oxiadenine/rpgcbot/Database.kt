package io.github.oxiadenine.rpgcbot

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection

object UserTable : Table("user") {
    val id = long("id").uniqueIndex()
    val name = varchar("name", 64).index()

    override val primaryKey = PrimaryKey(id)
}

object GameTable : Table("game") {
    val key = varchar("key", 16).uniqueIndex()
    val name = varchar("name", 64).index()

    override val primaryKey = PrimaryKey(key)
}

object UserGameSubscriptionTable : IntIdTable("user_game_subscription") {
    val userId = (long("user_id") references UserTable.id).index()
    val gameKey = (varchar("game_key", 64) references GameTable.key).index()
}

object CharacterPageTable : Table("character_page") {
    val path = varchar("path", 128).uniqueIndex()
    val title = varchar("title", 64).index()
    val content = text("content")
    val url = varchar("url", 128)
    val isRanking = bool("is_ranking")
    val image = blob("image").nullable()
    val gameKey = (varchar("game_key", 64) references GameTable.key).index()

    override val primaryKey = PrimaryKey(path)
}

class Database private constructor(private val connection: Database) {
    companion object {
        fun create(config: ApplicationConfig): io.github.oxiadenine.rpgcbot.Database {
            val hikariConfig = HikariConfig().apply {
                jdbcUrl = config.property("url").getString()
                driverClassName = config.property("driver").getString()
                username = config.property("username").getString()
                password = config.property("password").getString()
                isAutoCommit = true
                transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ.toString()

                validate()
            }

            val connection = Database.connect(datasource = HikariDataSource(hikariConfig))

            return Database(connection)
        }
    }

    init {
        transaction(connection) {
            SchemaUtils.create(
                UserTable,
                GameTable,
                UserGameSubscriptionTable,
                CharacterPageTable
            )
        }
    }

    suspend fun <T> transaction(statement: suspend Transaction.() -> T) = newSuspendedTransaction(
        context = Dispatchers.IO,
        db = connection,
        transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ,
        statement = statement
    )
}