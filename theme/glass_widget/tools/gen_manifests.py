#!/usr/bin/env python3
"""生成取件喵柔光玻璃主题的 MAML manifest.xml（4 个尺寸）。

样式严格对齐 app/src/main/res/layout/express_widget.xml / express_widget_row.xml：
  - 标题：红色 26sp 数字 + 18sp「件待取」，粗体
  - 取件信息行：不再画 cardBg 卡片背景，只保留文字 + 蓝色「我已取件」按钮
  - 第一行：16sp 快递公司（跟随玻璃明暗反色，不用品牌色）+ 26sp 粗体取件码 + 蓝色「我已取件」按钮
  - 第二行：17sp 次要色时间 + 17sp 次要色地址
  - 每个取件码单独一行，不把同地址同快递的多个码合并（用户 11-10 明确要求）
  - 空态：0 件时只显示 15sp「还没有待取件」，同样不画卡片背景
  - **只使用系统柔光玻璃材质**（MaterialToken 的 Blur / Glass / ColorBlend / BloomStroke，
    与官方天气主题一致），不叠加任何自绘底色、圆角底板或描边

MAML 属性坑（反编译 com.miui.maml + 官方 maml_weather_2x2.mtz + 真机截图确认）：
  - <Text> 的文本框尺寸是 width / height，**不是** w / h；<Rectangle> / <Button> 才用 w / h。
  - **`align="center"` / `alignV="center"` 是把 x / y 当作文字中心点**，不是在 width/height
    框内居中！所以按钮文字要给「按钮中心坐标」且不带 width/height；给它按钮左上角坐标
    会让文字跑到按钮左上外面（真机截图：白字飘在蓝块上方偏左）。标题同理，y 必须给
    #padTop+#titleH/2，否则文字上半被圆角裁掉。
  - Rectangle 的 cornerRadius 只吃数字字面量（Float.parseFloat），表达式必须写
    cornerRadiusExp；官方样例两个都写。写成 cornerRadius="12*#u" 会解析失败 → 方角按钮。
  - 表达式里的 < / > 在 XML 属性中用 { / } 代替。

刷新链路（唯一通道）：App 写入 → MamlWidgetNotifier 把完整快照塞进 MAML_WIDGET_CHANGED
  广播 extras → <BroadcastBinder><Variable extra="..."/></BroadcastBinder> 直接写进 MAML 变量。
  主题 init / resume 用 <IntentCommand broadcast="true"> 反向请求 App 重推。
  **刻意不保留 ContentProviderBinder**：真机 rust_maml_sdk 在每次 Resume 都会重查它，
  日志出现「[ContentQuery] 结果已过期: maml_id=18, seq 48->49, source=Resume」以及
  provider 连接 1s 延迟卸载；查询与广播并发产生竞态，变量出现短暂空窗 ——
  用户实测「反复进出 App 时小组件有概率消失一下再显示」。移除后竞态消失。

变量口径：#pc_count = 取件点组数（标题「N 件待取」）；#pc_rows = 取件码条数（行可见性）。
  两者分开，才能做到「1 件待取」下面逐条列出该取件点的 2 个取件码。

文字明暗：#uiDark = 跟随系统时的 #__darkmode，或手动选择的 glass_type[0]（深色柔光）。
  不能只认 #__darkmode —— 用户关掉「跟随系统」手动选深色柔光时，系统仍是浅色模式，
  会出现深色玻璃上黑字。「文字自动跟随深浅色」开关（#autoColor）关掉后用 @textColor。
"""
from pathlib import Path
from string import Template
import re

ROOT = Path(__file__).resolve().parents[1]
MAX_ROWS = 5

# designW/designH 为官方预览画布像素；u_factor 把 Android 的 dp/sp 换算到该画布。
# 5x3 已按用户要求移除。
SIZES = {
    "2x1": dict(w=440, h=165, rows=1, u=1.15, wide=False),
    "2x2": dict(w=440, h=440, rows=2, u=2.0, wide=False),
    "4x2": dict(w=948, h=440, rows=2, u=2.0, wide=True),
    "5x2": dict(w=1032, h=440, rows=2, u=2.0, wide=True),   # 实验尺寸
}

MATERIAL_VARS = (ROOT / "src/material_vars.xml").read_text(encoding="utf-8")
MATERIAL_VARS = "\n".join(
    "  " + m.group(0)
    for m in re.finditer(r"<Var name=\"(?:common|bionic_dark|bionic_light)_glass_params\".*?/>", MATERIAL_VARS)
)

GLASS_PARAMS = ",".join(
    f"ifelse(#isBionic,ifelse(#bionicUseLight,#bionic_light_glass_params[{i}],"
    f"#bionic_dark_glass_params[{i}]),#common_glass_params[{i}])"
    for i in range(42)
)

# BroadcastBinder 的 Variable 用 extra 指定 Intent key（反编译 com.miui.maml 确认）：
# type=string → getStringExtra，type=number → getDoubleExtra。count/rows 由 App 以 Double 放入 extras。
# 不声明 pc_updated_at：主题没用到。
BROADCAST_VARS = "\n".join(
    [f'      <Variable name="pc_status" type="string" extra="status"/>',
     f'      <Variable name="pc_count" type="number" extra="count"/>',
     f'      <Variable name="pc_rows" type="number" extra="rows"/>',
     f'      <Variable name="pc_empty_text" type="string" extra="empty_text"/>',
     f'      <Variable name="pc_suffix" type="string" extra="suffix"/>']
    + [f'      <Variable name="pc_{k}{i}" type="string" extra="{k}{i}"/>'
       for i in range(1, MAX_ROWS + 1)
       for k in ("company", "company_color", "code", "time", "addr")]
)

BLOOM_STROKE = (
    '<BloomStrokeConfig disableBloomStroke="ifelse((#showBg || #isGlass),1,0)" '
    'bloomStrokeHeight="#view_height" bloomStrokeRadius="#outlineRadius" bloomStrokeWidth="#view_width" '
    'uGeoPxPosX="0.0" uGeoPxPosY="0.0" uShapeEdgePow="3" uColorPow="1.2" uColorAdd="0.0" '
    'uAlpha="1" uShapeEdgePx="24" uDarker="0.0" uDarkerRangeX="0.4" uDarkerRangeY="0.5" '
    'uScaleX="1" uScaleY="1" uRotationTrigX="1" uRotationTrigY="0.0" '
    'uDirectionalLightDirectionX="-0.6" uDirectionalLightDirectionY="0.7" '
    'uDirectionalLightDirectionZ="-0.05" uDirectionalLightIntensity="1.8" '
    'uDirectionalLightOppositeIntensity="1.2" uDirectionalLightAngleRange="0.7" '
    'uDirectionalLightMinLight="-0.5" uDirectionalLightLightWidth="8"/>'
)

HEADER = Template("""<?xml version="1.0" encoding="UTF-8"?>
<!-- 取件喵 · HyperOS 4 柔光玻璃小部件 (${design_key})；样式对齐标准 Android AppWidget -->
<Widget frameRate="0" scaleByDensity="false" screenWidth="1080" version="2"
        supportBackgroundBlur="false" useVariableUpdater="DateTime.Minute,HyperMaterial"
        disableCutRoundCorner="false" enableLayerAnimation="1">
  <Var name="isInPicker" type="number" expression="eqs(@isPreviewMode,'true')"/>
  <Var name="outlineRadius" type="number" expression="ifelse(#isInPicker,0,ifelse(#widget_radius,(#widget_radius / #__scale_factor),72))"/>
  <Var name="designW" type="number" expression="${design_w}"/>
  <Var name="designH" type="number" expression="${design_h}"/>
  <Var name="scaleX" type="number" expression="(#view_width / #designW)"/>
  <Var name="scaleY" type="number" expression="(#view_height / #designH)"/>
  <Var name="scale" type="number" expression="ifelse((#scaleX { #scaleY),#scaleX,#scaleY)"/>
  <Var name="u" type="number" expression="${u_factor}*#scale"/>
  <Var name="padX" type="number" expression="18*#u"/>
  <Var name="padTop" type="number" expression="24*#u"/>
  <Var name="padBottom" type="number" expression="12*#u"/>
  <Var name="titleH" type="number" expression="40*#u"/>
  <Var name="rowH" type="number" expression="58*#u"/>
  <Var name="gap" type="number" expression="12*#u"/>
  <Var name="rowPad" type="number" expression="14*#u"/>
  <Var name="btnW" type="number" expression="84*#u"/>
  <Var name="btnH" type="number" expression="42*#u"/>
  <Var name="cardW" type="number" expression="#view_width-2*#padX"/>
  <Var name="showBg" type="number" expression="!#enable_hyper_material||#isInPicker||(#hyper_material_view_type==2)"/>
  <Var name="isGlass" type="number" expression="#hyper_material_view_type==1"/>
  <Var name="isBlend" type="number" expression="#hyper_material_view_type==0"/>
  <!-- 文字/按钮的明暗基准必须跟随「实际生效的柔光玻璃」而不是只看系统模式：
       关掉「跟随系统」手动选深色柔光时系统可能仍是浅色模式，只看 #__darkmode 会出黑字。 -->
  <Var name="uiDark" type="number" expression="ifelse(#followSystem,#__darkmode,#glass_type[0])"/>
  <Var name="isGlassBionic" type="number" expression="#uiDark"/>
  <Var name="bionicUseLight" type="number" expression="ifelse(#followSystem,(! #__darkmode),#applied_light_wallpaper)"/>
  <Var name="isBionic" type="number" expression="#isGlassBionic ** #hyper_material_glass_enable ** (! #isInPicker) ** (#hyper_material_view_type==1)"/>
  <!-- Android 小部件配色：values/colors.xml 与 values-night/colors.xml，按 #uiDark 反色 -->
  <Var name="titleInk" type="string" expression="ifelse(#autoColor,ifelse(#uiDark,'#E6FFFFFF','#E6000000'),@textColor)"/>
  <Var name="textInk" type="string" expression="ifelse(#autoColor,ifelse(#uiDark,'#F2FFFFFF','#F2000000'),@textColor)"/>
  <Var name="secondaryInk" type="string" expression="ifelse(#autoColor,ifelse(#uiDark,'#99FFFFFF','#99000000'),@textColor)"/>
  <Var name="countRed" type="string" expression="'#FFFF3B30'"/>
  <Var name="pickBg" type="string" expression="ifelse(#uiDark,'#FF4A8CFF','#FF3482FF')"/>
  <Var name="pc_tick" type="number" expression="0"/>
${material_vars}
  <Layer width="#view_width" height="#view_height" frameRate="-1" layerType="bottom"
         x="0" y="0" export="true" name="root">
    <MaterialToken tokenName="pickupcat_glass_material" version="30">
      <BackgroundBlurConfig disableBlur="ifelse(#showBg,1,0)" blurBgMode="1" blurContentMode="1"
                            blurRadius="120" blurType="0" passWindow="true" rustPassWindow="true" reuseContainer="true"/>
      <GlassConfig params="${glass_params}" disableGlass="ifelse((#showBg || #isBlend),1,0)"
                   smallBlurRadius="36" bigBlurRadius="500"/>
      <ColorBlendConfig colors="ifelse(#uiDark,'#52000000','#B3FFFFFF'),ifelse(#uiDark,'#75000000','#33F9F9F9')"
                        colorModes="ifelse(#uiDark,3,20),ifelse(#uiDark,19,15)"
                        disableBlend="ifelse((#showBg || #isGlass),1,0)"/>
      ${bloom_stroke}
    </MaterialToken>
    <ViewOutlineProvider radius="#outlineRadius" type="rect" left="0" top="0" right="#view_width" bottom="#view_height"/>
    <!-- 背景完全交给 MaterialToken 的系统柔光玻璃：不画任何自绘底色 / 圆角底板 / 描边 -->
    <Group w="#view_width" h="#view_height">
      <Function name="openApp">
        <IntentCommand action="android.intent.action.MAIN"
                       package="com.toretto5060.pickupcatapp"
                       class="com.toretto5060.pickupcatapp.app.ExpressListActivity"
                       flags="268484608"/>
      </Function>
${layout}
      <Button x="0" y="0" w="#view_width" h="#view_height" interceptTouch="true">
        <Triggers>
          <Trigger action="up"><FunctionCommand target="openApp"/></Trigger>
        </Triggers>
      </Button>
    </Group>
  </Layer>

  <!-- 唯一数据通道：广播 extras 直接注入变量。不放 ContentProviderBinder ——
       rust_maml_sdk 在 Resume 会重查它并产生「结果已过期」竞态（实测组件闪一下）。 -->
  <VariableBinders>
    <BroadcastBinder action="com.toretto5060.pickupcatapp.MAML_WIDGET_CHANGED">
${broadcast_vars}
      <Trigger>
        <VariableCommand name="pc_tick" expression="#pc_tick+1"/>
      </Trigger>
    </BroadcastBinder>
  </VariableBinders>

  <!-- 兜底：小部件 init / resume 时主动向 App 要一次最新快照。
       IntentCommand 用 broadcast="true"（不是 type=broadcast），package 让广播显式投递到 App，
       即使 App 进程已被回收，静态注册的 MamlWidgetRequestReceiver 也会被拉起。 -->
  <ExternalCommands>
    <Trigger action="init">
      <IntentCommand action="com.toretto5060.pickupcatapp.MAML_WIDGET_REQUEST"
                     package="com.toretto5060.pickupcatapp" broadcast="true"/>
    </Trigger>
    <Trigger action="resume">
      <IntentCommand action="com.toretto5060.pickupcatapp.MAML_WIDGET_REQUEST"
                     package="com.toretto5060.pickupcatapp" broadcast="true"/>
    </Trigger>
  </ExternalCommands>
</Widget>
""")


def header() -> str:
    """标题栏：alignV=center 把 y 当文字中心，所以给标题区中线，别给顶部。"""
    return """      <Text name="txt_count" x="#padX" y="#padTop+#titleH/2" width="120*#u" alignV="center" size="26*#u"
            color="@countRed" fontFamily="mipro-demibold" textExp="formatFloat('%.0f',#pc_count)"/>
      <Text x="#padX+#txt_count.text_width+6*#u" y="#padTop+#titleH/2" width="140*#u" alignV="center" size="18*#u"
            color="@titleInk" fontFamily="mipro-demibold" textExp="@pc_suffix"/>"""


def row_card(index: int, wide: bool) -> str:
    """一条取件码；y 由行号推导，58dp 行高 + 12dp 间隔，无卡片背景。"""
    y = f"#padTop+#titleH+{index - 1}*(#rowH+#gap)"
    # 快递公司名同样跟随明暗反色（用户两次明确要求：深色玻璃白、浅色玻璃黑），
    # 不再引用品牌色 —— 品牌色里中通/顺丰是纯黑、韵达是深蓝 #FF0E4C9A，
    # 浅色玻璃上看起来都像黑字，用户会认为「反色没生效」。
    company_color = "@textInk"
    if wide:
        body = f"""        <Text name="txt_company@N@" x="#padX+#rowPad" y="@Y@+8*#u" width="150*#u" size="16*#u"
              color="{company_color}" fontFamily="mipro-demibold" ellipsis="true"
              textExp="@pc_company@N@"/>
        <Text x="#padX+#rowPad+#txt_company@N@.text_width+12*#u" y="@Y@+4*#u"
              width="#cardW-2*#rowPad-#txt_company@N@.text_width-12*#u-#btnW-12*#u"
              size="26*#u" color="@textInk" fontFamily="mipro-demibold" ellipsis="true"
              textExp="@pc_code@N@"/>
        <Text name="txt_time@N@" x="#padX+#rowPad" y="@Y@+#rowH-27*#u" width="120*#u" size="17*#u"
              color="@secondaryInk" fontFamily="mipro-regular" ellipsis="true"
              textExp="@pc_time@N@"/>
        <Text x="#padX+#rowPad+#txt_time@N@.text_width+8*#u" y="@Y@+#rowH-27*#u"
              width="#cardW-2*#rowPad-#txt_time@N@.text_width-8*#u"
              size="17*#u" color="@secondaryInk" fontFamily="mipro-regular" ellipsis="true"
              textExp="@pc_addr@N@"/>"""
    else:
        body = f"""        <Text x="#padX+#rowPad" y="@Y@+4*#u" width="#cardW-2*#rowPad-#btnW-12*#u"
              size="26*#u" color="@textInk" fontFamily="mipro-demibold" ellipsis="true"
              textExp="@pc_code@N@"/>
        <Text x="#padX+#rowPad" y="@Y@+#rowH-27*#u" width="96*#u" size="16*#u"
              color="{company_color}" fontFamily="mipro-demibold" ellipsis="true"
              textExp="@pc_company@N@"/>
        <Text x="#padX+#rowPad+104*#u" y="@Y@+#rowH-27*#u" width="72*#u" size="17*#u"
              color="@secondaryInk" fontFamily="mipro-regular" ellipsis="true"
              textExp="@pc_time@N@"/>
        <Text x="#padX+#rowPad+184*#u" y="@Y@+#rowH-27*#u" width="#cardW-2*#rowPad-184*#u"
              size="17*#u" color="@secondaryInk" fontFamily="mipro-regular" ellipsis="true"
              textExp="@pc_addr@N@"/>"""
    # 第 i 行仅在「取件码条数 > i-1」时显示。用 #pc_rows 而不是 #pc_count：
    # 一个取件点（count=1）可能有多条取件码，都要逐行列出。
    # 按钮文字的 x/y 必须是按钮中心（align/alignV=center 把 x/y 当文字中心点），
    # 且不能带 width/height，否则白字会飘到蓝块左上外面。
    tpl = """      <Group visibility="ifelse(#pc_rows}@MIN@,1,0)">
@BODY@
        <Rectangle x="#padX+#cardW-#rowPad-#btnW" y="@Y@+(#rowH-#btnH)/2" w="#btnW" h="#btnH"
                   cornerRadius="12" cornerRadiusExp="12*#u" fillColor="@pickBg"/>
        <Text x="#padX+#cardW-#rowPad-#btnW/2" y="@Y@+#rowH/2"
              align="center" alignV="center" size="14*#u" color="#FFFFFFFF"
              fontFamily="mipro-demibold" textExp="'我已取件'"/>
      </Group>"""
    return (tpl.replace("@BODY@", body).replace("@N@", str(index))
            .replace("@MIN@", str(index - 1)).replace("@Y@", y))


def empty_card() -> str:
    """0 件待取：只显示 15sp「还没有待取件」，不画卡片背景；y 同样是文字中心。"""
    return """      <Group visibility="ifelse(#pc_count==0,1,0)">
        <Text x="#padX+#rowPad" y="#padTop+#titleH+#rowH/2" width="#cardW-2*#rowPad"
              alignV="center" size="15*#u" color="@secondaryInk" fontFamily="mipro-regular"
              textExp="ifelse(eqs(@pc_status,'ok'),@pc_empty_text,'等待取件喵数据')"/>
      </Group>"""


def layout(cfg: dict) -> str:
    parts = [header(), empty_card()]
    parts += [row_card(i, cfg["wide"]) for i in range(1, cfg["rows"] + 1)]
    return "\n".join(parts)


def main() -> None:
    for key, cfg in SIZES.items():
        xml = HEADER.substitute(
            design_key=key, design_w=cfg["w"], design_h=cfg["h"],
            u_factor=cfg["u"], material_vars=MATERIAL_VARS, glass_params=GLASS_PARAMS,
            bloom_stroke=BLOOM_STROKE, layout=layout(cfg),
            broadcast_vars=BROADCAST_VARS,
        )
        out = ROOT / "src" / f"manifest_{key}.xml"
        out.write_text(xml, encoding="utf-8")
        print("wrote", out.name, len(xml), "bytes, rows =", cfg["rows"])


if __name__ == "__main__":
    main()
