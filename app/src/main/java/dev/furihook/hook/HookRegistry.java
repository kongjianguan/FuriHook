package dev.furihook.hook;

import android.content.pm.ApplicationInfo;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

final class HookRegistry {
    private final XposedModule module;
    private final ModuleLog log;
    private final String processName;
    private boolean attempted;
    private XposedInterface.HookHandle[] handles;

    HookRegistry(XposedModule module, ModuleLog log, String processName) {
        this.module = module;
        this.log = log;
        this.processName = processName;
    }

    synchronized void onPackage(XposedModuleInterface.PackageLoadedParam param) {
        if (attempted || !param.isFirstPackage()) {
            return;
        }
        attempted = true;
        ApplicationInfo app = param.getApplicationInfo();
        String packageName = param.getPackageName();
        if (packageName.equals("dev.furihook") || packageName.equals("android")
                || (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                || app.uid % 100000 < 10000) {
            log.event("{\"event\":\"package_skipped\",\"reason\":\"protected_process\"}");
            return;
        }
        try {
            TextViewObserver observer = new TextViewObserver(log, packageName, processName);
            handles = TextViewHook.register(module, observer);
            log.event("{\"event\":\"hook_registered\",\"methods\":[\"TextView.setText(CharSequence,BufferType)\",\"TextView.setText(char[],int,int)\"],\"api\":102}");
        } catch (Throwable failure) {
            log.failure("hook_registration_failed", failure);
        }
    }
}
