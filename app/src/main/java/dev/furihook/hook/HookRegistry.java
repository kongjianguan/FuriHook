package dev.furihook.hook;

import android.content.pm.ApplicationInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.furihook.engine.KuromojiReadingEngine;
import dev.furihook.engine.ReadingEngine;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

final class HookRegistry {
    private final XposedModule module;
    private final ModuleLog log;
    private final String processName;
    private boolean attempted;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final ReadingEngine engine = new KuromojiReadingEngine();
    private boolean allowed;
    private boolean composeAttempted;

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
        allowed = true;
        try {
            TextViewAnnotator annotator = new TextViewAnnotator(log, packageName, processName, engine);
            Collections.addAll(handles, TextViewHook.register(module, annotator));
            log.event("{\"event\":\"hook_registered\",\"methods\":[\"TextView.setText(CharSequence,BufferType)\",\"TextView.setText(char[],int,int)\"],\"api\":102}");
        } catch (Throwable failure) {
            log.failure("hook_registration_failed", failure);
        }
        try {
            Collections.addAll(handles, WebViewHook.register(module, log, packageName, processName, engine));
        } catch (Throwable failure) {
            log.failure("webview_hook_registration_failed", failure);
        }
    }

    synchronized void onReady(XposedModuleInterface.PackageReadyParam param) {
        onPackage(param);
        if (!allowed || composeAttempted || !param.isFirstPackage()) return;
        composeAttempted = true;
        try {
            Collections.addAll(handles, ComposeHook.register(module, log, param.getClassLoader(), engine));
        } catch (Throwable failure) {
            log.failure("compose_hook_registration_failed", failure);
        }
    }
}
