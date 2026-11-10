import AppKit

// 生成 mtz 需要的浅色 / 深色预览图（官方尺寸：2x1=440x165、2x2=440x440、4x2=948x440）。
// 用模拟壁纸 + 半透明圆角面板表达柔光玻璃观感；真机玻璃由 MAML MaterialToken 渲染。

let outputDir = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "preview"
let sizes: [(String, CGFloat, CGFloat)] = [
    ("2x1", 440, 165),
    ("2x2", 440, 440),
    ("4x2", 948, 440),
]

let inkLight = NSColor(calibratedRed: 0.10, green: 0.10, blue: 0.12, alpha: 1)
let subLight = NSColor(calibratedRed: 0.24, green: 0.26, blue: 0.30, alpha: 0.85)
let codeLight = NSColor(calibratedRed: 0.04, green: 0.39, blue: 0.84, alpha: 1)
let inkDark = NSColor.white
let subDark = NSColor(calibratedWhite: 1, alpha: 0.70)
let codeDark = NSColor.white

func font(_ size: CGFloat, medium: Bool = false) -> NSFont {
    let name = medium ? "PingFangSC-Medium" : "PingFangSC-Regular"
    return NSFont(name: name, size: size)
        ?? NSFont.systemFont(ofSize: size, weight: medium ? .medium : .regular)
}

func drawText(_ s: String, x: CGFloat, y: CGFloat, size: CGFloat, color: NSColor, medium: Bool = false) {
    (s as NSString).draw(at: NSPoint(x: x, y: y),
                         withAttributes: [.font: font(size, medium: medium), .foregroundColor: color])
}

func drawTextRight(_ s: String, right: CGFloat, y: CGFloat, size: CGFloat, color: NSColor, medium: Bool = false) {
    let f = font(size, medium: medium)
    let w = (s as NSString).size(withAttributes: [.font: f]).width
    drawText(s, x: right - w, y: y, size: size, color: color, medium: medium)
}

func drawRow(x: CGFloat, y: CGFloat, w: CGFloat, h: CGFloat, index: Int, dark: Bool, wide: Bool) {
    let fill = dark ? NSColor(calibratedWhite: 1, alpha: 0.10) : NSColor.white.withAlphaComponent(0.50)
    let path = NSBezierPath(roundedRect: NSRect(x: x, y: y, width: w, height: h), xRadius: 18, yRadius: 18)
    fill.setFill(); path.fill()
    let codes = ["8-3-7231", "5-2-9018", "3-6-4472", "1-9-3306"]
    let places = ["菜鸟驿站 · 6号柜", "丰巢快递柜 · 3号柜", "妈妈驿站 · 2号柜"]
    let companies = ["申通快递", "圆通速递", "中通快递"]
    let times = ["09:41", "08:12", "昨天 19:03"]
    let ink = dark ? inkDark : subLight
    drawText(codes[index], x: x + 16, y: y + 12, size: wide ? 34 : 32,
             color: dark ? codeDark : codeLight, medium: true)
    drawTextRight(times[index], right: x + w - 16, y: y + 16, size: 15, color: ink)
    drawText(places[index], x: x + 16, y: y + h - 34, size: 15, color: ink)
    drawTextRight(companies[index], right: x + w - 16, y: y + h - 34, size: 14, color: ink)
}

func makeImage(key: String, width: CGFloat, height: CGFloat, dark: Bool) -> NSImage {
    let size = NSSize(width: width, height: height)
    return NSImage(size: size, flipped: true) { _ in
        let ctx = NSGraphicsContext.current!.cgContext
        let top = dark ? NSColor(calibratedRed: 0.08, green: 0.10, blue: 0.22, alpha: 1).cgColor
                       : NSColor(calibratedRed: 0.72, green: 0.84, blue: 0.98, alpha: 1).cgColor
        let bottom = dark ? NSColor(calibratedRed: 0.30, green: 0.14, blue: 0.34, alpha: 1).cgColor
                          : NSColor(calibratedRed: 0.98, green: 0.79, blue: 0.84, alpha: 1).cgColor
        let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                                  colors: [top, bottom] as CFArray, locations: [0, 1])!
        ctx.drawLinearGradient(gradient, start: .zero,
                               end: CGPoint(x: width, y: height), options: [])
        for (cx, cy, r, c) in [(width * 0.18, height * 0.70, width * 0.32, 0.30),
                               (width * 0.82, height * 0.22, width * 0.28, 0.24),
                               (width * 0.55, height * 0.90, width * 0.22, 0.18)] as [(CGFloat, CGFloat, CGFloat, CGFloat)] {
            NSColor(calibratedWhite: dark ? 0.55 : 1, alpha: c).setFill()
            ctx.fillEllipse(in: CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2))
        }

        let panel = NSBezierPath(roundedRect: NSRect(x: 10, y: 10, width: width - 20, height: height - 20),
                                 xRadius: 34, yRadius: 34)
        (dark ? NSColor(calibratedWhite: 1, alpha: 0.12) : NSColor.white.withAlphaComponent(0.58)).setFill()
        panel.fill()
        NSColor(calibratedWhite: 1, alpha: dark ? 0.34 : 0.85).setStroke()
        panel.lineWidth = 2; panel.stroke()

        let ink = dark ? inkDark : inkLight
        let sub = dark ? subDark : subLight
        drawText("取件喵", x: 28, y: 22, size: key == "4x2" ? 22 : 20, color: ink, medium: true)

        switch key {
        case "2x1":
            drawText("3 件待取", x: 28, y: 58, size: 34, color: ink, medium: true)
            drawTextRight("8-3-7231", right: width - 28, y: 26, size: 28,
                          color: dark ? codeDark : codeLight, medium: true)
            drawTextRight("菜鸟驿站 · 6号柜", right: width - 28, y: 74, size: 14, color: sub)
            drawText("09:41 更新", x: 28, y: height - 38, size: 14, color: sub)
        case "2x2":
            drawText("3 件待取", x: 28, y: 60, size: 44, color: ink, medium: true)
            drawRow(x: 28, y: 130, w: width - 56, h: 104, index: 0, dark: dark, wide: false)
            drawRow(x: 28, y: 250, w: width - 56, h: 104, index: 1, dark: dark, wide: false)
        default:
            drawText("3", x: 28, y: 58, size: 56, color: ink, medium: true)
            drawText("件待取", x: 28, y: 126, size: 20, color: sub)
            NSColor(calibratedWhite: dark ? 1 : 0, alpha: 0.18).setFill()
            NSBezierPath(rect: NSRect(x: 316, y: 34, width: 1, height: height - 68)).fill()
            for i in 0..<3 {
                drawRow(x: 340, y: 30 + CGFloat(i) * 126, w: width - 368, h: 112,
                        index: i, dark: dark, wide: true)
            }
        }
        return true
    }
}

for (key, w, h) in sizes {
    for dark in [false, true] {
        let name = dark ? "widget_\(key)_dark.png" : "widget_\(key).png"
        let image = makeImage(key: key, width: w, height: h, dark: dark)
        guard let tiff = image.tiffRepresentation,
              let rep = NSBitmapImageRep(data: tiff),
              let png = rep.representation(using: .png, properties: [:]) else {
            fatalError("failed to render \(name)")
        }
        let url = URL(fileURLWithPath: outputDir).appendingPathComponent(name)
        try png.write(to: url)
        print("wrote \(name) \(Int(w))x\(Int(h)) \(png.count) bytes")
    }
}
