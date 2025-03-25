plugins {
    alias(libs.plugins.shadow)
    java
}

group = "uk.co.notnull"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://repo.not-null.co.uk/releases/")
    }
    maven {
        url = uri("https://repo.not-null.co.uk/snapshots/")
    }
    maven {
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        url = uri("https://repo.mattmalec.com/repository/releases")
    }
}

dependencies {
    implementation(libs.pterodactyl4J)
    implementation(libs.messagesHelper)

    compileOnly(libs.velocityApi)
    implementation(libs.proxyQueuesApi)

    annotationProcessor(libs.velocityApi)
}

tasks {
    compileJava {
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
        options.encoding = "UTF-8"
    }

    processResources {
        expand("version" to project.version)
    }
}

tasks {
    shadowJar {
        archiveClassifier = ""
    }

    build {
        dependsOn(shadowJar)
    }

    processResources {
        expand("version" to project.version)
    }
}
