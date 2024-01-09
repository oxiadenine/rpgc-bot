plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    val kotlinxCoroutinesVersion = properties["kotlinx-coroutines.version"] as String
    val kotlinxSerializationVersion = properties["kotlinx-serialization.version"] as String
    val ktorVersion = properties["ktor.version"] as String
    val typesafeConfigVersion = properties["typesafe-config.version"] as String
    val kotlinTelegramBotVersion = properties["kotlin-telegram-bot.version"] as String

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("com.typesafe:config:$typesafeConfigVersion")
    implementation("io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:$kotlinTelegramBotVersion")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("${project.group}.rpgcbot.MainKt")
}

tasks.test {
    useJUnitPlatform()
}