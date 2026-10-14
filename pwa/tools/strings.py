"""Writes js/strings.js and js/version.js from the Android app, so that both say the same words."""
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
OUT = ROOT / "pwa/js/strings.js"
VERSION = ROOT / "pwa/js/version.js"
GRADLE = ROOT / "app/build.gradle.kts"


def text(node):
    value = "".join(node.itertext())
    # Quoted values keep their spaces; the quotes themselves are not part of the text.
    if len(value) >= 2 and value.startswith('"') and value.endswith('"'):
        value = value[1:-1]
    value = value.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n").replace("\\@", "@")
    return value


def read(folder):
    root = ET.parse(RES / folder / "strings.xml").getroot()
    strings = {n.get("name"): text(n) for n in root.findall("string")}
    for p in root.findall("plurals"):
        strings[p.get("name")] = {i.get("quantity"): text(i) for i in p.findall("item")}
    return strings


def main():
    en = read("values")
    it = read("values-it")
    body = "// Written by tools/strings.py from the strings of the Android app: do not edit.\n"
    body += "export const STRINGS = " + json.dumps({"en": en, "it": it}, ensure_ascii=False, indent=0) + ";\n"
    OUT.write_text(body)
    # The web app takes the version of the Android app it is the twin of.
    version = re.search(r'versionName = "([^"]+)"', GRADLE.read_text()).group(1)
    VERSION.write_text(f"// Written by tools/strings.py from the version of the Android app.\nexport const APP_VERSION = '{version}';\n")
    print(f"{len(en)} strings, {len(it)} in Italian", file=sys.stderr)


if __name__ == "__main__":
    main()
