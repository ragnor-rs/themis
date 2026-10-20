plugins {
    id("java")
    id("application")
    kotlin("jvm")
}

group = "io.github.ragnorrs.themis"
version = "0.1.0"

application {
    mainClass = "ThemisBotKt"
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.openai:openai-java:2.12.0")
    implementation("io.pinecone:pinecone-client:5.0.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("org.jetbrains:markdown:0.7.3")
    implementation("io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:6.3.0")
    implementation("org.jsoup:jsoup:1.18.3")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation(kotlin("stdlib-jdk8"))
}

/**
 * Populates the Pinecone indexes the bot reads from. Run once before the first `run`.
 *
 * `./gradlew ingest` to populate, `./gradlew ingest --args="--dry-run"` to parse without
 * calling OpenAI or Pinecone.
 */
tasks.register<JavaExec>("ingest") {
    group = "application"
    description = "Downloads the DSA and the GDPR and loads their clauses into Pinecone."
    mainClass = "IngestKt"
    classpath = sourceSets.main.get().runtimeClasspath
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}