plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    testImplementation("junit:junit:4.13.2")
}

val verification by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

tasks.register<JavaExec>("verifyReadingCorpus") {
    group = "verification"
    description = "Runs real Japanese text samples through the local Kuromoji reading engine."
    dependsOn(verification.classesTaskName)
    classpath = verification.runtimeClasspath
    mainClass = "dev.furihook.engine.ReadingEngineCorpus"
    args(layout.buildDirectory.file("reports/reading-corpus.tsv").get().asFile.absolutePath)
}
