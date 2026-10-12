# 开发指南

用户安装和使用说明见 [项目首页](../README.md)。

## 构建

使用 JDK 21 和仓库中的 Gradle Wrapper：

```powershell
# Windows PowerShell
.\gradlew.bat build
```

```sh
# Bash / sh
./gradlew build
```

模组与源码 JAR 输出到 `build/libs/`。

## 常用开发任务

| 任务 | Windows PowerShell | Bash / sh |
| --- | --- | --- |
| 运行自动测试 | `.\gradlew.bat test` | `./gradlew test` |
| 启动开发客户端 | `.\gradlew.bat runClient` | `./gradlew runClient` |

测试报告位于 `build/reports/tests/test/index.html`，界面预览输出到 `build/previews/`。

## 可选模组接口检查

可通过 Gradle 属性指定 IMBlocker 或 Simple Voice Chat JAR，运行额外的接口兼容性检查：

```powershell
.\gradlew.bat build '-PimblockerTestJar=C:\path\to\IMBlocker.jar'
.\gradlew.bat build '-PvoicechatTestJar=C:\path\to\voicechat.jar'
```

请将示例路径替换为实际文件路径。

## 翻译与字体

界面文案位于以下资源文件，通过 Minecraft 翻译系统读取，支持资源包覆盖：

- [简体中文](../src/main/resources/assets/ingamemusic/lang/zh_cn.json)
- [英文](../src/main/resources/assets/ingamemusic/lang/en_us.json)

新增文案应使用语言键，动态参数使用 `%s`。曲名、歌单名称、文件路径等用户数据保留原文。内置 Noto Sans SC 和单色 Noto Emoji，字体许可证随 JAR 提供。

## 第三方源码

可重新生成第三方依赖源码归档：

```sh
python3 tools/collect_dependency_sources.py
```

第三方组件来源和许可证见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。项目许可证见 [LICENSE](../LICENSE) 和 [COPYING](../COPYING)。
