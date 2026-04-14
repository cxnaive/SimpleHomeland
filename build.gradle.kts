plugins {
    java
    `java-library`
    id("com.gradleup.shadow") version "8.3.5"
}

group = "dev.user"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.rosewooddev.io/repository/public/")
    maven("https://repo.thenextlvl.net/releases")
}

dependencies {
    compileOnly("dev.folia:folia-api:1.21.11-R0.1-SNAPSHOT")

    // PlaceholderAPI (硬依赖)
    compileOnly("me.clip:placeholderapi:2.11.6")

    // Worlds 插件 (硬依赖)
    compileOnly("net.thenextlvl:worlds:3.12.4")

    // XConomy 经济插件 (软依赖)
    compileOnly("com.github.YiC200333:XConomyAPI:2.25.1")

    // PlayerPoints 点券插件 (软依赖)
    compileOnly("org.black_ixx:playerpoints:3.3.3")

    // 数据库连接池和驱动 (由服务器通过 plugin.yml libraries 加载)
    compileOnly("com.zaxxer:HikariCP:6.2.1")
    compileOnly("com.h2database:h2:2.3.232")
    compileOnly("com.mysql:mysql-connector-j:9.2.0")

    // JSON 序列化 (由服务器通过 plugin.yml libraries 加载)
    compileOnly("com.google.code.gson:gson:2.12.1")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set("SimpleHomeland-${version}.jar")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
