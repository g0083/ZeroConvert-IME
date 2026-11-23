#pragma once
#include <jni.h>
#include <string>
void assist_abort();
void assist_close();
long long assist_epoch();
std::string assist_from_java(JNIEnv * env, jstring text);
jstring assist_to_java(JNIEnv * env, const std::string & text);
std::string benchmark_keyboard_repair(jlong handle, const char *payload, unsigned long long timeout);
