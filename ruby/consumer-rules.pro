# 词典资源相对于 Tokenizer 的包名读取。
-keepnames class com.atilika.kuromoji.ipadic.Tokenizer
# 模块与宿主各自的 ClassLoader 用公共类名识别自有 span。
-keep public class dev.furihook.renderer.RubySpan {
    public *;
}
