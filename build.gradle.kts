plugins {
    id("java")
    id("com.gradleup.shadow") version "9.4.1"
}

group = "me.bedepay"
version = "1.0"

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    implementation("org.incendo:cloud-core:2.0.0")
    implementation("org.incendo:cloud-annotations:2.0.0")
    implementation("org.incendo:cloud-paper:2.0.0-beta.10")
    implementation("org.incendo:cloud-minecraft-extras:2.0.0-beta.10")

    implementation("com.password4j:password4j:1.8.3")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.6")
    implementation("com.h2database:h2:2.3.232")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.test {
    enabled = false
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = Charsets.UTF_8.name()
}

tasks.shadowJar {
    archiveVersion = ""
    archiveClassifier = ""

    val base = "me.bedepay.authsecurity.libs"
    relocate("com.zaxxer.hikari", "$base.hikari")
    relocate("com.password4j", "$base.password4j")
    relocate("org.h2", "$base.h2")
    relocate("org.mariadb", "$base.mariadb")
    relocate("org.incendo.cloud", "$base.cloud")
    relocate("io.leangen.geantyref", "$base.geantyref")
}
