import Carbon
import Foundation

/// Registers Control + Option + V with the system without monitoring all keystrokes.
final class GlobalHotkey {
    private var hotkey: EventHotKeyRef?
    private var handler: EventHandlerRef?
    var onPress: (() -> Void)?

    func start() -> OSStatus {
        guard handler == nil, hotkey == nil else { return noErr }
        var eventType = EventTypeSpec(eventClass: OSType(kEventClassKeyboard),
                                      eventKind: UInt32(kEventHotKeyPressed))
        let context = Unmanaged.passUnretained(self).toOpaque()
        let installed = InstallEventHandler(GetApplicationEventTarget(),
            { _, _, context in
                guard let context else { return OSStatus(eventNotHandledErr) }
                let listener = Unmanaged<GlobalHotkey>.fromOpaque(context).takeUnretainedValue()
                DispatchQueue.main.async { [weak listener] in
                    guard let listener, listener.hotkey != nil else { return }
                    listener.onPress?()
                }
                return noErr
            }, 1, &eventType, context, &handler)
        guard installed == noErr else { return installed }
        let identifier = EventHotKeyID(signature: 0x6D436F70, id: 1) // 'mCop'
        let registered = RegisterEventHotKey(UInt32(kVK_ANSI_V),
            UInt32(controlKey | optionKey), identifier,
            GetApplicationEventTarget(), 0, &hotkey)
        if registered != noErr {
            if let handler { RemoveEventHandler(handler) }
            handler = nil
        }
        return registered
    }

    func stop() {
        if let hotkey { UnregisterEventHotKey(hotkey) }
        if let handler { RemoveEventHandler(handler) }
        hotkey = nil
        handler = nil
        onPress = nil
    }

    deinit { stop() }
}
