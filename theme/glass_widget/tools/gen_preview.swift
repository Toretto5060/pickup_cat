import AppKit

// 生成 mtz 需要的浅色 / 深色预览图。
// 画布与 MAML 设计尺寸一致；配色/字号/行结构与 Android 小部件 express_widget*.xml 对齐。
// 已去掉 cardBg 行卡片背景、5x3 尺寸；不叠加任何自绘背景色，也不画描边/轮廓，
// 背景完全交给系统柔光玻璃（对齐「透明天气」主题）。

let outputDir = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "preview"

struct Spec {
    let key: String
    let width: CGFloat
    let height: CGFloat
    let rows: Int
    let u: CGFloat
    let wide: Bool
}

let specs: [Spec] = [
    Spec(key: "2x1", width: 440, height: 165, rows: 1, u: 1.15, wide: false),
    Spec(key: "2x2", width: 440, height: 440, rows: 2, u: 2.0, wide: false),
    Spec(key: "4x2", width: 948, height: 440, rows: 2, u: 2.0, wide: true),
    Spec(key: "5x2", width: 1032, height: 440, rows: 2, u: 2.0, wide: true),
]

let sample = [
    (company: "申通快递", color: 0xFF1C64B2, code: "6柜 8-3-7231", time: "09:41", addr: "菜鸟驿站 · 幸福里 3 号楼"),
    (company: "丰巢", color: 0xFF2BB6A3, code: "3柜 5-2-9018", time: "08:12", addr: "丰巢快递柜 · 东门"),
    (company: "中通快递", color: 0xFF1B1B1B, code: "2柜 3-6-4472", time: "昨天 19:03", addr: "妈妈驿站 · 6 栋 1 单元"),
    (company: "菜鸟", color: 0xFFFE7300, code: "9柜 1-9-3306", time: "昨天 17:20", addr: "菜鸟驿站 · 南门店"),
]

func nsColor(_ argb: UInt32) -> NSColor {
    NSColor(calibratedRed: CGFloat((argb >> 16) & 0xFF) / 255,
            green: CGFloat((argb >> 8) & 0xFF) / 255,
            blue: CGFloat(argb & 0xFF) / 255,
            alpha: CGFloat((argb >> 24) & 0xFF) / 255)
}

func font(_ size: CGFloat, bold: Bool = false) -> NSFont {
    NSFont(name: bold ? "PingFangSC-Semibold" : "PingFangSC-Regular", size: size)
        ?? NSFont.systemFont(ofSize: size, weight: bold ? .semibold : .regular)
}

func textWidth(_ s: String, _ size: CGFloat, _ bold: Bool) -> CGFloat {
    (s as NSString).size(withAttributes: [.font: font(size, bold: bold)]).width
}

func drawText(_ s: String, x: CGFloat, y: CGFloat, size: CGFloat, color: NSColor, bold: Bool = false) {
    (s as NSString).draw(at: NSPoint(x: x, y: y),
                         withAttributes: [.font: font(size, bold: bold), .foregroundColor: color])
}

func drawTextRight(_ s: String, right: CGFloat, y: CGFloat, size: CGFloat, color: NSColor, bold: Bool = false) {
    drawText(s, x: right - textWidth(s, size, bold), y: y, size: size, color: color, bold: bold)
}

func makeImage(_ spec: Spec, dark: Bool) -> NSImage {
    let w = spec.width, h = spec.height, u = spec.u
    let padX = 18 * u, padTop = 24 * u, titleH = 40 * u
    let rowH = 58 * u, gap = 12 * u, rowPad = 14 * u
    let btnW = 84 * u, btnH = 42 * u
    let cardW = w - 2 * padX

    let ink = dark ? nsColor(0xE6FFFFFF) : nsColor(0xE6000000)
    let codeInk = dark ? nsColor(0xF2FFFFFF) : nsColor(0xF2000000)
    let sub = dark ? nsColor(0x99FFFFFF) : nsColor(0x99000000)
    let red = nsColor(0xFFFF3B30)
    let pick = dark ? nsColor(0xFF4A8CFF) : nsColor(0xFF3482FF)

    return NSImage(size: NSSize(width: w, height: h), flipped: true) { _ in
        let ctx = NSGraphicsContext.current!.cgContext
        let top = dark ? nsColor(0xFF141B38).cgColor : nsColor(0xFFB8D5FA).cgColor
        let bottom = dark ? nsColor(0xFF4D2457).cgColor : nsColor(0xFFFAC9D6).cgColor
        let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                                  colors: [top, bottom] as CFArray, locations: [0, 1])!
        ctx.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: w, y: h), options: [])
        for (cx, cy, r, a) in [(w * 0.18, h * 0.70, w * 0.32, 0.30),
                               (w * 0.82, h * 0.22, w * 0.28, 0.24),
                               (w * 0.55, h * 0.90, w * 0.22, 0.18)]
            as [(CGFloat, CGFloat, CGFloat, CGFloat)] {
            NSColor(calibratedWhite: dark ? 0.55 : 1, alpha: a).setFill()
            ctx.fillEllipse(in: CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2))
        }

        // 标题：红色 26sp 计数 + 18sp「件待取」，都相对标题区垂直居中
        let countText = "3"
        let countSize = 26 * u
        drawText(countText, x: padX, y: padTop + (titleH - countSize) / 2,
                 size: countSize, color: red, bold: true)
        drawText("件待取", x: padX + textWidth(countText, countSize, true) + 6 * u,
                 y: padTop + (titleH - 18 * u) / 2, size: 18 * u, color: ink, bold: true)

        for i in 0..<spec.rows {
            let item = sample[i % sample.count]
            let y = padTop + titleH + CGFloat(i) * (rowH + gap)

            let companyColor = nsColor(UInt32(item.color))
            if spec.wide {
                drawText(item.company, x: padX + rowPad, y: y + 8 * u,
                         size: 16 * u, color: companyColor, bold: true)
                drawText(item.code, x: padX + rowPad + textWidth(item.company, 16 * u, true) + 12 * u,
                         y: y + 4 * u, size: 26 * u, color: codeInk, bold: true)
                drawText(item.time, x: padX + rowPad, y: y + rowH - 27 * u,
                         size: 17 * u, color: sub)
                drawText(item.addr, x: padX + rowPad + textWidth(item.time, 17 * u, false) + 8 * u,
                         y: y + rowH - 27 * u, size: 17 * u, color: sub)
            } else {
                drawText(item.code, x: padX + rowPad, y: y + 4 * u,
                         size: 26 * u, color: codeInk, bold: true)
                drawText(item.company, x: padX + rowPad, y: y + rowH - 27 * u,
                         size: 16 * u, color: companyColor, bold: true)
                drawText(item.time, x: padX + rowPad + 104 * u, y: y + rowH - 27 * u,
                         size: 17 * u, color: sub)
                drawText(item.addr, x: padX + rowPad + 184 * u, y: y + rowH - 27 * u,
                         size: 17 * u, color: sub)
            }

            // 「我已取件」按钮：文字在按钮内水平垂直居中
            let btnX = padX + cardW - rowPad - btnW
            let btnY = y + (rowH - btnH) / 2
            let btn = NSBezierPath(roundedRect: NSRect(x: btnX, y: btnY, width: btnW, height: btnH),
                                   xRadius: 12 * u, yRadius: 12 * u)
            pick.setFill(); btn.fill()
            let label = "我已取件"
            let labelSize = 14 * u
            drawText(label, x: btnX + (btnW - textWidth(label, labelSize, true)) / 2,
                     y: btnY + (btnH - labelSize) / 2 - labelSize * 0.12,
                     size: labelSize, color: .white, bold: true)
        }
        return true
    }
}

for spec in specs {
    for dark in [false, true] {
        let name = dark ? "widget_\(spec.key)_dark.png" : "widget_\(spec.key).png"
        let image = makeImage(spec, dark: dark)
        guard let tiff = image.tiffRepresentation,
              let rep = NSBitmapImageRep(data: tiff),
              let png = rep.representation(using: .png, properties: [:]) else {
            fatalError("failed to render \(name)")
        }
        try png.write(to: URL(fileURLWithPath: outputDir).appendingPathComponent(name))
        print("wrote \(name) \(Int(spec.width))x\(Int(spec.height)) \(png.count) bytes")
    }
}
