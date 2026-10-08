import io.papermc.hangarpublishplugin.model.Platforms

plugins {
    java
    // XMine start - публикация универсального jar в свой Reposilite
    `maven-publish`
    // XMine end - публикация универсального jar в свой Reposilite
    id("xyz.wagyourtail.jvmdowngrader")
    id("io.papermc.hangar-publish-plugin") version "0.1.4"
}

dependencies {
    implementation(project(":skinsrestorer-bukkit", "downgraded")) {
        isTransitive = false
    }
    implementation(project(":skinsrestorer-bungee", "downgraded")) {
        isTransitive = false
    }
    implementation(project(":skinsrestorer-velocity", "downgraded")) {
        isTransitive = false
    }
    implementation(projects.multiver.miniplaceholders) {
        isTransitive = false
    }
}

tasks {
    jar {
        archiveClassifier = "only-merged"

        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        dependsOn(configurations.runtimeClasspath)
        from({ configurations.runtimeClasspath.get().map { zipTree(it) } })
    }
    shadeDowngradedApi {
        dependsOn(jar)

        inputFile = jar.get().archiveFile
        downgradeTo = JavaVersion.VERSION_1_8

        archiveFileName = "SkinsRestorer.jar"
        destinationDirectory = rootProject.layout.buildDirectory.dir("libs")

        shadePath = { _ -> "net/skinsrestorer/shadow/jvmdowngrader" }
    }
    build {
        dependsOn(shadeDowngradedApi)
    }
}

hangarPublish {
    publications.register("plugin") {
        version.set(project.version.toString())
        channel.set("Release")
        id.set("SkinsRestorer")
        apiKey.set(providers.environmentVariable("HANGAR_TOKEN"))
        changelog.set(providers.environmentVariable("HANGAR_CHANGELOG"))
        platforms {
            register(Platforms.PAPER) {
                jar.set(tasks.shadeDowngradedApi.flatMap { it.archiveFile })

                val versions: List<String> = (property("paperVersion") as String)
                    .split(",")
                    .map { it.trim() }
                platformVersions.set(versions)
            }
            register(Platforms.VELOCITY) {
                jar.set(tasks.shadeDowngradedApi.flatMap { it.archiveFile })

                val versions: List<String> = (property("velocityVersion") as String)
                    .split(",")
                    .map { it.trim() }
                platformVersions.set(versions)
            }
            register(Platforms.WATERFALL) {
                jar.set(tasks.shadeDowngradedApi.flatMap { it.archiveFile })

                val versions: List<String> = (property("waterfallVersion") as String)
                    .split(",")
                    .map { it.trim() }
                platformVersions.set(versions)
            }
        }
    }
}

// XMine start - публикация универсального jar в свой Reposilite
//
// Публикуется ровно тот файл, который у нас едет на прокси: build/libs/SkinsRestorer.jar,
// один на все платформы. Подпроектные публикации из sr.base-logic (api, shared, ...) нас
// не касаются - мы их не заливаем.
//
// Раздел - кандидат пары форков fork-snapshot (вики, ADR-0056), а не third-party:
// third-party - зеркало ЧУЖИХ артефактов, а сборку форка правим и собираем мы.
// Workflow передаёт полный адрес раздела через XMINE_MAVEN_URL.
publishing {
    publications {
        register<MavenPublication>("xmineUniversal") {
            groupId = "ru.xmine.thirdparty"
            artifactId = "skinsrestorer"
            version = project.version.toString()

            artifact(tasks.shadeDowngradedApi.flatMap { it.archiveFile }) {
                // archiveFileName у задачи переопределён на SkinsRestorer.jar, но классификатор
                // задачи от этого не исчезает - без явного сброса он уехал бы в координату.
                classifier = null
                extension = "jar"
            }

            pom {
                name = "SkinsRestorer (XMine fork)"
                description = "SkinsRestorer with environment variable expansion in config.yml"
                url = "https://github.com/XMineServer/SkinsRestorer"
                licenses {
                    license {
                        name = "GNU General Public License v3.0"
                        url = "https://www.gnu.org/licenses/gpl-3.0.html"
                    }
                }
                scm {
                    connection = "scm:git:https://github.com/XMineServer/SkinsRestorer.git"
                    url = "https://github.com/XMineServer/SkinsRestorer"
                }
            }
        }
    }

    repositories {
        // Имена свойств учётки - те же, что у остальных проектов XMine (Paper, XMinePlugins):
        // локально ~/.gradle/gradle.properties, в CI - переменные окружения.
        maven {
            name = "xmine"
            url = uri(
                providers.environmentVariable("XMINE_MAVEN_URL")
                    .getOrElse("https://maven.xmine.world/fork-snapshot")
            )
            credentials {
                username = providers.gradleProperty("xmineMavenUsername")
                    .orElse(providers.environmentVariable("XMINE_MAVEN_USERNAME"))
                    .orNull
                password = providers.gradleProperty("xmineMavenPassword")
                    .orElse(providers.environmentVariable("XMINE_MAVEN_PASSWORD"))
                    .orNull
            }
        }
    }
}

// Публикация берёт готовый файл, а не выход компонента, поэтому связь с задачей,
// которая этот файл делает, объявляется здесь явно.
tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(tasks.shadeDowngradedApi)
}
// XMine end - публикация универсального jar в свой Reposilite
