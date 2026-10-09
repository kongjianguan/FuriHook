# 第二阶段研发要求

## 本地读音分析

现有 `ReadingEngine.analyze(CharSequence)` 返回 `RubySegment` 集合，每个范围使用相对于原文的 UTF-16 半开区间。实现需要验证范围顺序、重叠与代理对边界，保留完整词语读音和对应汉字段落的注音，处理活用、熟字训和专有名词。禁止逐个汉字独立查询读音。

研究候选采用 [Sudachi Java 官方实现](https://github.com/WorksApplications/Sudachi)、[Kuromoji 官方实现](https://github.com/atilika/kuromoji)和 [MeCab 官方资料](https://github.com/taku910/mecab/blob/master/mecab/doc/index.html)。通过同一真实日语语料比较分词/读音准确率、Android 28 与 36 运行、字典体积、内存、初始化耗时和许可证。Java 方案可以直接接入当前接口，MeCab 需要独立 JNI/NDK 模块。

词元的 reading 常以片假名输出，需要转换为平假名。归一化后的词元位置可能无法直接代表原文范围，必须使用分析器提供的原始位置证据。汉字与假名混排的精确范围需要处理送假名、重复读音、多种合法读法与歧义，无法确认的结果应保持原文。

词典本地安装与校验，分析在专用后台任务执行。缓存采用有界内存策略，配置变化、字典更新和宿主文本变化需失效。JLPT 过滤需要明确词条和等级的数据来源、许可证及未收录词的处理规则。

## TextView Ruby 排版

`RubyRenderer` 和 `RubyStyle` 定义了独立的渲染输入与样式接口。先在测试应用中完成真实词元到可交互注音的端到端验证，然后接入宿主 Hook。验收覆盖自动换行、行高、字号、基线、相邻注音间距、已有 spans、点击、选择、复制、可访问性及 RecyclerView 复用。

[ReplacementSpan 官方接口](https://developer.android.com/reference/android/text/style/ReplacementSpan)提供宽度测量、FontMetrics 与绘制能力；需要实测覆盖段跨行、宽度扩张、span 组合与选择行为。当前阶段没有 RubySpan 实现，不承诺单个 ReplacementSpan 可以解决完整排版。

渲染状态需要与宿主业务文本和 View 生命周期分离，使用版本标识丢弃过时后台结果，防止修改事件递归、重复布局和复用 View 接收旧文本的注音。系统组件和输入控件继续执行明确的跳过规则。

## WebView

WebView 继承 AbsoluteLayout，HTML 文本由浏览器排版。独立适配层研究 DOM 文本节点、本地读音处理以及 `<ruby><rt>`，需要限定来源、iframe 权限、脚本桥访问、动态 DOM 更新与重复注入规则。编辑区域、密码字段、脚本和样式节点需要排除。WebView 数据不通过远程服务处理。

## Compose

[Compose 官方文本文档](https://developer.android.com/develop/ui/compose/text)描述 `Text`、`BasicText` 和可编辑文本组件。独立适配层需要实测不同 Compose 版本的文本布局入口与源码变化，验证 AnnotatedString 样式、重组、选择、点击、语义和布局节点复用。当前 TextView Hook 无法覆盖独立的 Compose 排版。

## 配置与设备验收

应用作用域继续由 LSPosed 管理器控制；配置界面增加注音等级、样式与日志开关。跨进程配置必须采用经过核实的官方服务或明确的 Android IPC，确保宿主进程读取配置的成本有限。

具备 API 102 的 LSPosed 测试设备后，验证模块识别、注入、日志、敏感输入跳过、原方法异常、进程重启与各宿主版本。保留 APK SHA-256、工具链、设备/API/框架版本、作用域、实际日志和可重复执行的测试报告。构建、普通模拟器运行与 LSPosed Hook 执行分别记录。
