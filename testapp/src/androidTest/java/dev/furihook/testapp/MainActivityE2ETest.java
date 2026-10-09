package dev.furihook.testapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.text.Selection;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class MainActivityE2ETest {
    @Test
    public void activityShowsAllTextSamplesAndOverloadResults() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals("今日は学校で日本語を勉強します。", text(activity, MainActivity.ID_JAPANESE));
                assertTrue(text(activity, MainActivity.ID_MIXED).contains("東京"));
                assertEquals("ひらがなだけのぶんしょうです。", text(activity, MainActivity.ID_HIRAGANA));
                assertTrue(text(activity, MainActivity.ID_ENGLISH).startsWith("This paragraph"));
                assertTrue(text(activity, MainActivity.ID_LONG).length() > 100);
                assertEquals("東京の図書館", text(activity, MainActivity.ID_RESOURCE));
                assertEquals("配列から日本語", text(activity, MainActivity.ID_CHAR_ARRAY));
            });
        }
    }

    @Test
    public void dynamicButtonChangesTextAndCounter() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                activity.findViewWithTag("dynamic_update_button").performClick();
                assertEquals(1, activity.dynamicCount());
                assertTrue(text(activity, MainActivity.ID_DYNAMIC).contains("第 1 次"));
            });
        }
    }

    @Test
    public void richTextSpanAndSelectionRemainAvailable() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                TextView rich = activity.textView(MainActivity.ID_RICH);
                assertTrue(rich.getText() instanceof Spanned);
                ClickableSpan[] spans = ((Spanned) rich.getText()).getSpans(0, 3, ClickableSpan.class);
                assertEquals(1, spans.length);

                assertNotNull(rich.getLayout());
                float x = rich.getTotalPaddingLeft() + rich.getLayout().getPrimaryHorizontal(1);
                float y = rich.getTotalPaddingTop() + rich.getLayout().getLineBaseline(0);
                long downTime = android.os.SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(downTime, downTime,
                        MotionEvent.ACTION_DOWN, x, y, 0);
                MotionEvent up = MotionEvent.obtain(downTime, downTime + 10,
                        MotionEvent.ACTION_UP, x, y, 0);
                try {
                    assertTrue(rich.onTouchEvent(down));
                    assertTrue(rich.onTouchEvent(up));
                } finally {
                    down.recycle();
                    up.recycle();
                }
                assertEquals("rich_span_clicked", rich.getTag());

                TextView selectable = activity.textView(MainActivity.ID_SELECTABLE);
                assertTrue(selectable.isTextSelectable());
                CharSequence text = selectable.getText();
                assertTrue(text instanceof Spannable);
                Selection.setSelection((Spannable) text, 0, 3);
                assertEquals(0, Selection.getSelectionStart(text));
                assertEquals(3, Selection.getSelectionEnd(text));
            });
        }
    }

    @Test
    public void recyclerViewScrollsAndBindsRecycledRows() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                androidx.recyclerview.widget.RecyclerView recycler = activity.recyclerView();
                assertEquals(MainActivity.ITEM_COUNT, recycler.getAdapter().getItemCount());
                recycler.scrollToPosition(90);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                androidx.recyclerview.widget.RecyclerView recycler = activity.recyclerView();
                androidx.recyclerview.widget.RecyclerView.ViewHolder holder =
                        recycler.findViewHolderForAdapterPosition(90);
                assertNotNull(holder);
                assertEquals("項目 90：日本語の一覧サンプル", ((TextView) holder.itemView).getText().toString());
                recycler.scrollToPosition(0);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                androidx.recyclerview.widget.RecyclerView.ViewHolder holder =
                        activity.recyclerView().findViewHolderForAdapterPosition(0);
                assertNotNull(holder);
                assertEquals("項目 0：日本語の一覧サンプル", ((TextView) holder.itemView).getText().toString());
            });
        }
    }

    @Test
    public void editableAndPasswordInputsKeepTheirOriginalText() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                EditText editable = activity.findViewById(MainActivity.ID_EDITABLE);
                EditText password = activity.findViewById(MainActivity.ID_PASSWORD);
                assertEquals("入力中の日本語", editable.getText().toString());
                assertEquals("秘密の文字", password.getText().toString());
                assertTrue((password.getInputType() & android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0);
            });
        }
    }

    @Test
    public void periodicUpdatesStopWithActivityAndResumeWithLifecycle() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            int initialCount = readPeriodicCount(scenario);
            int runningCount = awaitPeriodicCountGreaterThan(scenario, initialCount);

            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            int stoppedCount = readPeriodicCount(scenario);
            Thread.sleep(1_200L);
            assertEquals(stoppedCount, readPeriodicCount(scenario));

            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            int resumedCount = awaitPeriodicCountGreaterThan(scenario, stoppedCount);
            assertTrue("恢复后周期计数应继续增加", resumedCount > runningCount);
        }
    }

    private static int readPeriodicCount(ActivityScenario<MainActivity> scenario) {
        int[] count = new int[1];
        scenario.onActivity(activity -> count[0] = activity.periodicCount());
        return count[0];
    }

    private static int awaitPeriodicCountGreaterThan(
            ActivityScenario<MainActivity> scenario, int previousCount) throws InterruptedException {
        long deadline = android.os.SystemClock.uptimeMillis() + 5_000L;
        int currentCount = readPeriodicCount(scenario);
        while (currentCount <= previousCount
                && android.os.SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(100L);
            currentCount = readPeriodicCount(scenario);
        }
        assertTrue("周期计数应在 5 秒内增加", currentCount > previousCount);
        return currentCount;
    }

    private static String text(MainActivity activity, int id) {
        return activity.textView(id).getText().toString();
    }
}
