package dev.furihook.hook;

import android.widget.TextView;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

final class TextViewHook {
    private TextViewHook() {
    }

    static XposedInterface.HookHandle[] register(XposedModule module, TextViewAnnotator annotator)
            throws Throwable {
        Method setText = TextView.class.getDeclaredMethod("setText",
                CharSequence.class, TextView.BufferType.class);
        Method setChars = TextView.class.getDeclaredMethod("setText", char[].class, int.class, int.class);
        XposedInterface.HookHandle first = registerMethod(module, annotator, setText, "text");
        try {
            XposedInterface.HookHandle second = registerMethod(module, annotator, setChars, "chars");
            return new XposedInterface.HookHandle[]{first, second};
        } catch (Throwable failure) {
            first.unhook();
            throw failure;
        }
    }

    private static XposedInterface.HookHandle registerMethod(XposedModule module,
            TextViewAnnotator annotator, Method method, String id) {
        return module.hook(method)
                .setId("dev.furihook.textview.ruby." + id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object[] arguments = null;
                    try {
                        if (id.equals("text")) {
                            TextView view = (TextView) chain.getThisObject();
                            CharSequence text = (CharSequence) chain.getArg(0);
                            TextView.BufferType type = (TextView.BufferType) chain.getArg(1);
                            if (annotator.shouldPrepareBuffer(view, text, type)) {
                                arguments = new Object[]{text, TextView.BufferType.SPANNABLE};
                            }
                        }
                    } catch (Throwable failure) {
                        annotator.failure("buffer_preparation_failed", failure);
                    }
                    // 原调用执行一次，宿主异常继续传播；后续只修改 span。
                    Object result = arguments == null ? chain.proceed() : chain.proceed(arguments);
                    try {
                        annotator.observe((TextView) chain.getThisObject());
                    } catch (Throwable failure) {
                        annotator.failure("observation_failed", failure);
                    }
                    return result;
                });
    }
}
