# DeepSeek Harness for Android

DeepSeek Harness 的 Android 原生客户端

Kotlin + Jetpack Compose 重写

直接调用 DeepSeek 官方接口，不经过任何第三方通道

## 三种接入方式

账号登录

走官方 PKCE 授权，新账号自带的免费额度可用

DeepSeek API Key

用官方 API Key 直连，Key 只存在本机，用系统密钥库加密

自定义服务

填任意兼容 OpenAI 接口的地址和 Key

模型列表自动获取，也可随时刷新

本地 Ollama、硅基流动这类都能接

## 多账号

可以登录多个账号，也可以添加多个 Key 与自定义服务

抽屉里显示总额度，以及每个账号各自的额度

某个账号额度用尽时自动切换到下一个

可以手动切换，也可以关掉自动切换

自定义服务的模型可以在抽屉里单独选

## 功能

多会话管理，流式输出，可展开思考过程

回答按 Markdown 渲染，代码块可单独复制，消息可复制

内置 77 个工具调用，可以读写 /sdcard、装应用、跑 shell

接 Shizuku 后可执行提权命令、截图、模拟点击

模型可选 DeepSeek-V4-Flash 与 DeepSeek-V4-Pro

## 构建

由 GitHub Actions 编译

推 tag 触发完整流水线并发布 Release

产物 app-debug.apk

## 说明

凭证用 Android Keystore 加密保存

登录在系统浏览器完成，应用不接触密码

无第三方运行时，无 Node，无 Electron

## 跟其它安卓方案的区别

有的方案打包了整套运行时，体积大、启动慢

有的方案需要连电脑

这个直接调官方接口，启动快，内容只走你自己的账号
