import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jlleitschuh.gradle.ktlint")
}

group = "nl.ferron"
version = "1.0.0"

providers.gradleProperty("ccbBuildDir").orNull?.let { layout.buildDirectory.set(file(it)) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    intellijPlatform {
        pycharmCommunity("2025.2.6.1") {
            useInstaller = false
        }
        bundledPlugin("PythonCore")
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

intellijPlatform {
    pluginConfiguration {
        id = "nl.ferron.copilot-context-bridge"
        name = "Copilot Context Bridge"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "251"
            untilBuild = "262.*"
        }
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.PyCharmCommunity, "2025.1.3.1")
            create(IntelliJPlatformType.PyCharmCommunity, "2025.2.6.1")
            create(IntelliJPlatformType.PyCharmProfessional, "2025.2.6.1")
            create(IntelliJPlatformType.PyCharm, "2026.2.0.1")
        }
    }
}

tasks.test {
    maxHeapSize = "2g"
}
