#!/usr/bin/env python3
"""把 theme/glass_widget 源码打包成可直接导入「主题壁纸 / For设计师」的 .mtz。

.mtz 结构（对齐「透明天气（4种尺寸合集）.mtz」与系统内置 MAML 小部件）：
  description.xml
  widget_2x1 | widget_2x2 | widget_4x2   ← 每个都是「内层 zip」，含 manifest.xml / var_config.xml / assets/
  preview/widget_<size>.png + _dark.png
"""
from io import BytesIO
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "dist" / "取件喵柔光玻璃小部件.mtz"
SIZES = ("2x1", "2x2", "4x2")
CONFIG_ASSETS = (
    "glass_bionic.webp",
    "glass_common_light.webp",
    "glass_common_dark.webp",
)


def check_sources() -> None:
    for path in [ROOT / "description.xml", ROOT / "var_config.xml"] + [
        ROOT / "src" / f"manifest_{size}.xml" for size in SIZES
    ]:
        ET.parse(path)
    for size in SIZES:
        for suffix in ("", "_dark"):
            path = ROOT / "preview" / f"widget_{size}{suffix}.png"
            if not path.is_file():
                raise FileNotFoundError(path)
    for asset in CONFIG_ASSETS:
        if not (ROOT / "assets/config" / asset).is_file():
            raise FileNotFoundError(asset)


def widget_zip(size: str) -> bytes:
    buf = BytesIO()
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED) as z:
        # 目录项与官方 MAML 包保持一致（资源查找用相对路径）。
        z.writestr("assets/", b"")
        z.writestr("assets/config/", b"")
        z.write(ROOT / "src" / f"manifest_{size}.xml", "manifest.xml")
        z.write(ROOT / "var_config.xml", "var_config.xml")
        for asset in CONFIG_ASSETS:
            z.write(ROOT / "assets/config" / asset, f"assets/config/{asset}")
    return buf.getvalue()


def build() -> None:
    check_sources()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(OUT, "w", compression=zipfile.ZIP_DEFLATED) as z:
        z.write(ROOT / "description.xml", "description.xml")
        for size in SIZES:
            z.writestr(f"widget_{size}", widget_zip(size))
        for size in SIZES:
            for suffix in ("", "_dark"):
                # PNG 已压缩，按官方包习惯原样存储，避免二次 deflate 反而变大。
                z.write(ROOT / "preview" / f"widget_{size}{suffix}.png",
                        f"preview/widget_{size}{suffix}.png",
                        compress_type=zipfile.ZIP_STORED)
        z.writestr("preview/", b"")


def verify() -> None:
    with zipfile.ZipFile(OUT) as z:
        assert z.testzip() is None, "outer zip corrupted"
        names = set(z.namelist())
        assert "description.xml" in names and "preview/" in names
        ET.fromstring(z.read("description.xml"))
        for size in SIZES:
            assert f"widget_{size}" in names
            assert f"preview/widget_{size}.png" in names
            assert f"preview/widget_{size}_dark.png" in names
            with zipfile.ZipFile(BytesIO(z.read(f"widget_{size}"))) as inner:
                assert inner.testzip() is None, f"widget_{size} corrupted"
                inner_names = set(inner.namelist())
                assert {"manifest.xml", "var_config.xml"} <= inner_names
                ET.fromstring(inner.read("manifest.xml"))
                ET.fromstring(inner.read("var_config.xml"))
                for asset in CONFIG_ASSETS:
                    assert f"assets/config/{asset}" in inner_names
    print(f"OK: {OUT} ({OUT.stat().st_size} bytes)")
    print("    sizes:", ", ".join(SIZES))


if __name__ == "__main__":
    build()
    verify()
