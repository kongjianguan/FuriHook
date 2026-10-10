# Compose 与 WebView

## Compose

实现依据为 Google Maven 的 Compose 1.10.6 [Foundation 源码包](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-android/1.10.6/foundation-android-1.10.6-sources.jar)与 [UI Text 源码包](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-text-android/1.10.6/ui-text-android-1.10.6-sources.jar)。相同版本的 AAR 与 javap 确认签名；AAR metadata 要求 compileSdk 至少 35、AGP 至少 8.6.0。

`TextStringSimpleNode` 和 `TextAnnotatedStringNode` 的测量方法建立线程内节点上下文。`AndroidParagraphHelper_androidKt.createCharSequence(String,float,TextStyle,List,List,Density,Function4,boolean)` 返回平台文本，在 `AndroidParagraphIntrinsics` 创建 `LayoutIntrinsics` 前应用缓存的词段 RubySpan。首次缺少读音时排队到后台处理，完成后在主线程调用对应节点的 `doInvalidations`，更新布局缓存并重新测量、绘制。原始 AnnotatedString 保留，UTF-16 字符和值保持相同。

原有 Emoji、样式、占位符与链接范围保留；ReplacementSpan 重叠、词内样式边界和超宽词段跳过注音。只读文本节点上下文限定了处理范围，输入布局不会通过此适配入口处理。

内部类名与签名经过验证后才注册。内部名称被混淆、方法被移除或版本结构变化时，需要对真实宿主 APK 重新核实。测试应用使用真实 BasicText，无 LSPosed API 依赖；Java 通过已核实的 JVM 方法调用 Compose。

## WebView

Hook 公开导航方法与 WebViewClient 页面回调；不替换宿主 client。使用 `evaluateJavascript` 收集有界 DOM 文本批次，再将后台词典结果通过 JSON 返回页面，不提供 JavaScriptInterface。页面导航版本和节点原文检查防止应用旧结果。

DOM 脚本用标准 `<ruby><rb>…</rb><rt>…</rt></ruby>` 处理文本节点，保持父元素与事件处理器。MutationObserver 收集后续变化，通过自有节点标记避免重复注音。输入框、可编辑区域、脚本、样式、已有 Ruby 排除；复制包含模块 Ruby 的选区时删除自有 rt。

当前范围为主文档，不进入 iframe 和 Shadow DOM。页面没有开启 JavaScript 时保持宿主设置。外部页面仍由宿主自己的网络访问机制加载；模块中的词典与文本处理不调用网络服务。

## 真正启用模块的验收

在 Vector API 102 专用设备，安装相同构建的 module、testapp 与 androidTest APK，启用 `dev.furihook.testapp` 作用域后执行：

```sh
adb shell am instrument -w -r -e requireFuriHook true \
  -e class dev.furihook.testapp.RubyHookE2ETest,dev.furihook.testapp.RubyWebViewHookE2ETest,dev.furihook.testapp.RubyComposeHookE2ETest \
  dev.furihook.testapp.test/androidx.test.runner.AndroidJUnitRunner
```

WebView 和 Compose 测试成功后，在 `/sdcard/Android/data/dev.furihook.testapp/files/` 保存各自的 JSON 验证证据。Windows 脚本同时保存测试输出、框架、作用域、APK SHA-256 与模块事件。没有启用框架的 GitHub Actions 模拟器不会将普通控件测试报告为 Hook 成功。
