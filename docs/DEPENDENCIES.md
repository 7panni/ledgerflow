# 依赖与许可证

本仓库源码以 MIT 提供，用户自有的预测核心来自 [Show Me the Money](https://github.com/7panni/show-me-the-money)，保留 Copyright (c) 2026 7panni。

| 组件 | 版本 | 许可证与方式 |
| --- | --- | --- |
| AndroidX Compose / Material3 | BOM 2024.09.03；Compose 1.7.3；Material3 1.3.0 | Apache-2.0，运行时依赖 |
| AndroidX Activity / Lifecycle | 1.9.2 / 2.8.6 | Apache-2.0，运行时依赖 |
| 其他 AndroidX、Guava listenablefuture、JetBrains annotations | 见完整清单 | Apache-2.0，运行时依赖 |
| Kotlin | 2.0.20 | Apache-2.0，标准库及构建插件 |
| kotlinx.coroutines | 1.7.3 | Apache-2.0，运行时依赖 |
| Gradle Wrapper / Android Gradle Plugin | 8.7 / 8.5.2 | Apache-2.0，构建工具 |
| JUnit / kotlin-test | 4.13.2 / 2.0.20 | EPL-1.0 / Apache-2.0，仅测试 |
| org.json | Android 系统；测试 20240303 | Android 系统 Apache-2.0；测试依赖 JSON License，不嵌入 APK |

[完整运行时坐标](runtime-dependencies.tsv) 是本地已解析版本的清单。运行 `./gradlew :app:writeRuntimeInventory` 可重新生成 `outputs/runtime-dependencies.tsv`。构建会从各自 Maven 仓库解析依赖，本仓库未内置这些依赖的二进制。

Apache-2.0 文本和 Gradle NOTICE 保留在 [licenses](licenses/)；APK 内的声明和许可证在 `app/src/main/assets/`。Gradle Wrapper 源码头保留原版权声明，wrapper JAR 为随源码分发的构建引导程序。

官方来源：

- https://github.com/androidx/androidx
- https://github.com/JetBrains/kotlin
- https://github.com/Kotlin/kotlinx.coroutines
- https://github.com/gradle/gradle/tree/v8.7.0
- https://github.com/google/guava
- https://github.com/JetBrains/java-annotations
- https://github.com/junit-team/junit4
- https://github.com/stleary/JSON-java
