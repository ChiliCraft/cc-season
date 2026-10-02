# cc-season

季节附属：季节日历、天气、生态与指南；入口 `/cc season`（兼容 `/season`、`/ccseason`）。

ChiliCraft 插件组独立仓库（原 monorepo 拆分）。运行于 **Paper 1.21.1**，JDK 21 构建。

## 构建

```bash
./gradlew build
```

产物在 `build/libs/*.jar`。依赖 Gradle Wrapper，无需本地安装 Gradle（缺 JDK 21 时由 Foojay 自动下载）。

## 依赖

- **cc-core**（`compileOnly("com.chilicraft:cc-core:1.0.0")`）：运行时由服务端已安装的 cc-core 提供，不打进 jar。
  - 本地开发：先在 cc-core 仓执行 `./gradlew publishToMavenLocal`，本仓 Gradle 会优先从 `mavenLocal()` 解析；
  - 否则从 GitHub Packages 拉取（凭证：`gpr.user` / `gpr.key` gradle 属性，或 `GITHUB_ACTOR` / `GITHUB_TOKEN` 环境变量）。
- 第三方联动均为 `compileOnly`，运行时缺失自动降级，不打进 jar（坐标见 `build.gradle.kts` 注释）。

## CI

每次 push 由 [.github/workflows/build.yml](.github/workflows/build.yml) 在 JDK 21 上执行 `gradlew build`，并把 `build/libs/*.jar` 上传为构建产物。
CI 会先检出 `ChiliCraft/cc-core` 并 `publishToMavenLocal` 供本仓解析（cc-core 发布到 GitHub Packages 后可改为直接拉取）。
