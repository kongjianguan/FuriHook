package dev.furihook.adapter;

import dev.furihook.engine.RubySegment;
import dev.furihook.renderer.RubyStyle;

import java.util.List;

/** WebView DOM 与 HTML ruby 的未来适配接口。 */
public interface WebViewAdapter<T> {
    T applyRuby(CharSequence source, List<RubySegment> segments, RubyStyle style);
}
