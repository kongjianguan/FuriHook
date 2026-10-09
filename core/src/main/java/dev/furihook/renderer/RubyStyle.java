package dev.furihook.renderer;

/** Ruby 字号、偏移和间距样式的适配接口。 */
public interface RubyStyle {
    float getTextSizeScale();

    float getVerticalOffsetEm();

    float getInterlinearSpacingEm();
}
