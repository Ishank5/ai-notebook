// Renders the AI Notebook app icon: the logo mark (three rounded waveform
// bars beside two short note lines) in white on a full-bleed pine teal square.
// iOS rounds the corners itself, so the square is drawn edge to edge, with no
// alpha channel.
//
// The geometry matches LogoGeometry in
// mobile/iosApp/iosApp/ui/components/LogoMark.swift.
//
// Usage:
//   swift design/render-app-icon.swift <output.png> [size]
// e.g.
//   swift design/render-app-icon.swift mobile/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png

import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let args = CommandLine.arguments
guard args.count >= 2 else {
    FileHandle.standardError.write("usage: swift render-app-icon.swift <output.png> [size]\n".data(using: .utf8)!)
    exit(1)
}
let output = URL(fileURLWithPath: args[1])
let size = args.count >= 3 ? Int(args[2]) ?? 1024 : 1024
let s = CGFloat(size)

// Pine teal #0D7A68.
let primary = CGColor(srgbRed: 0x0D / 255, green: 0x7A / 255, blue: 0x68 / 255, alpha: 1)
let white = CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1)

guard let context = CGContext(
    data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
    space: CGColorSpace(name: CGColorSpace.sRGB)!,
    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
) else { fatalError("no context") }

// CoreGraphics counts y from the bottom; flip so the numbers read top-down like SwiftUI.
context.translateBy(x: 0, y: s)
context.scaleBy(x: 1, y: -1)

context.setFillColor(primary)
context.fill(CGRect(x: 0, y: 0, width: s, height: s))

// The mark, scaled a touch smaller than in the logo so it sits well inside the icon mask.
let scale: CGFloat = 0.92
let inset = s * (1 - scale) / 2
let m = s * scale
let barWidth: CGFloat = 0.08
let bars: [(x: CGFloat, height: CGFloat)] = [(0.20, 0.26), (0.32, 0.44), (0.44, 0.32)]
let lines: [(from: CGFloat, to: CGFloat, y: CGFloat)] = [(0.60, 0.80, 0.43), (0.60, 0.73, 0.57)]

func roundedRect(_ rect: CGRect) -> CGPath {
    let r = min(rect.width, rect.height) / 2
    return CGPath(roundedRect: rect, cornerWidth: r, cornerHeight: r, transform: nil)
}

context.setFillColor(white)
let w = barWidth * m
for bar in bars {
    let h = bar.height * m
    context.addPath(roundedRect(CGRect(x: inset + bar.x * m, y: inset + m / 2 - h / 2, width: w, height: h)))
}
for line in lines {
    context.addPath(roundedRect(CGRect(
        x: inset + line.from * m, y: inset + line.y * m - w / 2, width: (line.to - line.from) * m, height: w
    )))
}
context.fillPath()

guard let image = context.makeImage(),
      let destination = CGImageDestinationCreateWithURL(output as CFURL, UTType.png.identifier as CFString, 1, nil)
else { fatalError("could not write \(output.path)") }
CGImageDestinationAddImage(destination, image, nil)
guard CGImageDestinationFinalize(destination) else { fatalError("could not write \(output.path)") }
print("wrote \(output.path) (\(size)x\(size))")
