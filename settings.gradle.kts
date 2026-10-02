rootProject.name = "cc-season"

plugins {
    // Toolchain 解析器：当本机找不到 JDK 21 时自动从 Foojay 下载
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
