plugins {
    kotlin("jvm") version "2.1.20"
    id("community.flock.wirespec.extractor") version "@project.version@"
}

dependencies {
    implementation("org.springframework.graphql:spring-graphql:1.3.3")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-java-parameters")
    }
}

wirespecExtractor {
    basePackage.set("com.acme.api")
}
