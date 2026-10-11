import Foundation

enum CheckError: Error { case failed(String) }
enum TestSupport {
    static var failures = 0
    static func check(_ condition: Bool, _ message: String) throws {
        if !condition { throw CheckError.failed(message) }
    }
    static func run(_ name: String, _ action: () throws -> Void) {
        let start = Date()
        do { try action(); print("TEST\tPASS\t\(name)\t\(Date().timeIntervalSince(start))") }
        catch { failures += 1; print("TEST\tFAIL\t\(name)\t\(Date().timeIntervalSince(start))\n\(error)") }
        fflush(stdout) // Preserve named failures even if a later test crashes.
    }
    static func rejects(_ action: () throws -> Void) throws {
        do { try action() } catch { return }
        throw CheckError.failed("Expected failure")
    }
    static func exact(_ a: [String], _ b: [String]) -> Bool { a.map { Array($0.utf16) } == b.map { Array($0.utf16) } }
    static func finish() throws { if failures > 0 { throw CheckError.failed("\(failures) test cases failed") } }
}
