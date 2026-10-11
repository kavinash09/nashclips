// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "nashclip",
    platforms: [.macOS(.v13)],
    products: [.executable(name: "nashclip", targets: ["nashclip"])],
    targets: [.executableTarget(name: "nashclip", path: "Sources")]
)
