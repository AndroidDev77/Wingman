// swift-tools-version:6.0
// WING-030: Wingman Companion for macOS (iMessage).
//
// * WingmanCompanionCore: pure logic (Foundation + the system SQLite). Builds and tests on
//   Linux and macOS. Everything that decides what is read, what is uploaded, and whether a
//   send happens lives here.
// * WingmanCompanionApp: the SwiftUI menu-bar app and the macOS adapters (Keychain,
//   NSAppleScript, ASWebAuthenticationSession, SMAppService). macOS only.
import PackageDescription

var targets: [Target] = [
    .systemLibrary(
        name: "CSQLite",
        path: "Sources/CSQLite",
        pkgConfig: "sqlite3",
        providers: [.apt(["libsqlite3-dev"])]
    ),
    .target(
        name: "WingmanCompanionCore",
        dependencies: ["CSQLite"],
        path: "Sources/WingmanCompanionCore"
    ),
    .testTarget(
        name: "WingmanCompanionCoreTests",
        dependencies: ["WingmanCompanionCore", "CSQLite"],
        path: "Tests/WingmanCompanionCoreTests"
    ),
]
var products: [Product] = [
    .library(name: "WingmanCompanionCore", targets: ["WingmanCompanionCore"]),
]

#if os(macOS)
targets.append(
    .executableTarget(
        name: "WingmanCompanionApp",
        dependencies: ["WingmanCompanionCore"],
        path: "Sources/WingmanCompanionApp"
    )
)
products.append(.executable(name: "WingmanCompanion", targets: ["WingmanCompanionApp"]))
#endif

let package = Package(
    name: "WingmanCompanion",
    platforms: [.macOS(.v14)],
    products: products,
    targets: targets
)
