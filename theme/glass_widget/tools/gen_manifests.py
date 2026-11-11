#!/usr/bin/env python3
"""生成取件喵柔光玻璃主题的 MAML manifest.xml（4 个尺寸）。

样式严格对齐 app/src/main/res/layout/express_widget.xml / express_widget_row.xml：
  - 标题：红色 26sp 数字 + 18sp「件待取」，粗体
  - 取件信息行：不再画 cardBg 卡片背景，只保留文字 + 蓝色方形「取」按钮
  - 第一行：16sp 快递公司（跟随玻璃明暗反色，不用品牌色）+ 26sp 粗体取件码 + 蓝色方形「取」按钮
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
  - **翻页（11-11 第 19~23 轮，当前最终态）**：MAML 主题部件在 HyperOS 桌面上**拿不到短滑动
    手势**（三轮实测都只在长按后才翻页：桌面在 ACTION_DOWN 就消费了短滑动，
    `interceptTouch` 只管 widget 收到事件之后，见避坑 C70）。因此改为**点按翻页**：
    标题栏右侧「◄ 当前页/总页数 ►」两个大热区箭头 Button 改写 #page。
    箭头字符别用 ◀ / ▶：它们是 emoji 字形，桌面会渲染成橙色方块（第 24 轮）。
    totalPages 按 pc_rows_shown（真实条数封顶 5）算，不再按容量翻出空白页。
    根标签 `frameRate="0"` + `useVariableUpdater="DateTime.Minute"` 意味着无更新时停止渲染、
    每分钟才更新一次；桌面 host 点击后会补一次重绘，负一屏 host 不会 → 「点了不变」。
    故每次翻页追加 `<PermanenceCommand requestUpdate="true"/>` 主动请求宿主重绘
    （官方语法「小部件持久化变量」明确标注「OS 新增，请在小部件中使用」）。
    `onFling` 是 Android GestureDetector 回调名，不是 MAML Trigger action，永远别用。
    第 23 轮：取件后页数缩减仍会空白 —— 表达式变量在同一帧按声明顺序求值，
    safePage 读 #totalPages 会拿到上一帧旧页数；改成直接按 #pc_rows_shown 展开，
    点「取」时再用 VariableCommand 把 page 写回剩余条数的合法页，并给单页行加显示兜底。

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
    # pager = 标题栏右侧分页控件宽度（设计单位，运行时乘 #u）；0 = 该尺寸完全不显示分页控件
    #          （pagerW=0 ⇒ pagerX=right_edge，openApp 标题栏自动恢复整条可点）。
    # 第 21 轮按用户要求：只有 4x2 / 5x2 显示箭头与页码，2x1 / 2x2 / 1x1 一律 0。
    "1x1": dict(w=216, h=216, rows=0, u=1.0, wide=False, pager=0),   # 角落总数卡，无分页
    "2x1": dict(w=440, h=165, rows=1, u=1.15, wide=False, pager=0),
    "2x2": dict(w=440, h=440, rows=2, u=2.0, wide=False, pager=0),
    "4x2": dict(w=948, h=440, rows=2, u=2.0, wide=True, pager=168),
    "5x2": dict(w=1032, h=440, rows=2, u=2.0, wide=True, pager=168),   # 实验尺寸
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
  <!-- 方形「取」按钮：边长 = 取件码(26*#u) + 地址(17*#u) 的高度之和，宽高相等。 -->
  <Var name="btnSide" type="number" expression="26*#u+17*#u"/>
  <Var name="btnW" type="number" expression="#btnSide"/>
  <Var name="btnH" type="number" expression="#btnSide"/>
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
  <!-- 计数红字在红色系壁纸上会「红底红字」糊掉：红色随玻璃明暗自适应（深玻璃用亮珊瑚红、
       浅玻璃用深红），并配一层随明暗反色的柔光阴影，让字在任何底色上都有轮廓分离。
       阴影属性由 TextScreenElement 解析（shadowColor/shadowRadius/shadowDx/shadowDy），
       ColorParser 支持 @变量 引用，与 color="@countRed" 同一套机制。 -->
  <Var name="countRed" type="string" expression="ifelse(#uiDark,'#FFFF7B6E','#FFC1272A')"/>
  <Var name="countHalo" type="string" expression="ifelse(#uiDark,'#E6000000','#E6FFFFFF')"/>
  <Var name="pickBg" type="string" expression="ifelse(#uiDark,'#FF4A8CFF','#FF3482FF')"/>
  <!-- 分页控件（标题栏右侧靠右对齐）：◄ 当前页·总页数 ►，纯点按翻页。
       page 是可写状态变量，由箭头 Button 的 VariableCommand 改写；
       totalPages 由生成期按「真实条数 rows_shown」展开成 ==/||/ifelse 表达式，
       保证 3 条(每页 2)只算 2 页，不会翻到空白页；pagerW 按尺寸注入避免窄卡挤爆标题。
       safePage 只用于页码文字显示：取件后数据变少、页数从 2 变 1 时 page 可能仍停在
       已不存在的第 2 页。第 23 轮：行 / 取件按钮 / 箭头步进全部改为按 #pc_rows_shown
       与 #page 生成期展开，不再读 #safePage / #totalPages 表达式变量（frameRate=0 时
       同一帧按声明顺序求值会读到上一帧旧值）；safePage 自身也直接按 pc_rows_shown 算。
       点「取」时另用 VariableCommand 把 page 写回剩余条数能显示的合法页。
       hitW = 3/8 pagerW 是箭头点击热区宽度（第 20 轮从 1/4 加大），热区高度占满标题栏。
       pagerTick 只是刷新信号：根标签 frameRate=0，负一屏 host 点击后不主动重绘，
       每次翻页用 PermanenceCommand requestUpdate="true" 请求宿主重取一帧。
       第 21 轮：仅 4x2/5x2（pager>0）生成控件，且 totalPages>1 才显示；
       第 24 轮：箭头改用 ◄ / ►（非 emoji 字形），避免 ◀ / ▶ 被桌面渲染成
       带橙色底块的彩色 emoji，白色 color 对彩色字形不生效。
       第 26 轮：箭头改用圆润图标 PNG（arrow_prev.png / arrow_next.png，白色、
       透明背景，打包在 widget 包根目录），<Image src tint="@titleInk"> 渲染：
       深色主题染白、浅色主题染黑，跟标题栏字体同色（tint 支持变量、不支持表达式，
       只对白色底图生效）；箭头与页码中心都落在标题栏中线 #padTop+#titleH/2，
       与页数垂直居中对齐；热区 Button 不变。 -->
  <Var name="page" type="number"/>
  <Var name="totalPages" type="number" expression="${total_pages}"/>
  <Var name="safePage" type="number" expression="${safe_page}"/>
  <Var name="pagerW" type="number" expression="${pager_w}*#u"/>
  <Var name="pagerX" type="number" expression="#padX+#cardW-#rowPad-#pagerW"/>
  <Var name="hitW" type="number" expression="#pagerW*3/8"/>
  <Permanence name="pagerTick" expression="0" type="number"/>
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
      <!-- 「取」按钮与分页箭头都在独立图层，各带 interceptTouch 独占点按；
           openApp 分区与二者几何零重叠（标题栏右侧整段让给分页控件），
           避免点箭头 / 点取件连带跳 App（避坑 C66）。 -->
      ${pick}
      ${pager}
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
            color="@countRed" fontFamily="mipro-demibold" textExp="formatFloat('%.0f',#pc_rows)"
            shadowColor="@countHalo" shadowRadius="3" shadowDx="0" shadowDy="0"/>
      <Text x="#padX+#rowPad+#txt_count.text_width+6*#u" y="#padTop+#titleH/2" width="140*#u" alignV="center" size="18*#u"
            color="@titleInk" fontFamily="mipro-demibold" textExp="@pc_suffix"/>"""


def row_card(data_index: int, local_i: int, page_index: int, wide: bool, per_page: int) -> str:
    """一条取件码。
    - data_index：1..MAX_ROWS，对应 pc_company{data_index} 等数据列；
    - local_i   ：页内行号 1..perPage，决定这一行在卡片里的 y（各页内容叠在同一区域，
                  靠 #safePage 切换可见性，由标题栏右侧箭头点按翻页把多余条目翻出来）；
    - page_index：这一行属于第几页（0 基）。
    「我已取件」按钮直接发广播标记该行已取（与安卓小部件一致，按行 id 精确标记，不跳列表）。"""
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
    tpl = """      <Group visibility="ifelse((@PAGEMATCH@) ** (@HASDATA@),1,0)">
@BODY@
        <Rectangle x="#padX+#cardW-#rowPad-#btnW" y="@Y@+(#rowH-#btnH)/2" w="#btnW" h="#btnH"
                   cornerRadius="12" cornerRadiusExp="12*#u" fillColor="@pickBg"/>
        <Text x="#padX+#cardW-#rowPad-#btnW/2" y="@Y@+#rowH/2"
              align="center" alignV="center" size="20*#u" color="#FFFFFFFF"
              fontFamily="mipro-demibold" textExp="'取'"/>
      </Group>"""
    has_data = " || ".join(f"#pc_rows_shown=={k}" for k in range(data_index, MAX_ROWS + 1))
    return (tpl.replace("@BODY@", body).replace("@N@", str(data_index))
            .replace("@HASDATA@", has_data).replace("@Y@", y)
            .replace("@PAGEMATCH@", page_match_expr(page_index, per_page)))


# 分页（第 19 轮）：MAML 主题部件拿不到短滑动手势（避坑 C70），改为标题栏右侧点按翻页。
# 页数与翻页步进全部用生成期展开的 ==/||/ifelse，只用真机已验证的语法，
# 不依赖比较符或取模；totalPages 按真实条数算，避免翻到空白页。
def multi_page_expr(per_page: int) -> str:
    """只有总页数 > 1（即条数超过一页容量）才显示分页控件，箭头和页码一起隐藏。
    第 23 轮改为直接展开 #pc_rows_shown：#totalPages 是表达式变量，取件后同一帧
    可能是旧页数，会让「单页隐藏」这一判断滞后；只用 == / ||，不用比较符。"""
    if per_page <= 0:
        return "0"
    return " || ".join(f"#pc_rows_shown=={k}" for k in range(per_page + 1, MAX_ROWS + 1))


def total_pages_expr(per_page: int) -> str:
    """总页数 = ceil(rows_shown / per_page)，下限 1；按 rows_shown 的取值逐一展开。"""
    max_pages = (MAX_ROWS + per_page - 1) // per_page
    expr = "1"
    for t in range(2, max_pages + 1):
        first = (t - 1) * per_page + 1
        last = min(t * per_page, MAX_ROWS)
        conds = " || ".join(f"#pc_rows_shown=={k}" for k in range(first, last + 1))
        expr = f"ifelse({conds},{t},{expr})"
    return expr


def page_step_expr(per_page: int, delta: int) -> str:
    """点箭头后的页码：按 (#pc_rows_shown, #page) 组合静态展开循环步进。
    与钳制 / 行可见性一样只依赖原始广播变量与可写变量，不读 #safePage /
    #totalPages 表达式变量，避免点击那一帧读到上一帧的旧页数。"""
    expr = "0"
    for k in range(MAX_ROWS, -1, -1):
        total = max(1, (k + per_page - 1) // per_page)
        inner = clamp_over_pages(
            per_page, lambda p, t=total: str((p + delta) % t if p < t else 0))
        expr = f"ifelse(#pc_rows_shown=={k},{inner},{expr})"
    return expr


def clamp_over_pages(per_page: int, value_for) -> str:
    """按 #page==0..max_pages-1 展开；任何未知 / 越界的 page 一律按最大页处理，
    表达式链末尾不留「默认 0」这种会把内容打回错误页的兜底。"""
    max_pages = (MAX_ROWS + per_page - 1) // per_page
    expr = value_for(max_pages - 1)
    for p in range(max_pages - 2, -1, -1):
        expr = f"ifelse(#page=={p},{value_for(p)},{expr})"
    return expr


def safe_page_expr(per_page: int) -> str:
    """把 #page 钳制到 [0, totalPages-1]，但**直接按 #pc_rows_shown 展开**，不引用
    另一个表达式变量 #totalPages：根标签 frameRate=0，表达式变量在同一帧按声明顺序
    求值，读 #totalPages 会拿到上一帧的旧页数 —— 取件后 2 页→1 页时 safePage 仍停在
    第 2 页，行全隐藏而分页控件已隐藏，整片空白且无法翻回（用户实测第 23 轮）。
    只依赖广播写入的 pc_rows_shown 与可写变量 page，任何求值时机都得到正确页码。"""

    def for_page(p: int) -> str:
        inner = "0"
        for k in range(MAX_ROWS, -1, -1):
            total = max(1, (k + per_page - 1) // per_page)
            inner = f"ifelse(#pc_rows_shown=={k},{min(p, total - 1)},{inner})"
        return inner

    return clamp_over_pages(per_page, for_page)


def page_match_expr(page_index: int, per_page: int) -> str:
    """行 / 取件按钮的「是否属于当前页」条件，**完全由 #pc_rows_shown 与 #page
    生成期展开**，不引用 #safePage / #totalPages 表达式变量：frameRate=0 时表达式
    变量可能停留在上一帧，取件后页数缩减就会让所有行都不匹配 → 整片空白。
    - 单页（rows <= per_page）：任意 page（含越界值）都落到第一页；
    - 最后一页：所有「大于末页下界」的 page 都落到最后一页；
    - 其余页：只有 page 精确相等才显示。
    这样即使一帧里表达式变量全是旧值，行内容也永远看得见、且不会重复渲染。"""
    expr = "0"
    for k in range(MAX_ROWS, -1, -1):
        total = max(1, (k + per_page - 1) // per_page)
        if page_index >= total:
            cond = "0"
        elif page_index == total - 1:
            lower = " || ".join(f"#page=={p}" for p in range(0, page_index))
            cond = f"ifelse({lower},0,1)" if lower else "1"
        else:
            # 分支值统一写成 ifelse(...,1,0)：比较表达式直接当分支值在真机语法上没验证过
            cond = f"ifelse(#page=={page_index},1,0)"
        expr = f"ifelse(#pc_rows_shown=={k},{cond},{expr})"
    return expr


def page_after_pick_expr(per_page: int) -> str:
    """点「取」时立刻把 #page 钳到「本条取走后（rows_shown-1 条）」的合法页。
    这是命令写入 page 本身，不依赖数据广播后表达式变量有没有重算，也不依赖
    requestUpdate 是否生效：3 条取走 1 条 ⇒ 2 条 = 1 页 ⇒ page=0，直接回到第一页。"""
    expr = "0"
    for k in range(MAX_ROWS, -1, -1):
        left = max(k - 1, 0)
        total = max(1, (left + per_page - 1) // per_page)
        inner = clamp_over_pages(per_page, lambda p, t=total: str(min(p, t - 1)))
        expr = f"ifelse(#pc_rows_shown=={k},{inner},{expr})"
    return expr


def pager_visual(cfg: dict) -> str:
    """标题栏右侧「◄ 当前页/总页数 ►」纯视觉；箭头 Button 在独立图层。
    第 21 轮：固定纯白；只有多页时才显示整个控件。
    第 24 轮：箭头曾用 ◄ / ►（非 emoji 实心三角），避免 ◀/▶ 被桌面渲染成橙底 emoji。
    第 25 轮：曾做成半透明圆角圆形按钮，被用户否决——他要的是**箭头形状圆润**，不是加背景。
    第 26 轮：改用用户提供的圆润箭头 PNG（arrow_prev.png / arrow_next.png，白色、
    透明背景、256×256，放 widget 包根目录），<Image src tint="@titleInk"> 渲染。
    tint 引用颜色变量（MIUI12+，支持变量、不支持表达式）：深色主题染白、浅色主题染黑，
    跟标题栏字体 @titleInk 完全一致。tint 只对亮色底图生效，所以底图必须是白色。
    箭头与页码 y 都用标题栏中线 #padTop+#titleH/2；Image 的 x/y 用「中心 − 半边长」
    展开式定位（不依赖 align 语义），保证与页数垂直居中。热区 Button 不变。"""
    if cfg["pager"] <= 0:
        return ""
    pages_cond = multi_page_expr(cfg["rows"])
    vis = f"ifelse(({pages_cond}),1,0)"
    cy = "#padTop+#titleH/2"          # 标题栏中线：箭头与页码共用的垂直中心
    sz = "#pagerW/6"                  # 箭头图标边长（约等于原三角字号）
    half = "#pagerW/12"               # 半边长，作左上角偏移
    lx = "#pagerX+#hitW/2"            # 左箭头中心 x
    mx = "#pagerX+#pagerW/2"          # 页码中心 x
    rx = "#pagerX+#pagerW-#hitW/2"    # 右箭头中心 x
    return f"""      <Group visibility="{vis}">
        <Image x="{lx}-{half}" y="{cy}-{half}" w="{sz}" h="{sz}"
               src="arrow_prev.png" tint="@titleInk"/>
        <Text x="{mx}" y="{cy}" align="center" alignV="center"
              size="14*#u" color="@titleInk" fontFamily="mipro-demibold"
              textExp="formatFloat('%.0f',#safePage+1)+'/'+formatFloat('%.0f',#totalPages)"/>
        <Image x="{rx}-{half}" y="{cy}-{half}" w="{sz}" h="{sz}"
               src="arrow_next.png" tint="@titleInk"/>
      </Group>"""


def pager_buttons(cfg: dict) -> str:
    """两个大热区透明箭头 Button（独立图层，带 interceptTouch 独占点按）。
    第 20 轮：热区 hitW(3/8 pagerW) × 整个标题栏高，y=0、h=#padTop+#titleH；
    第 21 轮热区**不变**（用户要求「箭头收小但点击区域别减小」），
    显示条件改为 totalPages>1；与 openApp 分区仍零重叠。
    每次翻页追加 PermanenceCommand requestUpdate="true"：根标签 frameRate=0，
    负一屏（com.miui.personalassistant）点击后不会主动重绘，靠它请求宿主重取一帧。"""
    per_page = cfg["rows"]
    if cfg["pager"] <= 0:
        return ""
    pages_cond = multi_page_expr(per_page)
    vis = f"ifelse(({pages_cond}),1,0)"
    prev_expr = page_step_expr(per_page, -1)
    next_expr = page_step_expr(per_page, 1)

    def arrow(x: str, expr: str) -> str:
        return (f'      <Button x="{x}" y="0" w="#hitW" h="#padTop+#titleH" '
                f'interceptTouch="true" visibility="{vis}">\n'
                f'        <Triggers>\n'
                f'          <Trigger action="up">\n'
                f'            <VariableCommand name="page" expression="{expr}"/>\n'
                f'            <PermanenceCommand name="pagerTick" expression="#time_sys" '
                f'requestUpdate="true" type="number"/>\n'
                f'          </Trigger>\n'
                f'        </Triggers>\n'
                f'      </Button>')

    return "\n".join([
        arrow("#pagerX", prev_expr),
        arrow("#pagerX+#pagerW-#hitW", next_expr),
    ])


CLICK_TRIGGERS = """        <Triggers>
          <Trigger action="up"><FunctionCommand target="openApp"/></Trigger>
        </Triggers>
"""


def open_app_buttons(cfg: dict) -> str:
    """卡片上「除取件按钮 / 分页箭头外」的可点区域 → 跳 App 待取件列表。

    必须与取件按钮、分页箭头**零重叠**（避坑 C66）：MAML 对重叠兄弟元素的点按会
    派发给所有层，一张全卡 Button 会让点「取」/ 点箭头连带启动列表页。
    按几何拆成不重叠矩形：
      - 标题栏：左侧 [0, pagerX]（含「N 件待取」）+ 最右窄条 [right_edge, 卡片右缘]；
        中间 [pagerX, right_edge] 整段让给分页控件，不挂 openApp；
      - 内容区：左侧大块 [0, left_edge] + 最右窄条 [right_edge, ...]；
      - 按钮列内、每个行槽里「按钮上方」与「按钮下方」的空白（含行间 gap）。
    1x1 迷你卡没有取件按钮、也无分页，整卡一个纯点击矩形。
    """
    def rect(x: str, y: str, w: str, h: str) -> str:
        return (f'      <Button x="{x}" y="{y}" w="{w}" h="{h}">\n'
                f'{CLICK_TRIGGERS}'
                f'      </Button>')

    per_page = cfg["rows"]
    if per_page <= 0:
        return rect("0", "0", "#view_width", "#view_height")

    left_edge = "#padX+#cardW-#rowPad-#btnW"   # 「取」按钮列左缘
    right_edge = "#padX+#cardW-#rowPad"        # 「取」按钮列右缘 = 卡片内容右缘
    content_top = "#padTop+#titleH"
    content_h = "#view_height-(#padTop+#titleH)"
    parts = [
        rect("0", "0", "#pagerX", content_top),
        rect(right_edge, "0", "#padX+#rowPad", content_top),
        rect("0", content_top, left_edge, content_h),
        rect(right_edge, content_top, "#padX+#rowPad", content_h),
    ]
    for local_i in range(1, per_page + 1):
        row_top = f"{content_top}+{local_i - 1}*(#rowH+#gap)"
        btn_y = f"{row_top}+(#rowH-#btnH)/2"
        parts.append(rect(left_edge, row_top, "#btnW", "(#rowH-#btnH)/2"))
        parts.append(rect(left_edge, f"{btn_y}+#btnH", "#btnW",
                          "(#rowH-#btnH)/2+#gap"))
    return "\n".join(parts)


def pick_button(data_index: int, local_i: int, page_index: int,
                per_page: int, clamp_page: str) -> str:
    """「我已取件」按钮：置于内容层之上（${pick}），点它直接发广播精确标记该行已取（按行 id），不跳列表。
    可见性与对应行一致（本行有数据 且 处于当前页），避免翻到别的页时误点中错位按钮。
    第 23 轮：up 里先写 page = 取走本条后的合法页，再发取件广播，最后请求宿主重绘；
    「3 条→2 条→1 页」时立刻回到第一页，不留越界的 page 状态。"""
    by = f"#padTop+#titleH+{local_i - 1}*(#rowH+#gap)+(#rowH-#btnH)/2"
    has_data = " || ".join(f"#pc_rows_shown=={k}" for k in range(data_index, MAX_ROWS + 1))
    match = page_match_expr(page_index, per_page)
    return (f'      <Button x="#padX+#cardW-#rowPad-#btnW" y="{by}" w="#btnW" h="#btnH" '
            f'interceptTouch="true" '
            f'visibility="ifelse(({match}) ** ({has_data}),1,0)">\n'
            f'        <Triggers>\n'
            f'          <Trigger action="up">\n'
            f'            <VariableCommand name="page" expression="{clamp_page}"/>\n'
            f'            <IntentCommand\n'
            f'              action="com.toretto5060.pickupcatapp.MAML_PICK"\n'
            f'              package="com.toretto5060.pickupcatapp"\n'
            f'              broadcast="true">\n'
            f'              <Extra name="row_id" type="long" expression="#pc_id{data_index}"/>\n'
            f'              <Extra name="row_index" type="string" expression="\'{data_index}\'"/>\n'
            f'            </IntentCommand>\n'
            f'            <PermanenceCommand name="pagerTick" expression="#time_sys" '
            f'requestUpdate="true" type="number"/>\n'
            f'          </Trigger>\n'
            f'        </Triggers>\n'
            f'      </Button>')


def pick_buttons(cfg: dict) -> str:
    """所有「我已取件」按钮（独立图层，位于内容层之上）。"""
    per_page = cfg["rows"]
    if per_page <= 0:
        return ""
    clamp_page = page_after_pick_expr(per_page)
    parts = []
    for r in range(1, MAX_ROWS + 1):
        local_i = (r - 1) % per_page + 1
        page_index = (r - 1) // per_page
        parts.append(pick_button(r, local_i, page_index, per_page, clamp_page))
    return "\n".join(parts)


def empty_card() -> str:
    """0 件待取：只显示 15sp「还没有待取件」，不画卡片背景；y 同样是文字中心。"""
    return """      <Group visibility="ifelse(#pc_count==0,1,0)">
        <Text x="#padX+#rowPad" y="#padTop+#titleH+#rowH/2" width="#cardW-2*#rowPad"
              alignV="center" size="15*#u" color="@secondaryInk" fontFamily="mipro-regular"
              textExp="ifelse(eqs(@pc_status,'ok'),@pc_empty_text,'等待取件喵数据')"/>
      </Group>"""


# 圆点指示器已由标题栏「‹ 当前页/总页数 ›」取代（第 19 轮）。


def mini_widget() -> str:
    """1x1 角落总数卡：左上「待取」+ 居中放大数字（总件数）+ 下方「件待取」。"""
    return """      <Text x="#padX" y="#padTop" width="100*#u" size="15*#u" color="@secondaryInk"
            fontFamily="mipro-demibold" textExp="'待取'"/>
      <Text x="#view_width/2" y="#view_height/2" align="center" alignV="center"
            size="84*#u" color="@countRed" fontFamily="mipro-demibold"
            textExp="formatFloat('%.0f',#pc_rows)"
            shadowColor="@countHalo" shadowRadius="5" shadowDx="0" shadowDy="0"/>
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
        parts.append(row_card(r, local_i, page_index, cfg["wide"], per_page))
    parts.append(pager_visual(cfg))
    return "\n".join(parts)


def main() -> None:
    for key, cfg in SIZES.items():
        per_page = cfg["rows"]
        xml = HEADER.substitute(
            design_key=key, design_w=cfg["w"], design_h=cfg["h"],
            u_factor=cfg["u"], material_vars=MATERIAL_VARS, glass_params=GLASS_PARAMS,
            bloom_stroke=BLOOM_STROKE, layout=layout(cfg),
            cp_vars=CP_VARS,
            total_pages=total_pages_expr(max(per_page, 1)), pager_w=cfg["pager"],
            safe_page=safe_page_expr(max(per_page, 1)),
            openapp=open_app_buttons(cfg), pick=pick_buttons(cfg),
            pager=pager_buttons(cfg),
        )
        out = ROOT / "src" / f"manifest_{key}.xml"
        out.write_text(xml, encoding="utf-8")
        print("wrote", out.name, len(xml), "bytes, rows =", cfg["rows"])


if __name__ == "__main__":
    main()
