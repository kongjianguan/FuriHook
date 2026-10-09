package dev.furihook.engine;

import java.util.List;

/** 日语词法与读音分析接口；实现负责返回相对于输入文本的 UTF-16 范围。 */
public interface ReadingEngine {
    List<RubySegment> analyze(CharSequence text);
}
