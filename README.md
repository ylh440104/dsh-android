# DeepSeek Harness for Android

DeepSeek Harness 的 Android 原生客户端

Kotlin + Jetpack Compose 重写

直接调用 DeepSeek 官方接口

## 功能

登录 DeepSeek 账号，走官方 PKCE 授权，账号自带的免费额度可用

查看账户余额与赠送额度

多会话管理，流式输出，可展开思考过程

内置工具调用，可在工作区内读写文件

模型可选 DeepSeek-V4-Flash 与 DeepSeek-V4-Pro

## 构建

由 GitHub Actions 编译

推 tag 触发完整流水线并发布 Release

产物 app-debug.apk

## 说明

凭证用 Android Keystore 加密保存

登录在系统浏览器完成，应用不接触密码

无第三方运行时，无 Node，无 Electron
