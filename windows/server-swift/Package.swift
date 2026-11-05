// swift-tools-version: 6.1
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription
import Foundation

let swiftSettings: [SwiftSetting] = [
    .interoperabilityMode(.Cxx)
]
let llamaRuntime = ProcessInfo.processInfo.environment["AZOOKEY_LLAMA_BACKEND_DIR"] ?? "../../artifacts/runtime/windows-b12125/llama_cpu"
let llamaLinkFlags = ["llama", "ggml", "ggml-base"].flatMap { ["-Xlinker", "\(llamaRuntime)/\($0).lib"] }

let package = Package(
    name: "azookey-server",
    products: [
        // Products define the executables and libraries a package produces, making them visible to other packages.
        .library(
            name: "azookey-server",
            type: .dynamic,
            targets: ["azookey-server"]
        ),
        .library(name: "ffi", targets: ["azookey-server"])
    ],
    dependencies: [
        // Dependencies declare other packages that this package depends on.
        // .package(url: /* package url */, from: "1.0.0"),
        .package(
            name: "AzooKeyKanaKanjiConverter",
            path: ProcessInfo.processInfo.environment["ZEROCON_CONVERTER_SOURCE"] ?? "../../artifacts/dependencies/azookey-b12125",
            traits: ["Zenzai"]
        )
    ],
    targets: [
        // Targets are the basic building blocks of a package, defining a module or a test suite.
        // Targets can depend on other targets in this package and products from dependencies.
        .target(name: "ffi"),
        .target(
            name: "azookey-server",
            dependencies: [
                .product(name: "KanaKanjiConverterModule", package: "azookeykanakanjiconverter"),
                "ffi"
            ],
            swiftSettings: swiftSettings,
            linkerSettings: [.unsafeFlags(llamaLinkFlags)]
        ),
        .testTarget(
            name: "azookey-serverTests",
            dependencies: ["azookey-server"],
            swiftSettings: swiftSettings
        ),
    ]
)
