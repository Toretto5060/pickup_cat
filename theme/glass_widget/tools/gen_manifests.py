#!/usr/bin/env python3
"""生成取件喵柔光玻璃主题的 3 份 MAML manifest.xml。

结构对齐小米 OS4 官方小部件（calendar-4x1-maml / widget_clock_2x2）：
MaterialToken 申请系统 BackgroundBlur + Glass + ColorBlend + BloomStroke，
变量由 #hyper_material_* 系统变量驱动；数据来自 ContentProviderBinder。
"""
from pathlib import Path
from string import Template
import re

ROOT = Path(__file__).resolve().parents[1]
MAX_ROWS = 4

MATERIAL_VARS = (ROOT / "src/material_vars.xml").read_text(encoding="utf-8")
MATERIAL_VARS = "\n".join(
    "  " + m.group(0)
    for m in re.finditer(r"<Var name=\"(?:common|bionic_dark|bionic_light)_glass_params\".*?/>", MATERIAL_VARS)
)

GLASS_PARAMS = ",".join(
    f"ifelse(#isBionic,ifelse(#applied_light_wallpaper,#bionic_light_glass_params[{i}],"
    f"#bionic_dark_glass_params[{i}]),#common_glass_params[{i}])"
    for i in range(42)
)

BINDER_COLUMNS = ",".join(
    ["count", "updated_at", "empty_text", "suffix"]
    + [f"{k}{i}" for i in range(1, MAX_ROWS + 1) for k in ("code", "company", "place", "time")]
)

BINDER_VARS = "\n".join(
    [f'      <Variable name="pc_count" type="number" column="count"/>',
     f'      <Variable name="pc_updated_at" type="number" column="updated_at"/>',
     f'      <Variable name="pc_empty_text" type="string" column="empty_text"/>',
     f'      <Variable name="pc_suffix" type="string" column="suffix"/>']
    + [f'      <Variable name="pc_{k}{i}" type="string" column="{k}{i}"/>'
       for i in range(1, MAX_ROWS + 1) for k in ("code", "company", "place", "time")]
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
<!-- 取件喵 · HyperOS 4 柔光玻璃小部件 (${design_key}) -->
<Widget frameRate="0" scaleByDensity="false" screenWidth="1080" version="2"
        supportBackgroundBlur="false" useVariableUpdater="DateTime.Minute,HyperMaterial"
        disableCutRoundCorner="false" enableLayerAnimation="1">
  <Var name="isInPicker" type="number" expression="eqs(@isPreviewMode,'true')"/>
  <Var name="outlineRadius" type="number" expression="ifelse(#isInPicker,0,ifelse(#widget_radius,(#widget_radius / #__scale_factor),60))"/>
  <Var name="designW" type="number" expression="${design_w}"/>
  <Var name="designH" type="number" expression="${design_h}"/>
  <Var name="scaleX" type="number" expression="(#view_width / #designW)"/>
  <Var name="scaleY" type="number" expression="(#view_height / #designH)"/>
  <Var name="scale" type="number" expression="ifelse((#scaleX { #scaleY),#scaleX,#scaleY)"/>
  <Var name="pad" type="number" expression="24*#scale"/>
  <Var name="showBg" type="number" expression="!#enable_hyper_material||#isInPicker||(#hyper_material_view_type==2)"/>
  <Var name="isGlass" type="number" expression="#hyper_material_view_type==1"/>
  <Var name="isBlend" type="number" expression="#hyper_material_view_type==0"/>
  <Var name="isGlassBionic" type="number" expression="#glass_type[0]"/>
  <Var name="isBionic" type="number" expression="#isGlassBionic ** #hyper_material_glass_enable ** (! #isInPicker) ** (#hyper_material_view_type==1)"/>
  <Var name="cardBgColor" type="string" expression="ifelse(#__darkmode,'#801C1C1E','#CCF5F8FC')"/>
  <Var name="rowColor" type="string" expression="ifelse(#__darkmode,'#33FFFFFF','#80FFFFFF')"/>
  <Var name="ink" type="string" expression="ifelse(#autoColor,ifelse(#__darkmode,'#FFFFFFFF','#F21A1A1A'),@textColor)"/>
  <Var name="subInk" type="string" expression="ifelse(#__darkmode,'#B3FFFFFF','#991A1A1A')"/>
  <Var name="codeInk" type="string" expression="ifelse(#__darkmode,'#FFFFFFFF','#FF0A63D6')"/>
  <Var name="accent" type="string" expression="'#FFFF3B30'"/>
  <Var name="hideCard" type="number" expression="(#pc_count==0) ** (! #showEmpty) ** (! #isInPicker)"/>
  <Var name="pc_tick" type="number" expression="0"/>
  <Var name="pc_last_query" type="number" expression="0"/>
${material_vars}
  <Layer alpha="ifelse(#hideCard,0,255)" width="#view_width" height="#view_height"
         frameRate="-1" layerType="bottom" x="0" y="0" export="true" name="root">
    <MaterialToken tokenName="pickupcat_glass_material" version="30">
      <BackgroundBlurConfig disableBlur="ifelse(#showBg,1,0)" blurBgMode="1" blurContentMode="1"
                            blurRadius="120" blurType="0" passWindow="true" rustPassWindow="true" reuseContainer="true"/>
      <GlassConfig params="${glass_params}" disableGlass="ifelse((#showBg || #isBlend),1,0)"
                   smallBlurRadius="36" bigBlurRadius="500"/>
      <ColorBlendConfig colors="ifelse(#__darkmode,'#52000000','#B3FFFFFF'),ifelse(#__darkmode,'#75000000','#33F9F9F9')"
                        colorModes="ifelse(#__darkmode,3,20),ifelse(#__darkmode,19,15)"
                        disableBlend="ifelse((#showBg || #isGlass),1,0)"/>
      ${bloom_stroke}
    </MaterialToken>
    <ViewOutlineProvider radius="#outlineRadius" type="rect" left="0" top="0" right="#view_width" bottom="#view_height"/>
    <Rectangle x="0" y="0" w="#view_width" h="#view_height" cornerRadius="#outlineRadius"
               fillColor="@cardBgColor" visibility="#showBg"/>
    <Group w="#view_width" h="#view_height">
      <Function name="openApp">
        <IntentCommand action="android.intent.action.MAIN"
                       package="com.toretto5060.pickupcatapp"
                       class="com.toretto5060.pickupcatapp.app.ExpressListActivity"
                       flags="268484608"/>
      </Function>
${layout}
      <Button x="0" y="0" w="#view_width" h="#view_height" interceptTouch="true"
              visibility="ifelse(#hideCard,0,1)">
        <Triggers>
          <Trigger action="up"><FunctionCommand target="openApp"/></Trigger>
        </Triggers>
      </Button>
    </Group>
  </Layer>

  <VariableBinders>
    <ContentProviderBinder name="pickupcat" uri="content://com.toretto5060.pickupcatapp.maml/pending"
                           columns="${binder_columns}" queryAtStart="true"
                           countName="pc_has_data" statusName="pc_status">
${binder_vars}
      <Trigger>
        <VariableCommand name="pc_tick" expression="#pc_tick+1"/>
      </Trigger>
    </ContentProviderBinder>
  </VariableBinders>

  <ExternalCommands>
    <Trigger action="init">
      <BinderCommand name="pickupcat" command="refresh"/>
      <VariableCommand name="pc_last_query" expression="#time_sys" type="number"/>
    </Trigger>
    <Trigger action="resume">
      <MultiCommand condition="((#time_sys - #pc_last_query) } 15000)">
        <BinderCommand name="pickupcat" command="refresh"/>
        <VariableCommand name="pc_last_query" expression="#time_sys" type="number"/>
      </MultiCommand>
    </Trigger>
  </ExternalCommands>
</Widget>
""")


def row_card(index: int, x: str, y: str, w: str, h: str, code_size: str) -> str:
    """一张取件码行卡片。@I@ / @Y@ / ... 是占位符，避免 f-string 与 MAML 的 } 冲突。"""
    tpl = """      <Group visibility="ifelse(#pc_count}@MIN@,1,0)">
        <Rectangle x="@X@" y="@Y@" w="@W@" h="@H@" cornerRadius="18*#scale" fillColor="@rowColor"/>
        <Text x="@X@+16*#scale" y="@Y@+12*#scale" size="@CODE_SIZE@*#scale" color="@codeInk"
              fontFamily="mipro-medium" textExp="@pc_code@I@"/>
        <Text x="@X@+16*#scale" y="@Y@+@META_Y@*#scale" size="15*#scale" color="@subInk"
              width="@W@-32*#scale" ellipsis="true" textExp="@pc_place@I@"/>
        <Text x="@X@+@W@-16*#scale" y="@Y@+14*#scale" width="120*#scale" align="right"
              size="15*#scale" color="@subInk" textExp="@pc_time@I@"/>
        <Text x="@X@+@W@-16*#scale" y="@Y@+@META_Y@*#scale" width="150*#scale" align="right"
              size="14*#scale" color="@subInk" ellipsis="true" textExp="@pc_company@I@"/>
      </Group>"""
    return (tpl.replace("@I@", str(index)).replace("@MIN@", str(index - 1))
            .replace("@X@", x).replace("@Y@", y).replace("@W@", w).replace("@H@", h)
            .replace("@CODE_SIZE@", code_size).replace("@META_Y@", str(int(h.split("*")[0]) - 34)))


def layout_2x1() -> str:
    return """      <Text x="#pad" y="#pad-4*#scale" size="20*#scale" color="@ink"
            fontFamily="mipro-medium" textExp="'取件喵'"/>
      <Text x="#pad" y="#pad+26*#scale" size="34*#scale" color="@ink"
            fontFamily="mipro-medium" textExp="formatFloat('%.0f',#pc_count)+' '+@pc_suffix"/>
      <Text x="#view_width-#pad" y="#pad+2*#scale" width="190*#scale" align="right"
            size="28*#scale" color="@codeInk" fontFamily="mipro-medium" ellipsis="true"
            textExp="ifelse(#pc_count}0,@pc_code1,@pc_empty_text)"/>
      <Text x="#view_width-#pad" y="#pad+42*#scale" width="190*#scale" align="right"
            size="14*#scale" color="@subInk" ellipsis="true"
            textExp="ifelse(#pc_count}0,@pc_place1,'')"/>
      <Text x="#pad" y="#view_height-#pad-18*#scale" size="14*#scale" color="@subInk"
            textExp="ifelse(#pc_count}0,@pc_time1+' 更新','')"/>"""


def layout_2x2() -> str:
    head = """      <Text x="#pad" y="#pad-4*#scale" size="20*#scale" color="@ink"
            fontFamily="mipro-medium" textExp="'取件喵'"/>
      <Text x="#pad" y="#pad+30*#scale" size="44*#scale" color="@ink"
            fontFamily="mipro-medium" textExp="formatFloat('%.0f',#pc_count)+' '+@pc_suffix"/>
      <Text x="#pad" y="#pad+92*#scale" size="16*#scale" color="@subInk"
            visibility="ifelse(#pc_count==0,1,0)" textExp="@pc_empty_text"/>"""
    rows = "\n".join(
        row_card(i, "#pad", f"({130+(i-1)*120})*#scale", "#view_width-2*#pad", "104*#scale", "32")
        for i in range(1, 3)
    )
    return head + "\n" + rows


def layout_4x2() -> str:
    head = """      <Text x="#pad" y="#pad-4*#scale" size="22*#scale" color="@ink"
            fontFamily="mipro-medium" textExp="'取件喵'"/>
      <Text x="#pad" y="#pad+34*#scale" size="56*#scale" color="@ink"
            fontFamily="mipro-medium" textExp="formatFloat('%.0f',#pc_count)"/>
      <Text x="#pad" y="#pad+104*#scale" size="20*#scale" color="@subInk" textExp="@pc_suffix"/>
      <Text x="#pad" y="#pad+140*#scale" size="16*#scale" color="@subInk"
            visibility="ifelse(#pc_count==0,1,0)" textExp="@pc_empty_text"/>
      <Rectangle x="316*#scale" y="#pad+8*#scale" w="1*#scale" h="#view_height-2*#pad-16*#scale"
                 fillColor="@subInk" alpha="60"/>"""
    rows = "\n".join(
        row_card(i, "340*#scale", f"({30+(i-1)*126})*#scale", "#view_width-340*#scale-#pad", "112*#scale", "34")
        for i in range(1, 4)
    )
    return head + "\n" + rows


LAYOUTS = {
    "2x1": (440, 165, layout_2x1),
    "2x2": (440, 440, layout_2x2),
    "4x2": (948, 440, layout_4x2),
}


def main() -> None:
    for key, (w, h, layout_fn) in LAYOUTS.items():
        xml = HEADER.substitute(
            design_key=key, design_w=w, design_h=h,
            material_vars=MATERIAL_VARS, glass_params=GLASS_PARAMS,
            bloom_stroke=BLOOM_STROKE, layout=layout_fn(),
            binder_columns=BINDER_COLUMNS, binder_vars=BINDER_VARS,
        )
        out = ROOT / "src" / f"manifest_{key}.xml"
        out.write_text(xml, encoding="utf-8")
        print("wrote", out.name, len(xml), "bytes")


if __name__ == "__main__":
    main()
