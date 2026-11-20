// swift-tools-version: 5.9

// وكيل للآيباد — يُفتح في Swift Playgrounds (iPadOS 17 أو أحدث) أو في Xcode.
import PackageDescription
import AppleProductTypes

let package = Package(
    name: "Wakeel",
    platforms: [
        .iOS("17.0")
    ],
    products: [
        .iOSApplication(
            name: "Wakeel",
            targets: ["AppModule"],
            bundleIdentifier: "com.wakeel.ipad",
            teamIdentifier: "",
            displayVersion: "1.0",
            bundleVersion: "1",
            appIcon: .placeholder(icon: .sparkle),
            accentColor: .presetColor(.teal),
            supportedDeviceFamilies: [
                .pad,
                .phone
            ],
            supportedInterfaceOrientations: [
                .portrait,
                .landscapeRight,
                .landscapeLeft,
                .portraitUpsideDown(.when(deviceFamilies: [.pad]))
            ]
        )
    ],
    targets: [
        .executableTarget(
            name: "AppModule",
            path: "."
        )
    ]
)
