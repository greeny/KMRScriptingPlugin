import java.io.StringReader
import java.util.Properties

plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.4"
    id("org.jetbrains.grammarkit") version "2022.3.2.2"
}

group = "dev.greeny"
version = "0.2.1a"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("2023.2.6")
    type.set("IC") // Target IDE Platform
}

grammarKit {
    jflexRelease.set("1.9.1")
    grammarKitRelease.set("2022.3.2")
}

// The Marketplace token: from the environment, or from "publishToken=..." in local.properties, which is not
// committed. Both are read through the provider API so the configuration cache treats them as build inputs.
val publishToken: Provider<String> = providers.environmentVariable("PUBLISH_TOKEN").orElse(
    providers.fileContents(layout.projectDirectory.file("local.properties")).asText
        .map { Properties().apply { load(StringReader(it)) }.getProperty("publishToken").orEmpty() }
        .filter(String::isNotEmpty)
)

sourceSets {
    main {
        java {
            srcDirs("src/main/gen")
        }
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    generateLexer {
        sourceFile.set(file("src/main/java/dev/greeny/kmr/language/KmrPascal.flex"))
        targetOutputDir.set(file("src/main/gen/dev/greeny/kmr/language"))
        // JFlex keeps a backup of the previous lexer; we don't need it. The File is resolved outside the action so
        // the action captures no script object (required by the Gradle configuration cache).
        val backup = file("src/main/gen/dev/greeny/kmr/language/KmrPascalLexer.java~")
        doLast { backup.delete() }
    }

    generateParser {
        sourceFile.set(file("src/main/java/dev/greeny/kmr/language/KmrPascal.bnf"))
        targetRootOutputDir.set(file("src/main/gen"))
        pathToParser.set("dev/greeny/kmr/language/parser/KmrPascalParser.java")
        pathToPsiRoot.set("dev/greeny/kmr/language/psi")
        purgeOldFiles.set(true)
        mustRunAfter(generateLexer)
    }

    compileJava {
        dependsOn(generateLexer, generateParser)
    }

    buildSearchableOptions {
        enabled = false
    }

    patchPluginXml {
        sinceBuild.set("232")
        untilBuild.set("")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(publishToken)
    }
}

