package dev.furihook;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        content.requestApplyInsets();
        TextView version = findViewById(R.id.version);
        version.setText(getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME);
    }
}
