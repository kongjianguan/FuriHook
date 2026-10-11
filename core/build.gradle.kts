plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val verification by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

dependencies {
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    testImplementation("junit:junit:4.13.2")
    "verificationImplementation"("com.google.code.gson:gson:2.14.0")
}

tasks.register<JavaExec>("verifyReadingCorpus") {
    group = "verification"
    description = "Runs real Japanese text samples through the local Kuromoji reading engine."
    dependsOn(verification.classesTaskName)
    classpath = verification.runtimeClasspath
    mainClass = "dev.furihook.engine.ReadingEngineCorpus"
    args(layout.buildDirectory.file("reports/reading-corpus.tsv").get().asFile.absolutePath)
}

tasks.register("writeReadingEngineBridgeClasspath") {
    group = "verification"
    description = "Writes the production reading engine verification classpath for browser E2E."
    dependsOn(verification.classesTaskName)
    val classpathFile = layout.buildDirectory.file("reports/webview-e2e/bridge-classpath.txt")
    outputs.file(classpathFile)
    doLast {
        val output = classpathFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(verification.runtimeClasspath.asPath + System.lineSeparator())
    }
}
