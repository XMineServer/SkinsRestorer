enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev/")
        maven("https://files.minecraftforge.net/maven/")
        gradlePluginPortal()
    }
}

plugins {
    id("com.gradle.develocity") version "4.5.0"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "skinsrestorer-parent"

develocity {
    buildScan {
        val isCi = providers.environmentVariable("CI").map { it.isNotEmpty() }.getOrElse(false)
        if (isCi) {
            termsOfUseUrl = "https://gradle.com/help/legal-terms-of-use"
            termsOfUseAgree = "yes"
            tag("CI")
        }
        publishing.onlyIf { isCi }
    }
}

setOf("shared", "paper").forEach {
    include("multiver:bukkit:$it")
}

include("test")

rootProject.projectDir.resolve("mappings").list().toList().forEach { include(":mappings:$it") }

setupSRSubproject("build-data")
setupSRSubproject("api")
setupSRSubproject("scissors")
setupSRSubproject("shared")
include("multiver:miniplaceholders")
include("multiver:viaversion")

setupSRSubproject("bukkit")
setupSRSubproject("bungee")
setupSRSubproject("velocity")

// XMine start - модовые подпроекты можно выключить
//
// Fabric и NeoForge в универсальный SkinsRestorer.jar не входят вовсе: universal
// собирается из bukkit, bungee, velocity и miniplaceholders. Зато их плагины на
// стадии КОНФИГУРАЦИИ скачивают, патчат и декомпилируют Minecraft - это десятки
// минут на каждом холодном прогоне, то есть на каждом запуске CI.
//
// По умолчанию поведение апстримное; -PxmineSkipMods=true отключает три
// подпроекта, на которые никто за пределами mod/ не ссылается.
val xmineSkipMods = providers.gradleProperty("xmineSkipMods")
    .map(String::toBoolean)
    .getOrElse(false)

if (!xmineSkipMods) {
    setupSubproject("skinsrestorer-mod-common") {
        projectDir = file("mod/common")
    }

    setupSubproject("skinsrestorer-mod-fabric") {
        projectDir = file("mod/fabric")
    }

    setupSubproject("skinsrestorer-mod-neoforge") {
        projectDir = file("mod/neoforge")
    }
}
// XMine end - модовые подпроекты можно выключить

setupSubproject("skinsrestorer") {
    projectDir = file("universal")
}

fun setupSRSubproject(name: String) {
    setupSubproject("skinsrestorer-$name") {
        projectDir = file(name)
    }
}

inline fun setupSubproject(name: String, block: ProjectDescriptor.() -> Unit) {
    include(name)
    project(":$name").apply(block)
}
