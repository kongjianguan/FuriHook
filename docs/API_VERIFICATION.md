# API 核实记录

核实日期：2026-10-09。

## 官方发布与资料

Maven Central 提供 [102.0.0 发布目录](https://repo.maven.apache.org/maven2/io/github/libxposed/api/102.0.0/)。其 POM 标明 AAR 格式、Apache 2.0 许可证，SCM 指向 `https://github.com/libxposed/api.git`。模块使用 `compileOnly("io.github.libxposed:api:102.0.0")`。

本项目实际阅读的是该版本官方发布的 [sources.jar](https://repo.maven.apache.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0-sources.jar)，SHA-256：`c4a5761c2409f411ca0f67983a687fbd9dd9a76250d7a68ab7cb2950c820444e`。核实文件包括 `XposedModule.java`、`XposedModuleInterface.java`、`XposedInterface.java`、`XposedInterfaceWrapper.java` 和 `package-info.java`。

当前官方 GitHub 仓库、`libxposed/example` 官方示例和 `libxposed.github.io/api/` 返回 HTTP 404，无法读取示例项目建立骨架。[LSPosed 官方 Modern API Wiki](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)可以读取。项目骨架遵循发布源码中的官方初始化示例与元数据定义；编译和 APK 检查验证实际产物。

## 已核实定义

| 项目 | 102.0.0 实际定义 |
| --- | --- |
| 入口 | `XposedModule extends XposedInterfaceWrapper implements XposedModuleInterface`，公开无参数构造器 |
| 初始化 | 框架调用内部 `attachFramework`；模块在 `onModuleLoaded(ModuleLoadedParam)` 初始化 |
| 包加载 | `onPackageLoaded(PackageLoadedParam)` 在 API 29+ 的默认类加载器就绪后调用 |
| 包就绪 | `onPackageReady(PackageReadyParam)` 在 Application 创建前调用，可用于 API 28 |
| 注册 | `HookBuilder hook(Executable origin)` |
| 拦截 | `HookHandle HookBuilder.intercept(Hooker hooker)` |
| 回调 | `Object Hooker.intercept(XposedInterface.Chain chain) throws Throwable` |
| 原调用 | `Object Chain.proceed() throws Throwable` 保持当前接收对象和参数 |
| 参数 | `getThisObject()`、`getArg(int)`、不可变 `getArgs()` |
| 隔离 | `HookBuilder.setExceptionMode(ExceptionMode.PROTECTIVE)` |
| 日志 | `log(int priority, String tag, String message)`，另有 Throwable 重载 |
| 去重标识 | API 102 `setId(String)`；同模块同方法同标识原子替换已有 Hook |
| 句柄 | `HookHandle.unhook()`、`getId()`、`replaceHook(Hooker)` |

源码的公开拦截链名称为 `Chain`。`PROTECTIVE` 会隔离拦截器自身异常，`proceed()` 抛出的宿主异常始终传播。FuriHook 将观测代码放在原调用完成后的独立异常边界内。

## 元数据

入口类写入 `META-INF/xposed/java_init.list`。名称与说明取 Android `label`、`description`；`scope.list` 每行一个包名；`module.prop` 使用 Java Properties 格式。

```properties
minApiVersion=102
targetApiVersion=102
staticScope=false
exceptionMode=protective
autoHotReload=false
```

`staticScope=false` 允许管理器选择范围，默认推荐测试应用。模块拒绝系统服务与系统应用，进程中的后续包回调不会重复注册 Hook。热重载维持 API 默认拒绝行为。

## TextView 方法

已阅读 [Android TextView API](https://developer.android.com/reference/android/widget/TextView)及 AOSP 的 [Android 9 源码](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/core/java/android/widget/TextView.java)、[Android 16 源码](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/widget/TextView.java)。

两个版本均存在公开的 `setText(CharSequence, BufferType)`。`final setText(CharSequence)`、资源重载和 `setTextKeepState` 会调用该入口。公开的 `final setText(char[], int, int)` 直接调用内部四参数方法，因此项目独立 Hook 字符数组入口。

所选方法均为 SDK 公开方法，不需要调用 `setAccessible` 或访问隐藏方法。读取原方法完成后的 `getText()` 能反映过滤器处理后的当前内容。完整覆写且不调用父类的自定义控件属于覆盖边界。
