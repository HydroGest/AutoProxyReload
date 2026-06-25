plugins {
    kotlin("jvm") version "2.3.21"
    application
}

group = "top.yurikale"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("com.formdev:flatlaf:3.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaExec> {
    jvmArgs("-Dfile.encoding=GBK")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "MainKt"
    }
    // 这行代码会把依赖解压并融合进最终的 jar 里面
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}