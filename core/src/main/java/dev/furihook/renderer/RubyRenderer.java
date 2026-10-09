package dev.furihook.renderer;

import dev.furihook.engine.RubySegment;

import java.util.List;

/** 将读音数据映射到具体文本平台的渲染接口。 */
public interface RubyRenderer<T> {
    T render(CharSequence source, List<RubySegment> segments, RubyStyle style);
}
