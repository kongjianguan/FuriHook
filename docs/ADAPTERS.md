# Compose 与 WebView

## Compose

实现依据为 Google Maven 的 Compose 1.10.6 [Foundation 源码包](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-android/1.10.6/foundation-android-1.10.6-sources.jar)与 [UI Text 源码包](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-text-android/1.10.6/ui-text-android-1.10.6-sources.jar)。相同版本的 AAR 与 javap 确认签名；AAR metadata 要求 compileSdk 至少 35、AGP 至少 8.6.0。

`TextStringSimpleNode` 和 `TextAnnotatedStringNode` 的测量方法建立线程内节点上下文。`AndroidParagraphHelper_androidKt.createCharSequence(String,float,TextStyle,List,List,Density,Function4,boolean)` 返回平台文本，在 `AndroidParagraphIntrinsics` 创建 `LayoutIntrinsics` 前应用缓存的词段 RubySpan。首次缺少读音时排队到后台处理，完成后在主线程调用对应节点的 `doInvalidations`，更新布局缓存并重新测量、绘制。原始 AnnotatedString 保留，UTF-16 字符和值保持相同。

原有 Emoji、样式、占位符与链接范围保留；ReplacementSpan 重叠、词内样式边界和超宽词段跳过注音。只读文本节点上下文限定了处理范围，输入布局不会通过此适配入口处理。

文本测量使用 Compose 已应用段落样式的 AndroidTextPaint，并逐词应用覆盖范围的 metric spans。Ruby 的 LineHeightSpan 在宿主行高处理后扩展 FontMetricsInt，预留注音空间。节点状态保存完整文本版本及多个段落来源，后台结果只触发当前文本的布局刷新。

内部类名与签名经过验证后才注册。内部名称被混淆、方法被移除或版本结构变化时，需要对真实宿主 APK 重新核实。测试应用使用真实 BasicText，无 LSPosed API 依赖；Java 通过已核实的 JVM 方法调用 Compose。

### X 12.31

用户提供的 `X_12.31.0-prod.01.apks` 包名为 `com.twitter.android`，版本号为 `312310001`，SHA-256 为 `03eb62077992827bb79fc2cec582db2734dc035b9bf9c6fffbfa456296ecf073`。`scripts/verify_x_apk.py` 使用 Androguard 检查实际 DEX，核实正文调用链 `com.x.urt.items.post.notepost.b.a → androidx.compose.material3.se.c → androidx.compose.foundation.text.e1.c → modifiers.v`，以及适配器使用的字段、构造器、测量、intrinsic 与布局失效方法描述符。

`XComposeAdapter` 在实际的 `modifiers.y` 与 `modifiers.u` 节点测量期间建立上下文。段落构造器执行完成后，通过 Modern API 的 `getThisObject()` 获取实例，在独立 Spannable 中保留宿主样式并添加 RubySpan，同时创建新的平台测量缓存，再一起设置段落的 `h` 与 `i` 字段。原始 String、AnnotatedString 与语义数据保持原样；带有选择控制器的节点跳过处理。

后台读音完成后，适配器核实节点的当前完整文本，再清除对应布局缓存并请求测量、绘制。intrinsic 测量先建立的纯文本缓存，在有界宽度测量后执行一次重建；文本版本变化会重新建立状态。注册前逐项检查实际类型和返回值，任何签名不匹配记录 `x_compose_unsupported`。

静态检查输出明确区分 `staticContractVerified` 与 `runtimeHookVerified`。该安装包仅包含 arm64 原生库，现有 x86_64 专用模拟器尚未验证真实 X 页面中的注入、排版和交互。

## WebView

Hook 公开导航方法与 WebViewClient 页面回调；不替换宿主 client。使用 `evaluateJavascript` 收集有界 DOM 文本批次，再将后台词典结果通过 JSON 返回页面，不提供 JavaScriptInterface。页面导航版本和节点原文检查防止应用旧结果。

DOM 脚本保留宿主 TextNode 的身份、内容、父元素和兄弟关系。读音标签位于文档根元素下的独立元素及其 closed ShadowRoot 中，标签不接收触摸，不参与宿主正文的复制、选择和可访问性文本。React 等页面框架继续更新自己的原始节点。模块不改变正文的 HTML、业务文本或行高。

脚本使用 Range 获取原文字段的位置，使用当前字体测量读音，按实际视口、祖先裁剪区域、文本占用区域和遮挡情况决定是否绘制。读音占用的空间不足时跳过该词段。滚动、窗口尺寸、字体加载、页面可见性和相关属性变化触发位置刷新；旋转、斜切、透视与竖排文本跳过。注音层最多同时显示 128 个标签，正文范围与位置检查均有容量限制。

语言判断使用最近的明确语言声明及所在段落的假名线索。明确声明日语的纯汉字段落可以分析；明确声明中文或韩文的段落排除；没有日语线索的纯汉字段落保持原文。输入框、可编辑区域、脚本、样式、已有 Ruby 和宿主 Shadow DOM 排除。MutationObserver 监听文本、子节点和影响排版的属性，结果应用前再次检查节点身份、原文及可编辑状态。

长文本按代理对安全的 UTF-16 偏移逐段处理，每段最多 900 个 code unit。变更队列达到容量时重新扫描文档，后台队列拒绝的节点重新排队。单批最多 24 个文本项，JavaScript 待取批次最多 7 批，内存最多保存 4096 个文本片段。结果应用不会替换 TreeWalker 正在遍历的节点。

当前范围为主文档，不进入 iframe 和 Shadow DOM。页面没有开启 JavaScript 时保持宿主设置。外部页面仍由宿主自己的网络访问机制加载；模块中的词典与文本处理不调用网络服务。

## 真正启用模块的验收

在 Vector API 102 专用设备，安装相同构建的 module、testapp 与 androidTest APK，启用 `dev.furihook.testapp` 作用域后执行：

```sh
adb shell am instrument -w -r -e requireFuriHook true \
  -e class dev.furihook.testapp.RubyHookE2ETest,dev.furihook.testapp.RubyWebViewHookE2ETest,dev.furihook.testapp.RubyComposeHookE2ETest \
  dev.furihook.testapp.test/androidx.test.runner.AndroidJUnitRunner
```

WebView 和 Compose 测试成功后，在 `/sdcard/Android/data/dev.furihook.testapp/files/` 保存各自的 JSON 验证证据。Windows 脚本同时保存测试输出、框架、作用域、APK SHA-256 与模块事件。没有启用框架的 GitHub Actions 模拟器不会将普通控件测试报告为 Hook 成功。
