package dev.kongjianguan.wechatmemoryguard;

final class NativeBridge {
    private NativeBridge() {}
    static native int purge();
}
