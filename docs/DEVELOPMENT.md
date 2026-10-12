# 开发指南

用户安装和使用说明见 [项目首页](../README.md)。

## 构建与测试

使用 JDK 21 和仓库中的 Gradle Wrapper：

```powershell
# Windows PowerShell
.\gradlew.bat build
```

```sh
# Linux / WSL
./gradlew build
```

模组与源码 JAR 输出到 `build/libs/`，离屏界面预览输出到 `build/previews/`。自动测试覆盖音频解码、音乐库与文件操作、配置、语言键、Unicode 输入、界面交互和可选模组适配。

离屏预览用于检查自绘界面，不等同于实际游戏操作测试。客户端交互验收使用 **Windows 原生环境**：

```powershell
.\gradlew.bat runClient
```

**不要在 WSL2 / WSLg 中启动 Minecraft 进行本项目验证。** Linux / WSL 用于构建、自动测试和离屏渲染。发布前应在 Windows 原生 Minecraft 中确认纹理、快捷键、浮窗拖动、输入法、音频设备输出及语音分享。

## 液态玻璃验证

液态玻璃通过屏幕空间近似计算曲面入射、介质内传播与背面出射，使用 Snell 折射、Fresnel 反射和 Cauchy 色散。背景被视为虚拟平面，反射取自当前画面，不能重建真实场景深度或屏幕外景物。

Linux / WSL 安装 Mesa EGL / OpenGL 库后，可验证实际 GLSL 着色器而不启动游戏：

```sh
python3 tools/validate_glass_shaders.py --previews
```

检查包括颜色还原、模糊响应、边缘采样、曲面连续性、折射与色散、圆角裁切和蒙版。生成的材质预览使用程序化背景，位于 `build/previews/`。游戏背景模糊的调用时序及实际显卡表现仍需客户端验收。

## 可选模组接口检查

IMBlocker 适配提供隔离的焦点协议测试。可指定本地 JAR 额外只读检查真实接口：

```powershell
.\gradlew.bat build '-PimblockerTestJar=C:\path\to\IMBlocker.jar'
```

语音分享编译目标为 Simple Voice Chat API 2.6.13，使用 `MergeClientSoundEvent`，不捆绑 Simple Voice Chat。可指定本地 JAR 检查插件事件接口：

```powershell
.\gradlew.bat build '-PvoicechatTestJar=C:\path\to\voicechat.jar'
```

请将示例路径替换为实际文件路径。这些检查不会调用系统输入法、启动 Minecraft 或发送语音；输入法候选窗和实际语音听感需在游戏中验证。

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
