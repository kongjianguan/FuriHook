package dev.furihook.adapter;

import dev.furihook.engine.RubySegment;
import dev.furihook.renderer.RubyStyle;

import java.util.List;

/** Jetpack Compose 文本布局的未来适配接口。 */
public interface ComposeAdapter<T> {
    T applyRuby(CharSequence source, List<RubySegment> segments, RubyStyle style);
}
