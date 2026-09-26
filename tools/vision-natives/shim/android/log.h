/*
 * The only Android dependency in OpenFTC's apriltag JNI is liblog, so this is all a desktop
 * build of it needs. See tools/build-vision-natives.sh.
 */
#pragma once
#include <stdarg.h>
#include <stdio.h>

#define ANDROID_LOG_UNKNOWN 0
#define ANDROID_LOG_DEFAULT 1
#define ANDROID_LOG_VERBOSE 2
#define ANDROID_LOG_DEBUG   3
#define ANDROID_LOG_INFO    4
#define ANDROID_LOG_WARN    5
#define ANDROID_LOG_ERROR   6
#define ANDROID_LOG_FATAL   7

static inline int __android_log_print(int priority, const char *tag, const char *fmt, ...) {
    (void) priority;
    va_list args;
    va_start(args, fmt);
    fprintf(stderr, "[%s] ", tag);
    int written = vfprintf(stderr, fmt, args);
    fprintf(stderr, "\n");
    va_end(args);
    return written;
}
