plugins {
    java
    `java-library`
    `maven-publish`
    id("com.gradleup.shadow") version "8.3.5"
}

group = "dev.user"
version = "1.7.0"

repositories {
    maven("https://nexus.handyplus.cn/releases")
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

    // PlayerWarp 地标点插件 (软依赖)
    compileOnly("cn.handyplus.warp:PlayerWarp:2.3.7") { isTransitive = true }

    // Redis 客户端 (由服务器通过 plugin.yml libraries 加载)
    compileOnly("redis.clients:jedis:5.1.3")
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

// 创建源代码 JAR 任务（用于 JitPack）
tasks.register<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from(sourceSets["main"].allSource)
}

// 配置 Maven 发布（用于 JitPack）
publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "dev.user"
            artifactId = "simplehomeland"
            version = project.version.toString()

            from(components["java"])

            artifact(tasks["sourcesJar"])

            pom {
                name.set("SimpleHomeland")
                description.set("A homeland plugin for Minecraft Paper/Folia")
                url.set("https://github.com/cxnaive/SimpleHomeland")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("cxnaive")
                        name.set("cxnaive")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/cxnaive/SimpleHomeland.git")
                    developerConnection.set("scm:git:ssh://github.com:cxnaive/SimpleHomeland.git")
                    url.set("https://github.com/cxnaive/SimpleHomeland")
                }
            }
        }
    }
}
