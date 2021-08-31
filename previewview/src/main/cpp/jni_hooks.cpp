#include <jni.h>

extern "C" {
    JNIEXPORT jint JNICALL JNI_On_Load(JavaVM *jvm, void *reserved) {
        return JNI_VERSION_1_6;
    }
}