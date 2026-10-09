package dev.furihook.testapp;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public final class MainActivity extends Activity {
    public static final int ID_JAPANESE = 1001;
    public static final int ID_MIXED = 1002;
    public static final int ID_HIRAGANA = 1003;
    public static final int ID_ENGLISH = 1004;
    public static final int ID_LONG = 1005;
    public static final int ID_DYNAMIC = 1006;
    public static final int ID_RICH = 1007;
    public static final int ID_SELECTABLE = 1008;
    public static final int ID_RESOURCE = 1009;
    public static final int ID_CHAR_ARRAY = 1010;
    public static final int ID_EDITABLE = 1011;
    public static final int ID_PASSWORD = 1012;
    public static final int ID_RECYCLER = 1013;
    public static final int ITEM_COUNT = 120;

    private static final long PERIOD_MS = 1_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int dynamicCount;
    private int periodicCount;
    private boolean periodicRunning;
    private TextView dynamicText;
    private TextView periodicText;
    private RecyclerView recyclerView;

    private final Runnable periodicUpdate = new Runnable() {
        @Override
        public void run() {
            if (!periodicRunning) return;
            periodicCount++;
            periodicText.setText("周期更新次数：" + periodicCount);
            handler.postDelayed(this, PERIOD_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        periodicRunning = true;
        handler.removeCallbacks(periodicUpdate);
        handler.postDelayed(periodicUpdate, PERIOD_MS);
    }

    @Override
    protected void onStop() {
        periodicRunning = false;
        handler.removeCallbacks(periodicUpdate);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        periodicRunning = false;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    public TextView textView(int id) {
        return findViewById(id);
    }

    public RecyclerView recyclerView() {
        return recyclerView;
    }

    public int periodicCount() {
        return periodicCount;
    }

    public int dynamicCount() {
        return dynamicCount;
    }

    private View createContent() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(24));
        scrollView.addView(content);
        int initialLeft = scrollView.getPaddingLeft();
        int initialTop = scrollView.getPaddingTop();
        int initialRight = scrollView.getPaddingRight();
        int initialBottom = scrollView.getPaddingBottom();
        scrollView.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(initialLeft + insets.getSystemWindowInsetLeft(),
                    initialTop + insets.getSystemWindowInsetTop(),
                    initialRight + insets.getSystemWindowInsetRight(),
                    initialBottom + insets.getSystemWindowInsetBottom());
            return insets;
        });

        addHeading(content, "FuriHook Android 测试应用");
        addSample(content, "普通日语", ID_JAPANESE, "今日は学校で日本語を勉強します。");
        addSample(content, "中日混合", ID_MIXED, "東京駅で咖啡を飲みます。混合内容：中文、日本語。");
        addSample(content, "纯平假名", ID_HIRAGANA, "ひらがなだけのぶんしょうです。");
        addSample(content, "纯英文", ID_ENGLISH, "This paragraph contains English text only.");
        addSample(content, "多行长文本", ID_LONG,
                "日本語の長い文章を複数行で表示します。図書館で本を読み、友達と新しい計画について話しました。\n"
                        + "明日は学校へ行って、先生に研究の結果を説明する予定です。昼休みには新しい本を借りて、\n"
                        + "帰宅後に家族と夕食を食べながら、週末の旅行について相談するつもりです。\n"
                        + "日本語を学ぶために、毎日少しずつ文章を読み、知らない言葉の意味を調べています。\n"
                        + "この見本は画面上で複数行に折り返され、RecyclerView の項目と同じ画面で表示されます。\n"
                        + "文章の途中には漢字、ひらがな、カタカナ、数字 2026 と英字 FuriHook が含まれます。\n"
                        + "表示内容は固定されており、テスト時に毎回同じ文字列と改行位置を確認できます。");

        dynamicText = addSample(content, "手动动态文本", ID_DYNAMIC, "动态更新前：今日は晴れです。");
        Button updateButton = new Button(this);
        updateButton.setText("手动更新文本");
        updateButton.setId(View.generateViewId());
        updateButton.setTag("dynamic_update_button");
        updateButton.setOnClickListener(view -> {
            dynamicCount++;
            dynamicText.setText("动态更新第 " + dynamicCount + " 次：明日は図書館へ行きます。");
        });
        content.addView(updateButton);

        periodicText = addSample(content, "生命周期周期更新", View.generateViewId(), "周期更新次数：0");
        periodicText.setTag("periodic_text");

        addHeading(content, "RecyclerView 列表（120 项）");
        recyclerView = new RecyclerView(this);
        recyclerView.setId(ID_RECYCLER);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(new SampleAdapter());
        recyclerView.setNestedScrollingEnabled(false);
        content.addView(recyclerView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(280)));

        addHeading(content, "Spannable 富文本");
        TextView richText = addSample(content, "可点击富文本", ID_RICH, "日本語のリンクをタップしてください。");
        SpannableString rich = new SpannableString("日本語のリンクをタップしてください。");
        rich.setSpan(new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                widget.setTag("rich_span_clicked");
            }
        }, 0, 3, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        richText.setText(rich);
        richText.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

        TextView selectable = addSample(content, "可选择文本", ID_SELECTABLE, "選択可能な日本語テキストです。");
        selectable.setTextIsSelectable(true);

        TextView resource = addSample(content, "资源重载 setText(int)", ID_RESOURCE, "占位文本");
        resource.setText(R.string.resource_sample);

        TextView charArray = addSample(content, "char[] 重载 setText(char[], int, int)", ID_CHAR_ARRAY, "占位文本");
        char[] chars = "配列から日本語".toCharArray();
        charArray.setText(chars, 0, chars.length);

        addHeading(content, "敏感输入保护验证样例（应保持原样）");
        EditText editable = new EditText(this);
        editable.setId(ID_EDITABLE);
        editable.setHint("普通可编辑文本");
        editable.setText("入力中の日本語");
        content.addView(editable);

        EditText password = new EditText(this);
        password.setId(ID_PASSWORD);
        password.setHint("密码输入");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText("秘密の文字");
        content.addView(password);

        return scrollView;
    }

    private TextView addSample(LinearLayout parent, String label, int id, String text) {
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(Color.DKGRAY);
        title.setTextSize(14);
        parent.addView(title);

        TextView sample = new TextView(this);
        sample.setId(id);
        sample.setText(text);
        sample.setTextSize(18);
        sample.setPadding(0, dp(4), 0, dp(12));
        parent.addView(sample);
        return sample;
    }

    private void addHeading(LinearLayout parent, String text) {
        TextView heading = new TextView(this);
        heading.setText(text);
        heading.setTextColor(Color.BLACK);
        heading.setTextSize(20);
        heading.setPadding(0, dp(16), 0, dp(8));
        parent.addView(heading);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class SampleAdapter extends RecyclerView.Adapter<SampleAdapter.RowHolder> {
        @NonNull
        @Override
        public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView row = new TextView(parent.getContext());
            row.setTextSize(16);
            row.setPadding(dp(8), dp(12), dp(8), dp(12));
            return new RowHolder(row);
        }

        @Override
        public void onBindViewHolder(@NonNull RowHolder holder, int position) {
            holder.text.setText("項目 " + position + "：日本語の一覧サンプル");
            holder.text.setTag(position);
        }

        @Override
        public int getItemCount() {
            return ITEM_COUNT;
        }

        final class RowHolder extends RecyclerView.ViewHolder {
            final TextView text;

            RowHolder(@NonNull TextView itemView) {
                super(itemView);
                text = itemView;
            }
        }
    }
}
