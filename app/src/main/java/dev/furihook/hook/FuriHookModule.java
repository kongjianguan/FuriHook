package dev.furihook.hook;

import io.github.libxposed.api.XposedModule;

public final class FuriHookModule extends XposedModule {
    private HookRegistry registry;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        if (param.isSystemServer()) {
            detach();
            return;
        }
        ModuleLog log = new ModuleLog(this);
        registry = new HookRegistry(this, log, param.getProcessName());
        log.event("{\"event\":\"module_loaded\",\"api\":102}");
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        registry.onPackage(param);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        // API 28 在此接收包就绪事件；注册器保证每个进程只注册一次。
        registry.onPackage(param);
    }
}
