package dev.furihook.hook;

import android.widget.TextView;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

final class TextViewHook {
    private TextViewHook() {
    }

    static XposedInterface.HookHandle[] register(XposedModule module, TextViewObserver observer)
            throws Throwable {
        Method setText = TextView.class.getDeclaredMethod("setText",
                CharSequence.class, TextView.BufferType.class);
        Method setChars = TextView.class.getDeclaredMethod("setText", char[].class, int.class, int.class);
        XposedInterface.HookHandle first = registerMethod(module, observer, setText, "text");
        try {
            XposedInterface.HookHandle second = registerMethod(module, observer, setChars, "chars");
            return new XposedInterface.HookHandle[]{first, second};
        } catch (Throwable failure) {
            first.unhook();
            throw failure;
        }
    }

    private static XposedInterface.HookHandle registerMethod(XposedModule module,
            TextViewObserver observer, Method method, String id) {
        return module.hook(method)
                .setId("dev.furihook.textview.observe." + id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    // 原调用只执行一次，宿主异常和返回结果保持原样。
                    Object result = chain.proceed();
                    try {
                        observer.observe((TextView) chain.getThisObject());
                    } catch (Throwable failure) {
                        observer.failure(failure);
                    }
                    return result;
                });
    }
}
