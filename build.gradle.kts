plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spotless)
}

group = "org.zirekhq"
description = "Commitment and peer-review layer in front of volunteer translation platforms"

// The version comes from the nearest git tag, so no
// hand-maintained version string drifts from what was actually released.
version =
    providers
        .exec {
            commandLine("git", "describe", "--tags", "--always", "--dirty")
            isIgnoreExitValue = true
        }.standardOutput.asText
        .map { it.trim().removePrefix("v") }
        .filter { it.isNotEmpty() }
        .orElse("0.0.0-SNAPSHOT")
        .get()

val gitCommit: String =
    providers.environmentVariable("GITHUB_SHA").map { it.take(7) }.orNull
        ?: providers
            .exec {
                commandLine("git", "rev-parse", "--short", "HEAD")
                isIgnoreExitValue = true
            }.standardOutput.asText
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .getOrElse("unknown")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.restclient)
    implementation(libs.spring.jdbc)
    implementation(libs.hikaricp)
    runtimeOnly(libs.postgresql)
}

springBoot {
    mainClass = "werger.WergerApplication"
    buildInfo {
        properties {
            additional = mapOf("gitCommit" to gitCommit)
        }
    }
}

// A fixed name lets the Dockerfile copy exactly the executable jar, not the
// plain jar the integrationTest and e2e suites compile against.
tasks.bootJar { archiveFileName = "dengjen-werger.jar" }

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
}

// Three tiers, one per trust level of dependency — stubbed, real dev infra,
// real third-party API. See .github/CONTRIBUTING.adoc.
testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter()
            dependencies {
                implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
                implementation(libs.spring.boot.starter.test)
                implementation(libs.spring.boot.starter.webmvc.test)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
        register<JvmTestSuite>("integrationTest") {
            sources { java.setSrcDirs(listOf("src/it/java")) }
            dependencies {
                implementation(project())
                implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
                implementation(libs.spring.boot.starter.test)
                implementation(libs.spring.jdbc)
                implementation(libs.hikaricp)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
        register<JvmTestSuite>("e2e") {
            sources { java.setSrcDirs(listOf("src/e2e/java")) }
            dependencies {
                implementation(project())
                implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
                implementation(libs.spring.boot.starter.test)
                implementation(libs.spring.boot.starter.restclient)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
    }
}

// The integration and e2e tiers reach real infrastructure through env vars
// that change without any input file changing, so a cached "skipped" result
// must never stand in for a real run.
listOf("integrationTest", "e2e").forEach { name ->
    tasks.named<Test>(name) { outputs.upToDateWhen { false } }
}

// A composition root and a thin driver wrapper aren't meaningfully
// unit-testable; including them would only pressure someone into padding
// coverage with low-value tests elsewhere.
val coverageExclusions = listOf("werger/WergerApplication*", "werger/adapters/db/Db.class")

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
    classDirectories.setFrom(
        files(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } }),
    )
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(
        files(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } }),
    )
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.test { finalizedBy(tasks.jacocoTestReport) }
tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }

spotless {
    java {
        target("src/*/java/**/*.java")
        palantirJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}
