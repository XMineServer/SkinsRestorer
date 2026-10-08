plugins {
    base
    idea
    eclipse
}

tasks.named<UpdateDaemonJvm>("updateDaemonJvm") {
    languageVersion = JavaLanguageVersion.of(25)
}

allprojects {
    group = "net.skinsrestorer"
    // XMine start - своя версия
    //
    // Суффикс не косметика: наш jar лежит в своём Reposilite и по внешнему виду
    // неотличим от апстримного. Версия - единственное, что говорит, какая сборка
    // стоит на прокси, и она же видна в /sr info и в логе запуска.
    //
    // В CI это адрес сборки <maven_version>-<ветка>-<дата>-<хеш> (вики, ADR-0056):
    // его считает .github/workflows/xmine-publish.yml и передаёт -PxmineVersion.
    // Локальная сборка без -P получает -xmine-local и не спутается с опубликованной.
    // maven_version остаётся ровно апстримным, чтобы перенос правок на новый тег
    // не упирался в конфликт по этой строке.
    //
    // Апстримному апдейт-чекеру суффикс не мешает: SemanticVersion.fromString
    // обрезает строку на первом не-цифре и не-точке, то есть видит ровно 15.12.5.
    version = providers.gradleProperty("xmineVersion")
        .getOrElse("${property("maven_version")}-xmine-local")
    // XMine end - своя версия
    description = "Ability to restore/change skins on servers!"

    repositories {
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
            name = "SpigotMC Repository"
            content {
                includeGroup("org.spigotmc")
                includeGroup("net.md-5")
            }
            mavenContent { snapshotsOnly() }
        }
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "PaperMC Repository"
            content {
                includeGroup("io.papermc.paper")
                includeGroup("com.velocitypowered")
                includeModule("net.md-5", "bungeecord-chat")
                // TODO: Remove, for some reason not in sonatype
                includeGroup("org.incendo")
            }
        }
        maven("https://repo.codemc.org/repository/nms/") {
            name = "CodeMC NMS Repository"
            content {
                includeGroup("org.spigotmc")
                includeGroup("org.bukkit")
            }
        }
        maven("https://repo.viaversion.com/") {
            name = "ViaVersion Repository"
            content {
                includeGroup("com.viaversion")
            }
        }
        maven("https://repo.extendedclip.com/content/repositories/placeholderapi/") {
            name = "PlaceholderAPI Repository"
            content {
                includeGroup("me.clip")
            }
        }
        maven("https://repo.clojars.org/") {
            name = "Clojars Repository"
            content {
                includeGroup("com.github.puregero")
            }
        }
        maven("https://jitpack.io/") {
            name = "JitPack Repository"
            content {
                includeGroupByRegex("com\\.github\\..*")
                excludeGroup("com.github.cryptomorin")
                excludeGroup("com.github.puregero")
            }
        }
        maven("https://libraries.minecraft.net/") {
            name = "Minecraft Repository"
            content {
                includeGroup("net.minecraft")
                includeGroup("com.mojang")
            }
        }
        maven("https://repo.opencollab.dev/maven-snapshots/") {
            name = "OpenCollab Snapshot Repository"
            content {
                includeGroupByRegex("org\\.geysermc\\..*")
            }
            mavenContent { snapshotsOnly() }
        }
        maven("https://repo.opencollab.dev/maven-releases/") {
            name = "OpenCollab Release Repository"
            content {
                includeGroupByRegex("org\\.geysermc\\..*")
            }
            mavenContent { releasesOnly() }
        }
        maven("https://maven.wagyourtail.xyz/releases") {
            name = "WagYourTail Release Repository"
            content {
                includeGroup("xyz.wagyourtail")
            }
            mavenContent { releasesOnly() }
        }
        maven("https://maven.wagyourtail.xyz/snapshots") {
            name = "WagYourTail Snapshot Repository"
            content {
                includeGroup("xyz.wagyourtail")
            }
            mavenContent { snapshotsOnly() }
        }
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            name = "Sonatype Snapshot Repository"
            mavenContent { snapshotsOnly() }
        }
        mavenCentral()
    }
}
