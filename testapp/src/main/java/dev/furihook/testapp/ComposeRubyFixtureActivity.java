package dev.furihook.testapp;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.activity.ComponentActivity;
import androidx.compose.foundation.text.BasicTextKt;
import androidx.compose.foundation.text.TextAutoSize;
import androidx.compose.runtime.Composer;
import androidx.compose.ui.Modifier;
import androidx.compose.ui.graphics.ColorProducer;
import androidx.compose.ui.platform.ComposeView;
import androidx.compose.ui.text.AnnotatedString;
import androidx.compose.ui.text.LinkAnnotation;
import androidx.compose.ui.text.TextLayoutResult;
import androidx.compose.ui.text.TextStyle;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** 使用真实 BasicText；Java 调用已核实的 Compose 1.10.6 JVM 方法。 */
public final class ComposeRubyFixtureActivity extends ComponentActivity {
    public static final int ID_SIMPLE = 2101;
    public static final int ID_RICH = 2102;
    public static final int ID_LONG = 2103;
    public static final int ID_ENGLISH = 2104;
    public static final int ID_UPDATE = 2105;
    public static final String ORIGINAL = "今日は学校で日本語を勉強します。";
    public static final String UPDATED = "明日は図書館へ行きます。";

    private ComposeView richView;
    private TextLayoutResult richLayout;
    private TextLayoutResult longLayout;
    private AnnotatedString richSource;
    private int linkClicks;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(24, 48, 24, 24);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);

        addSimple(column, ID_SIMPLE, "学校");
        richView = addView(column, ID_RICH);
        setRich(ORIGINAL);
        ComposeView longView = addView(column, ID_LONG);
        StringBuilder longText = new StringBuilder();
        for (int index = 0; index < 4; index++) {
            longText.append("日本語の長い文章を複数行で表示します。図書館で本を読み、友達と新しい計画について話しました。")
                    .append("明日は学校へ行って、先生に研究の結果を説明する予定です。");
        }
        AnnotatedString longSource = new AnnotatedString.Builder(longText.toString()).toAnnotatedString();
        longView.setContent((composer, flags) -> {
            renderAnnotated(longSource, result -> { longLayout = result; return Unit.INSTANCE; }, composer);
            return Unit.INSTANCE;
        });
        addSimple(column, ID_ENGLISH, "English only. ひらがなだけです。");
        Button update = new Button(this);
        update.setId(ID_UPDATE);
        update.setText("更新 Compose 文本");
        update.setOnClickListener(view -> setRich(UPDATED));
        column.addView(update);
    }

    public TextLayoutResult richLayout() { return richLayout; }
    public TextLayoutResult longLayout() { return longLayout; }
    public AnnotatedString richSource() { return richSource; }
    public int linkClicks() { return linkClicks; }

    private void setRich(String text) {
        AnnotatedString.Builder builder = new AnnotatedString.Builder(text);
        int start = text.indexOf("日本語");
        if (start >= 0) {
            builder.addLink(new LinkAnnotation.Clickable("japanese", null,
                    link -> linkClicks++), start, start + 3);
        }
        richSource = builder.toAnnotatedString();
        richLayout = null;
        AnnotatedString value = richSource;
        richView.setContent((composer, flags) -> {
            renderAnnotated(value, result -> { richLayout = result; return Unit.INSTANCE; }, composer);
            return Unit.INSTANCE;
        });
    }

    private ComposeView addView(LinearLayout column, int id) {
        ComposeView view = new ComposeView(this);
        view.setId(id);
        column.addView(view, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private void addSimple(LinearLayout column, int id, String text) {
        addView(column, id).setContent((composer, flags) -> {
            invoke(Methods.SIMPLE, text, Modifier.Companion, TextStyle.Companion.getDefault(),
                    null, 1, true, Integer.MAX_VALUE, 1, null, null, composer, 0, 0);
            return Unit.INSTANCE;
        });
    }

    private static void renderAnnotated(AnnotatedString text,
            Function1<TextLayoutResult, Unit> callback, Composer composer) {
        invoke(Methods.ANNOTATED, text, Modifier.Companion, TextStyle.Companion.getDefault(),
                callback, 1, true, Integer.MAX_VALUE, 1, Collections.emptyMap(),
                null, null, composer, 0, 0, 0);
    }

    private static void invoke(Method method, Object... arguments) {
        try {
            method.invoke(null, arguments);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("Compose fixture 调用失败", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Compose fixture JVM 接口发生变化", failure);
        }
    }

    private static final class Methods {
        static final Method SIMPLE = find("BasicText-RWo7tUw", String.class,
                Modifier.class, TextStyle.class, Function1.class, int.class, boolean.class,
                int.class, int.class, ColorProducer.class, TextAutoSize.class,
                Composer.class, int.class, int.class);
        static final Method ANNOTATED = find("BasicText-CL7eQgs", AnnotatedString.class,
                Modifier.class, TextStyle.class, Function1.class, int.class, boolean.class,
                int.class, int.class, Map.class, ColorProducer.class, TextAutoSize.class,
                Composer.class, int.class, int.class, int.class);

        private static Method find(String name, Class<?>... types) {
            try {
                return BasicTextKt.class.getDeclaredMethod(name, types);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
