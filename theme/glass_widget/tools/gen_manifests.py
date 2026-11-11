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
# rows = 本尺寸「每页显示行数」；1x1 为角落总数卡（rows=0 不翻页只显示总数）。
SIZES = {
    "1x1": dict(w=216, h=216, rows=0, u=1.0, wide=False),   # 角落总数卡：左上「待取」+ 居中放大数字
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

# ContentProviderBinder 的 Variable 用 column 指定 Provider 列（init 时直接跨 UID 查询拉数据，
# 不依赖 App 主动推送，因此「重新导入即有数据」）。变量在此**唯一声明一次**；
# 广播收到后只用 BinderCommand 触发本 binder 重查，绝不重复声明同名变量（否则 MAML 报重复定义）。
# type=string → 文本列；type=number → 数字列（Provider 以 Double/Long 写入，MAML 读为 number）。
CP_VARS = "\n".join(
    [f'      <Variable name="pc_status" type="string" column="status"/>',
     f'      <Variable name="pc_count" type="number" column="count"/>',
     f'      <Variable name="pc_rows" type="number" column="rows"/>',
     f'      <Variable name="pc_rows_shown" type="number" column="rows_shown"/>',
     f'      <Variable name="pc_empty_text" type="string" column="empty_text"/>',
     f'      <Variable name="pc_suffix" type="string" column="suffix"/>']
    + [f'      <Variable name="pc_id{i}" type="number" column="id{i}"/>'
       for i in range(1, MAX_ROWS + 1)]
    + [f'      <Variable name="pc_{k}{i}" type="string" column="{k}{i}"/>'
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
  <!-- 翻页：page 为可写状态变量（无 expression），由卡片点按/滑动/点圆点的 VariableCommand 改写；
       lastIndex 由生成时按每页行数算出（最大页码,0基）注入，避免运行时用 >/< 比较。 -->
  <Var name="page" type="number"/>
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
      <!-- 翻页层：只捕获上下滑动手势翻页，不挂 up。
           openApp 与「我已取件」按钮都在本层之上的独立图层，
           二者矩形零重叠，点按不会互相连带。 -->
      <Button x="0" y="0" w="#view_width" h="#view_height">
        <Triggers>
          <Trigger action="onFling"><VariableCommand name="page" expression="ifelse(#page==${lastIndex},0,#page+1)"/></Trigger>
        </Triggers>
      </Button>
      ${pick}
      ${openapp}
    </Group>
  </Layer>

  <!-- 数据双通道（变量只在 ContentProviderBinder 声明一次）：
       1) ContentProviderBinder：init 时跨 UID 直查本 App 导出的只读 Provider 拉初始数据，
          因此「重新导入即显示」，不依赖 App 主动推送；并监听 Provider 的 notifyChange 自动重查。
       2) BroadcastBinder：收到 App 推送广播时，用 BinderCommand 触发上面的 CP 重查，
          双保险覆盖新消息/取件后的刷新。不重复声明变量。 -->
  <VariableBinders>
    <ContentProviderBinder name="pc_provider" uri="content://com.toretto5060.pickupcatapp.maml/pending"
        queryAtStart="true" firstRequery="false">
${cp_vars}
    </ContentProviderBinder>
    <BroadcastBinder action="com.toretto5060.pickupcatapp.MAML_WIDGET_CHANGED">
      <Trigger>
        <BinderCommand name="pc_provider"/>
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
    """标题栏：alignV=center 把 y 当文字中心，所以给标题区中线，别给顶部。
    计数数字的左缘必须与下方快递公司名（#padX+#rowPad）对齐，不能直接用 #padX。
    数字 = 待取件**件数**（pc_rows=取件码条数），不是取件点组数（pc_count）。"""
    return """      <Text name="txt_count" x="#padX+#rowPad" y="#padTop+#titleH/2" width="120*#u" alignV="center" size="26*#u"
            color="@countRed" fontFamily="mipro-demibold" textExp="formatFloat('%.0f',#pc_rows)"/>
      <Text x="#padX+#rowPad+#txt_count.text_width+6*#u" y="#padTop+#titleH/2" width="140*#u" alignV="center" size="18*#u"
            color="@titleInk" fontFamily="mipro-demibold" textExp="@pc_suffix"/>"""


def row_card(data_index: int, local_i: int, page_index: int, wide: bool) -> str:
    """一条取件码。
    - data_index：1..MAX_ROWS，对应 pc_company{data_index} 等数据列；
    - local_i   ：页内行号 1..perPage，决定这一行在卡片里的 y（各页内容叠在同一区域，
                  靠 #page 切换可见性，实现「上下滑动/点按卡片体翻页」把多余条目翻出来）；
    - page_index：这一行属于第几页（0 基）。
    卡片体（全屏 Button，位于内容之下）上下滑动或点按翻页；「我已取件」按钮直接发广播
    标记该行已取（与安卓小部件一致，按行 id 精确标记，不跳列表）。"""
    y = f"#padTop+#titleH+{local_i - 1}*(#rowH+#gap)"
    # 快递公司名同样跟随明暗反色（用户明确要求：深色玻璃白、浅色玻璃黑），不引用品牌色。
    company_color = "@textInk"
    if wide:
        # 公司名(16sp)与取件码(26sp)都用 alignV=center + 同一个 y（取件码槽位中心 21u），垂直居中。
        # 时间/地址从 rowH-23：26sp 取件码字形底部约在 Y+30u，留 5u 间隙。
        body = f"""        <Text name="txt_company@N@" x="#padX+#rowPad" y="@Y@+21*#u" alignV="center" width="150*#u" size="16*#u"
              color="{company_color}" fontFamily="mipro-demibold" ellipsis="true"
              textExp="@pc_company@N@"/>
        <Text x="#padX+#rowPad+#txt_company@N@.text_width+12*#u" y="@Y@+21*#u" alignV="center"
              width="#cardW-2*#rowPad-#txt_company@N@.text_width-12*#u-#btnW-12*#u"
              size="26*#u" color="@textInk" fontFamily="mipro-demibold" ellipsis="true"
              textExp="@pc_code@N@"/>
        <Text name="txt_time@N@" x="#padX+#rowPad" y="@Y@+#rowH-23*#u" width="120*#u" size="17*#u"
              color="@secondaryInk" fontFamily="mipro-regular" ellipsis="true"
              textExp="@pc_time@N@"/>
        <Text x="#padX+#rowPad+#txt_time@N@.text_width+8*#u" y="@Y@+#rowH-23*#u"
              width="#cardW-2*#rowPad-#txt_time@N@.text_width-8*#u"
              size="17*#u" color="@secondaryInk" fontFamily="mipro-regular" ellipsis="true"
              textExp="@pc_addr@N@"/>"""
    else:
        body = f"""        <Text x="#padX+#rowPad" y="@Y@+21*#u" alignV="center" width="#cardW-2*#rowPad-#btnW-12*#u"
              size="26*#u" color="@textInk" fontFamily="mipro-demibold" ellipsis="true"
              textExp="@pc_code@N@"/>
        <Text x="#padX+#rowPad" y="@Y@+#rowH-23*#u" width="96*#u" size="16*#u"
              color="{company_color}" fontFamily="mipro-demibold" ellipsis="true"
              textExp="@pc_company@N@"/>
        <Text x="#padX+#rowPad+104*#u" y="@Y@+#rowH-23*#u" width="72*#u" size="17*#u"
              color="@secondaryInk" fontFamily="mipro-regular" ellipsis="true"
              textExp="@pc_time@N@"/>
        <Text x="#padX+#rowPad+184*#u" y="@Y@+#rowH-23*#u" width="#cardW-2*#rowPad-184*#u"
              size="17*#u" color="@secondaryInk" fontFamily="mipro-regular" ellipsis="true"
              textExp="@pc_addr@N@"/>"""
    # 仅当「本行有数据(任一 k∈[data_index..MAX_ROWS] 满足 pc_rows==k) 且 处于当前页」时显示；
    # 各页行按 local_i 叠在同一 y 区域，page 切换即原地翻页。可见性只用 ==/**/||，不依赖 >/<。
    tpl = """      <Group visibility="ifelse(#page==@PAGE@ ** (@HASDATA@),1,0)">
@BODY@
        <Rectangle x="#padX+#cardW-#rowPad-#btnW" y="@Y@+(#rowH-#btnH)/2" w="#btnW" h="#btnH"
                   cornerRadius="12" cornerRadiusExp="12*#u" fillColor="@pickBg"/>
        <Text x="#padX+#cardW-#rowPad-#btnW/2" y="@Y@+#rowH/2"
              align="center" alignV="center" size="14*#u" color="#FFFFFFFF"
              fontFamily="mipro-demibold" textExp="'我已取件'"/>
      </Group>"""
    has_data = " || ".join(f"#pc_rows_shown=={k}" for k in range(data_index, MAX_ROWS + 1))
    return (tpl.replace("@BODY@", body).replace("@N@", str(data_index))
            .replace("@HASDATA@", has_data).replace("@Y@", y).replace("@PAGE@", str(page_index)))


def open_app_buttons(cfg: dict) -> str:
    """卡片上「除我已取件按钮外」的可点区域 → 跳 App 待取件列表。

    必须与取件按钮矩形**零重叠**：MAML 对重叠兄弟元素的点按会派发给所有层，
    若像从前那样用一张全卡 Button 挂 up→openApp，点「我已取件」就会连带启动列表页
    （表现为「进 app 又瞬间闪退」）。因此按几何拆成数个不重叠矩形：
      - 左侧大块（标题 / 公司名 / 取件码 / 时间 / 地址都在这一侧）；
      - 按钮列右缘到卡片右缘的窄条；
      - 按钮列内、每个行槽里「按钮上方」与「按钮下方」的空白（含行间 gap）。
    1x1 迷你卡没有取件按钮，整卡一个矩形即可。
    """
    def rect(x: str, y: str, w: str, h: str) -> str:
        return (f'      <Button x="{x}" y="{y}" w="{w}" h="{h}">\n'
                f'        <Triggers>\n'
                f'          <Trigger action="up"><FunctionCommand target="openApp"/></Trigger>\n'
                f'        </Triggers>\n'
                f'      </Button>')

    per_page = cfg["rows"]
    if per_page <= 0:
        return rect("0", "0", "#view_width", "#view_height")

    left_edge = "#padX+#cardW-#rowPad-#btnW"   # 按钮列左缘
    right_edge = "#padX+#cardW-#rowPad"        # 按钮列右缘
    parts = [
        rect("0", "0", left_edge, "#view_height"),
        rect(right_edge, "0", "#padX+#rowPad", "#view_height"),
    ]
    base = "#padTop+#titleH"
    for local_i in range(1, per_page + 1):
        row_top = f"{base}+{local_i - 1}*(#rowH+#gap)"
        btn_y = f"{row_top}+(#rowH-#btnH)/2"
        parts.append(rect(left_edge, row_top, "#btnW", "(#rowH-#btnH)/2"))
        parts.append(rect(left_edge, f"{btn_y}+#btnH", "#btnW",
                          "(#rowH-#btnH)/2+#gap"))
    return "\n".join(parts)


def pick_button(data_index: int, local_i: int, page_index: int) -> str:
    """「我已取件」按钮：置于翻页层之上（${pick}），点它直接发广播精确标记该行已取（按行 id），不跳列表。
    可见性与对应行一致（本行有数据 且 处于当前页），避免翻到别的页时误点中错位按钮。"""
    by = f"#padTop+#titleH+{local_i - 1}*(#rowH+#gap)+(#rowH-#btnH)/2"
    has_data = " || ".join(f"#pc_rows_shown=={k}" for k in range(data_index, MAX_ROWS + 1))
    return (f'      <Button x="#padX+#cardW-#rowPad-#btnW" y="{by}" w="#btnW" h="#btnH" '
            f'interceptTouch="true" '
            f'visibility="ifelse(#page=={page_index} ** ({has_data}),1,0)">\n'
            f'        <Triggers>\n'
            f'          <Trigger action="up"><IntentCommand\n'
            f'            action="com.toretto5060.pickupcatapp.MAML_PICK"\n'
            f'            package="com.toretto5060.pickupcatapp"\n'
            f'            broadcast="true">\n'
            f'              <Extra name="row_id" type="long" expression="#pc_id{data_index}"/>\n'
            f'              <Extra name="row_index" type="string" expression="\'{data_index}\'"/>\n'
            f'            </IntentCommand></Trigger>\n'
            f'        </Triggers>\n'
            f'      </Button>')


def pick_buttons(cfg: dict) -> str:
    """所有「我已取件」按钮（独立图层，位于翻页层之上）。"""
    per_page = cfg["rows"]
    if per_page <= 0:
        return ""
    parts = []
    for r in range(1, MAX_ROWS + 1):
        local_i = (r - 1) % per_page + 1
        page_index = (r - 1) // per_page
        parts.append(pick_button(r, local_i, page_index))
    return "\n".join(parts)


def empty_card() -> str:
    """0 件待取：只显示 15sp「还没有待取件」，不画卡片背景；y 同样是文字中心。"""
    return """      <Group visibility="ifelse(#pc_count==0,1,0)">
        <Text x="#padX+#rowPad" y="#padTop+#titleH+#rowH/2" width="#cardW-2*#rowPad"
              alignV="center" size="15*#u" color="@secondaryInk" fontFamily="mipro-regular"
              textExp="ifelse(eqs(@pc_status,'ok'),@pc_empty_text,'等待取件喵数据')"/>
      </Group>"""


def page_dots(perPage: int) -> str:
    """翻页点点指示器：右侧竖向、垂直居中；当前页红点、其余暗点；纯视觉不可点。
    暗点仅在该页确有取件码时显示 —— 因此 3 条(每页2)只显示 2 个点，而非容量 3 个。
    可见性只用 ==/**/|| 与常量，不依赖 >/< 比较。"""
    max_pages = (MAX_ROWS + perPage - 1) // perPage
    if max_pages <= 1:
        return ""
    parts = []
    gap = 16
    for p in range(max_pages):
        cx = "#view_width-18*#u"
        cy = f"#view_height/2+({p-(max_pages-1)/2})*{gap}*#u"
        # 该页首条索引：page p 含取件码 k∈[first..first+perPage-1]；有数据 ⇔ pc_rows≥first
        first = p * perPage + 1
        has = " || ".join(f"#pc_rows_shown=={k}" for k in range(first, MAX_ROWS + 1))
        parts.append(
            f'      <Rectangle x="{cx}-3*#u" y="{cy}-3*#u" w="6*#u" h="6*#u" '
            f'cornerRadiusExp="3*#u" fillColor="@secondaryInk" '
            f'visibility="ifelse({has},1,0)"/>')
        parts.append(
            f'      <Rectangle x="{cx}-3*#u" y="{cy}-3*#u" w="6*#u" h="6*#u" '
            f'cornerRadiusExp="3*#u" fillColor="@countRed" '
            f'visibility="ifelse(#page=={p},1,0)"/>')
    return "\n".join(parts)


def mini_widget() -> str:
    """1x1 角落总数卡：左上「待取」+ 居中放大数字（总件数）+ 下方「件待取」。"""
    return """      <Text x="#padX" y="#padTop" width="100*#u" size="15*#u" color="@secondaryInk"
            fontFamily="mipro-demibold" textExp="'待取'"/>
      <Text x="#view_width/2" y="#view_height/2" align="center" alignV="center"
            size="84*#u" color="@countRed" fontFamily="mipro-demibold"
            textExp="formatFloat('%.0f',#pc_rows)"/>
      <Text x="#view_width/2" y="#view_height/2+50*#u" align="center" alignV="center"
            size="15*#u" color="@secondaryInk" fontFamily="mipro-regular"
            textExp="'件待取'"/>"""


def layout(cfg: dict) -> str:
    per_page = cfg["rows"]
    if per_page <= 0:
        # 1x1 角落卡：只显示总数，不翻页
        return mini_widget()
    parts = [header(), empty_card()]
    for r in range(1, MAX_ROWS + 1):
        local_i = (r - 1) % per_page + 1
        page_index = (r - 1) // per_page
        parts.append(row_card(r, local_i, page_index, cfg["wide"]))
    parts.append(page_dots(per_page))
    return "\n".join(parts)


def main() -> None:
    for key, cfg in SIZES.items():
        per_page = cfg["rows"]
        max_pages = 1 if per_page <= 0 else (MAX_ROWS + per_page - 1) // per_page
        last_index = max_pages - 1
        xml = HEADER.substitute(
            design_key=key, design_w=cfg["w"], design_h=cfg["h"],
            u_factor=cfg["u"], material_vars=MATERIAL_VARS, glass_params=GLASS_PARAMS,
            bloom_stroke=BLOOM_STROKE, layout=layout(cfg),
            cp_vars=CP_VARS, lastIndex=last_index,
            openapp=open_app_buttons(cfg), pick=pick_buttons(cfg),
        )
        out = ROOT / "src" / f"manifest_{key}.xml"
        out.write_text(xml, encoding="utf-8")
        print("wrote", out.name, len(xml), "bytes, rows =", cfg["rows"])


if __name__ == "__main__":
    main()
