package dev.furihook.hook;

import android.text.Editable;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.widget.EditText;
import android.widget.TextView;

final class SensitiveTextPolicy {
    private SensitiveTextPolicy() {
    }

    static boolean shouldSkip(TextView view) {
        if (view instanceof EditText || view.getKeyListener() != null
                || view.getTransformationMethod() instanceof PasswordTransformationMethod) {
            return true;
        }
        int inputType = view.getInputType();
        int inputClass = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        boolean password = inputClass == InputType.TYPE_CLASS_TEXT
                && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD);
        password |= inputClass == InputType.TYPE_CLASS_NUMBER
                && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        return password || inputType != InputType.TYPE_NULL || view.getText() instanceof Editable;
    }
}
