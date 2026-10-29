#include <jni.h>
#include <malloc.h>

// Only attempts to release allocator-owned *unused* pages.
// Does not free live allocations, force a GC, or touch Java heap objects.
JNIEXPORT jint JNICALL
Java_dev_kongjianguan_wechatmemoryguard_NativeBridge_purge(
        JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return (jint)mallopt(M_PURGE, 0);
}
