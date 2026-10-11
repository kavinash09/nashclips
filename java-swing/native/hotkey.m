#import <AppKit/AppKit.h>
#include <Carbon/Carbon.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>

/* Send shortcut and mouse-click notifications to the parent Java process. */
static OSStatus hotkeyPressed(EventHandlerCallRef handler, EventRef event, void *context) {
    (void)handler;
    (void)context;
    EventHotKeyID key;
    OSStatus status = GetEventParameter(event, kEventParamDirectObject, typeEventHotKeyID,
                                       NULL, sizeof(key), NULL, &key);
    if (status == noErr && key.signature == 'mCop' && key.id == 1) {
        puts("HOTKEY");
        fflush(stdout);
        return noErr;
    }
    return eventNotHandledErr;
}

/* A closed pipe means Java quit, even if it was stopped from the IDE. */
static void *watchParent(void *context) {
    (void)context;
    while (getchar() != EOF) { }
    exit(0);
    return NULL;
}

int main(void) {
    @autoreleasepool {
        NSApplication *app = [NSApplication sharedApplication];
        [app setActivationPolicy:NSApplicationActivationPolicyProhibited];
        EventTypeSpec type = { kEventClassKeyboard, kEventHotKeyPressed };
        EventHandlerRef handler = NULL;
        EventHotKeyRef reference = NULL;
        OSStatus status = InstallApplicationEventHandler(hotkeyPressed, 1, &type, NULL, &handler);
        if (status == noErr) {
            EventHotKeyID key = { 'mCop', 1 };
            status = RegisterEventHotKey(kVK_ANSI_V, controlKey | optionKey, key,
                                        GetApplicationEventTarget(), kEventHotKeyExclusive, &reference);
        }
        if (status == noErr) {
            puts("READY");
        } else {
            printf("ERROR Control+Option+V could not be registered (%d); it may already be in use\n",
                   (int)status);
        }
        fflush(stdout);

        /* The monitor only observes button-down positions; it never intercepts clicks. */
        id mouseMonitor = [NSEvent addGlobalMonitorForEventsMatchingMask:
                (NSEventMaskLeftMouseDown | NSEventMaskRightMouseDown | NSEventMaskOtherMouseDown)
                handler:^(NSEvent *event) {
                    CGEventRef mouseEvent = [event CGEvent];
                    if (mouseEvent == NULL) return;
                    CGPoint point = CGEventGetLocation(mouseEvent);
                    printf("CLICK %.0f %.0f\n", point.x, point.y);
                    fflush(stdout);
                }];
        if (mouseMonitor == nil) {
            puts("MOUSE_UNAVAILABLE");
        } else {
            puts("MOUSE_READY");
        }
        fflush(stdout);

        pthread_t parentWatcher;
        if (pthread_create(&parentWatcher, NULL, watchParent, NULL) != 0) {
            puts("ERROR Unable to monitor the parent app");
            if (mouseMonitor != nil) [NSEvent removeMonitor:mouseMonitor];
            if (reference != NULL) UnregisterEventHotKey(reference);
            if (handler != NULL) RemoveEventHandler(handler);
            return 1;
        }
        pthread_detach(parentWatcher);
        [app run];
        if (mouseMonitor != nil) [NSEvent removeMonitor:mouseMonitor];
        if (reference != NULL) UnregisterEventHotKey(reference);
        if (handler != NULL) RemoveEventHandler(handler);
        return 0;
    }
}
