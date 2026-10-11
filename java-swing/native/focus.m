#import <AppKit/AppKit.h>
#import <ApplicationServices/ApplicationServices.h>
#include <jni.h>
#include <stdint.h>

#ifdef NASHCLIP_FX
#define FN(name) Java_nashclip_NativeFocus_##name
#else
#define FN(name) Java_NativeFocus_##name
#endif

@interface NCWindowTarget : NSObject
@property(nonatomic, strong) NSRunningApplication *currentApp;
@property(nonatomic, assign) AXUIElementRef currentWindow;
@property(nonatomic, strong) NSRunningApplication *frozenApp;
@property(nonatomic, assign) AXUIElementRef frozenWindow;
@property(nonatomic) jlong generation;
@end
@implementation NCWindowTarget
- (void)dealloc {
    if (_currentWindow) CFRelease(_currentWindow);
    if (_frozenWindow) CFRelease(_frozenWindow);
}
@end

static NCWindowTarget *target(jlong handle) {
    return (__bridge NCWindowTarget *)(void *)(intptr_t)handle;
}
static AXUIElementRef focusedWindow(pid_t pid) {
    AXUIElementRef application = AXUIElementCreateApplication(pid);
    AXUIElementSetMessagingTimeout(application, 0.5);
    CFTypeRef value = NULL;
    AXError result = AXUIElementCopyAttributeValue(application, kAXFocusedWindowAttribute, &value);
    CFRelease(application);
    if (result != kAXErrorSuccess || !value || CFGetTypeID(value) != AXUIElementGetTypeID()) {
        if (value) CFRelease(value);
        return NULL;
    }
    return (AXUIElementRef)value; // caller owns the Copy reference
}

JNIEXPORT jlong JNICALL FN(create)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    @autoreleasepool { return (jlong)(intptr_t)(__bridge_retained void *)[NCWindowTarget new]; }
}
JNIEXPORT void JNICALL FN(capture)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    @autoreleasepool {
        NCWindowTarget *state = target(handle);
        NSRunningApplication *app = NSWorkspace.sharedWorkspace.frontmostApplication;
        if (app.processIdentifier == NSProcessInfo.processInfo.processIdentifier) return;
        AXUIElementRef next = app && AXIsProcessTrusted() ? focusedWindow(app.processIdentifier) : NULL;
        if (state.currentWindow) CFRelease(state.currentWindow);
        state.currentWindow = next;
        state.currentApp = app;
    }
}
JNIEXPORT jlong JNICALL FN(snapshot)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    @autoreleasepool {
        NCWindowTarget *state = target(handle);
        if (state.frozenWindow) CFRelease(state.frozenWindow);
        state.frozenWindow = NULL;
        state.frozenApp = nil;
        if (!state.currentWindow || state.currentApp.terminated || !AXIsProcessTrusted()) return 0;
        state.frozenWindow = (AXUIElementRef)CFRetain(state.currentWindow);
        state.frozenApp = state.currentApp;
        state.generation += 1;
        return state.generation;
    }
}
JNIEXPORT jboolean JNICALL FN(activate)(JNIEnv *env, jclass cls, jlong handle, jlong token) {
    (void)env; (void)cls;
    @autoreleasepool {
        NCWindowTarget *state = target(handle);
        if (!token || token != state.generation || !state.frozenWindow || state.frozenApp.terminated
                || !AXIsProcessTrusted()) return JNI_FALSE;
        AXUIElementSetMessagingTimeout(state.frozenWindow, 0.5);
        AXUIElementPerformAction(state.frozenWindow, kAXRaiseAction);
        AXUIElementRef app = AXUIElementCreateApplication(state.frozenApp.processIdentifier);
        AXUIElementSetMessagingTimeout(app, 0.5);
        AXUIElementSetAttributeValue(app, kAXFocusedWindowAttribute, state.frozenWindow);
        CFRelease(app);
        return [state.frozenApp activateWithOptions:NSApplicationActivateIgnoringOtherApps] ? JNI_TRUE : JNI_FALSE;
    }
}
JNIEXPORT jboolean JNICALL FN(matches)(JNIEnv *env, jclass cls, jlong handle, jlong token) {
    (void)env; (void)cls;
    @autoreleasepool {
        NCWindowTarget *state = target(handle);
        if (!token || token != state.generation || !state.frozenWindow || state.frozenApp.terminated
                || !AXIsProcessTrusted()
                || NSWorkspace.sharedWorkspace.frontmostApplication.processIdentifier != state.frozenApp.processIdentifier)
            return JNI_FALSE;
        AXUIElementRef current = focusedWindow(state.frozenApp.processIdentifier);
        BOOL same = current && CFEqual(current, state.frozenWindow);
        if (current) CFRelease(current);
        return same ? JNI_TRUE : JNI_FALSE;
    }
}
JNIEXPORT void JNICALL FN(releaseSnapshot)(JNIEnv *env, jclass cls, jlong handle, jlong token) {
    (void)env; (void)cls;
    @autoreleasepool {
        NCWindowTarget *state = target(handle);
        if (token != state.generation) return;
        if (state.frozenWindow) CFRelease(state.frozenWindow);
        state.frozenWindow = NULL;
        state.frozenApp = nil;
    }
}
JNIEXPORT void JNICALL FN(dispose)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    @autoreleasepool { (void)CFBridgingRelease((void *)(intptr_t)handle); }
}
