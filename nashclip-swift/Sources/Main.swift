import AppKit

@MainActor
@main
struct NashClipMain {
    private static var controller: AppController?

    static func main() {
        let app = NSApplication.shared
        app.setActivationPolicy(.accessory)
        controller = AppController()
        app.delegate = controller
        app.run()
    }
}
