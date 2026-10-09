# TextView 后台读音与原地渲染

## 验证失败方式

实现与设备验证覆盖以下情况：词典初始化或资源读取失败；输入、密码、Editable 或预计算文本进入注音；原方法异常被吞掉或重复执行；同一视图快速更新产生旧读音；RecyclerView 复用遗留旧 span；后台任务持有宿主视图或无限积压；原文或阅读结果出现在日志；字体、宽度或已有 span 导致词语超宽；完成分析后视图脱离窗口；重复注音触发无界布局；关闭调试日志后注音停止。

## 调用与缓冲

公开的 `setText(CharSequence, BufferType)` 使用 API 102 的 `Chain.proceed(Object[])` 执行一次原调用。对非输入控件的普通文本，原调用选择 `BufferType.SPANNABLE`。宿主可观察到 `getText()` 的具体类型变化，字符内容、原始样式及业务调用次数保持原样。Editable、预计算文本及转换控件保持宿主原有参数。

公开字符数组入口执行一次原调用。如果宿主缓冲已经是 Spannable，结果可以原地注音；产生不可变 CharWrapper 的路径保留文本检测。模块不使用隐藏 TextView 方法，不调用第二次 `setText()`。

后台任务只持有有界字符串快照和视图弱引用，在专用线程初始化 Kuromoji 词典并执行分析。每个视图有一个待处理任务，后续文本更新替换待处理快照。完成后在主线程检查视图、文本对象、更新序号、字符内容、敏感输入策略及可用宽度；过时结果直接丢弃。缓存只存放有界内存中的原文和不可变读音范围。

## 原地注音

在当前 Spannable 上添加 RubySpan，不替换文本对象。绘制前筛除超出单行内容宽度的词段；宿主 ReplacementSpan 或词内样式边界由渲染器保护。文本 SpanWatcher 接收布局变化，模块没有字符修改或递归设置文本路径。

读音错误和布局限制由真实语料、Android 28/36 instrumentation，以及 Windows Vector API 102 注入环境验证。设备报告分别记录普通应用测试与 Hook 专项测试结果。

## 官方接口

- [API 102 源码核实记录](API_VERIFICATION.md)
- [TextView BufferType 与文本接口](https://developer.android.com/reference/android/widget/TextView)
- [Spannable span 修改接口](https://developer.android.com/reference/android/text/Spannable)
- [PrecomputedText 不允许修改 MetricAffectingSpan](https://developer.android.com/reference/android/text/PrecomputedText)
