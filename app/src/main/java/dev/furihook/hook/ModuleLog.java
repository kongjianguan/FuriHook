package dev.furihook.hook;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

final class ModuleLog {
    private final XposedModule module;

    ModuleLog(XposedModule module) {
        this.module = module;
    }

    void event(String message) {
        write(Log.INFO, message);
    }

    void failure(String event, Throwable failure) {
        // 异常消息可能含有宿主原文，只记录异常类型。
        write(Log.ERROR, "{\"event\":\"" + event + "\",\"errorType\":\""
                + failure.getClass().getName() + "\"}");
    }

    private void write(int priority, String message) {
        try {
            module.log(priority, "FuriHook", message);
        } catch (Throwable ignored) {
            // 日志服务故障必须保持宿主调用结果。
        }
    }
}
