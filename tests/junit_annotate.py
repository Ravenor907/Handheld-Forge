"""Turn pytest JUnit XML failures into GitHub annotations (visible without logs)."""

import sys
import xml.etree.ElementTree as ET

failed = 0
for case in ET.parse(sys.argv[1]).iter("testcase"):
    for bad in list(case.findall("failure")) + list(case.findall("error")):
        failed += 1
        text = (bad.get("message") or "") + "\n" + (bad.text or "")
        text = text.strip().replace("%", "%25").replace("\r", "").replace("\n", "%0A")[-1500:]
        print(f"::error title={case.get('classname')}.{case.get('name')}::{text}")
print(f"{failed} failing test(s)")
