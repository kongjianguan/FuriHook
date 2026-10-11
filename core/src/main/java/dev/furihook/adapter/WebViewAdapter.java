package dev.furihook.adapter;

import dev.furihook.engine.RubySegment;
import dev.furihook.renderer.RubyStyle;

import java.util.List;

/** WebView 原始文本与独立注音显示的适配接口。 */
public interface WebViewAdapter<T> {
    T applyRuby(CharSequence source, List<RubySegment> segments, RubyStyle style);
}
