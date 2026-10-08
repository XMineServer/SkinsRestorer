plugins {
    id("sr.base-logic")
}

dependencies {
    testFixturesApi(project(":skinsrestorer-shared", "shadow"))

    testImplementation(libs.bstats.base)

    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)

    testRuntimeOnly(libs.postgresql)

    testRuntimeOnly(libs.slf4j.simple)
}

// XMine start - окружение для EnvConfigTest
//
// EnvConfigTest проверяет подстановку !ENV ${...} и то, что конфиг не переписывается.
// Переменные окружения из JVM не подделать, поэтому их задаёт сама задача.
tasks.test {
    environment("SR_TEST_HOST", "db.internal.example")
    environment("SR_TEST_DATABASE", "skins")
    environment("SR_TEST_USERNAME", "sruser")
    environment("SR_TEST_PASSWORD", "s3cr3t-must-not-reach-disk")
    environment("SR_TEST_PORT", "3307")
}
// XMine end - окружение для EnvConfigTest
