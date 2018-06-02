//
// Created by Todd Smith on 6/1/18.
//

#include <jni.h>
#include <string.h>
#include <stdio.h>

extern "C" {
JNIEXPORT jstring JNICALL Java_com_tbse_arvectorfields_GoogleSignInActivity_stringMethod
        (JNIEnv *env, jobject thiz, jstring string) {

    const char *name = (*env).GetStringUTFChars(string, NULL);
    char msg[60] = "Hello ";
    jstring result;

    strcat(msg, name);
    (*env).ReleaseStringUTFChars(string, name);
    puts(msg);
    result = (*env).NewStringUTF(msg);
    return result;
}

}
