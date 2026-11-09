// Renders the iOS app icon from the Android launcher icon's vectors, so the two can't drift apart.
//
//     swift iosApp/make_app_icon.swift
//
// Run from the repository root after changing either vector; it rewrites the icon in the asset
// catalog. Draws the central 72dp of the 108dp adaptive-icon canvas, the part an Android launcher
// shows, so the mark sits in the square exactly as it does on Android and in docs/logo.png. Full
// bleed and opaque, as iOS wants: the system rounds the corners itself.

import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// The vectors the Android launcher draws, background first.
let layers = [
    "app/src/main/res/drawable/ic_launcher_background.xml",
    "app/src/main/res/drawable/ic_launcher_foreground.xml",
]

let output = "iosApp/MigraineWatch/Assets.xcassets/AppIcon.appiconset/AppIcon.png"

/// The one size iOS asks for; it scales it down for every other place the icon appears.
let iconPixels = 1024

/// The adaptive icon's canvas, and the window of it a launcher shows.
let canvasDp: CGFloat = 108
let visibleDp: CGFloat = 72

/// One `<path>` of an Android vector drawable.
struct VectorPath {
    var data = ""
    var fill: CGColor?
    var stroke: CGColor?
    var strokeWidth: CGFloat = 0
    var roundCaps = false
    var roundJoins = false
}

/// Reads the `<path>` elements of a vector drawable, in drawing order.
final class VectorReader: NSObject, XMLParserDelegate {
    private(set) var paths: [VectorPath] = []

    func parser(
        _ parser: XMLParser,
        didStartElement element: String,
        namespaceURI: String?,
        qualifiedName: String?,
        attributes: [String: String]
    ) {
        guard element == "path" else { return }

        var path = VectorPath()
        path.data = attributes["android:pathData"] ?? ""
        path.fill = attributes["android:fillColor"].map(color)
        path.stroke = attributes["android:strokeColor"].map(color)
        path.strokeWidth = attributes["android:strokeWidth"].flatMap(Double.init).map { CGFloat($0) } ?? 0
        path.roundCaps = attributes["android:strokeLineCap"] == "round"
        path.roundJoins = attributes["android:strokeLineJoin"] == "round"
        paths.append(path)
    }
}

/// An opaque `#RRGGBB` colour, in sRGB as Android reads it.
func color(_ hex: String) -> CGColor {
    let value = UInt32(hex.dropFirst(), radix: 16)!
    let channel = { (shift: UInt32) in CGFloat((value >> shift) & 0xFF) / 255 }
    return CGColor(srgbRed: channel(16), green: channel(8), blue: channel(0), alpha: 1)
}

/// Path data as these vectors write it: absolute moves, lines and cubics, and closes. Anything
/// else stops the script, rather than drawing a shape that isn't the one on Android.
func cgPath(_ data: String) -> CGPath {
    let scanner = Scanner(string: data)
    scanner.charactersToBeSkipped = CharacterSet(charactersIn: ", \n\t")
    let path = CGMutablePath()
    var command: Character = "M"
    var current = CGPoint.zero

    func number() -> CGFloat { CGFloat(scanner.scanDouble()!) }
    func point() -> CGPoint { CGPoint(x: number(), y: number()) }

    while !scanner.isAtEnd {
        if let letter = scanner.scanCharacter(), letter.isLetter {
            command = letter
        } else {
            scanner.currentIndex = data.index(before: scanner.currentIndex)
        }

        switch command {
        case "M":
            current = point()
            path.move(to: current)
            command = "L"
        case "L":
            current = point()
            path.addLine(to: current)
        case "h":
            current.x += number()
            path.addLine(to: current)
        case "v":
            current.y += number()
            path.addLine(to: current)
        case "C":
            let control1 = point(), control2 = point()
            current = point()
            path.addCurve(to: current, control1: control1, control2: control2)
        case "Z", "z":
            path.closeSubpath()
        default:
            fatalError("Path command \(command) isn't supported; add it to make_app_icon.swift")
        }
    }
    return path
}

/// Draws [paths] in Android's coordinates, y downward, with the visible window filling the image.
func render(_ paths: [VectorPath]) -> CGImage {
    let context = CGContext(
        data: nil,
        width: iconPixels,
        height: iconPixels,
        bitsPerComponent: 8,
        bytesPerRow: 0,
        space: CGColorSpace(name: CGColorSpace.sRGB)!,
        // No alpha: App Store icons must be opaque.
        bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
    )!

    let scale = CGFloat(iconPixels) / visibleDp
    let margin = (canvasDp - visibleDp) / 2
    context.translateBy(x: 0, y: CGFloat(iconPixels))
    context.scaleBy(x: scale, y: -scale)
    context.translateBy(x: -margin, y: -margin)

    for path in paths {
        let shape = cgPath(path.data)

        if let fill = path.fill {
            context.addPath(shape)
            context.setFillColor(fill)
            context.fillPath()
        }

        if let stroke = path.stroke {
            context.addPath(shape)
            context.setStrokeColor(stroke)
            context.setLineWidth(path.strokeWidth)
            context.setLineCap(path.roundCaps ? .round : .butt)
            context.setLineJoin(path.roundJoins ? .round : .miter)
            context.strokePath()
        }
    }
    return context.makeImage()!
}

let paths = layers.flatMap { file -> [VectorPath] in
    let reader = VectorReader()
    let parser = XMLParser(contentsOf: URL(fileURLWithPath: file))!
    parser.delegate = reader
    guard parser.parse() else { fatalError("Could not read \(file): \(parser.parserError!)") }
    return reader.paths
}

let destination = CGImageDestinationCreateWithURL(
    URL(fileURLWithPath: output) as CFURL, UTType.png.identifier as CFString, 1, nil
)!
CGImageDestinationAddImage(destination, render(paths), nil)
guard CGImageDestinationFinalize(destination) else { fatalError("Could not write \(output)") }
print("Wrote \(output)")
