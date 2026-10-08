"""Verify the APK package, version and signature before uploading it."""
import argparse
import os
from pathlib import Path
import shlex
import subprocess


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("apk", type=Path)
parser.add_argument("--tag")
args = parser.parse_args()
properties = dict(
    line.strip().split("=", 1)
    for line in Path("gradle.properties").read_text().splitlines()
    if line.startswith("appVersion")
)
expected = {
    "name": "moe.starmoe.box",
    "versionName": properties["appVersionName"],
    "versionCode": properties["appVersionCode"],
}
if args.tag is not None and args.tag != "v" + expected["versionName"]:
    raise SystemExit("Release tag must match appVersionName in gradle.properties")
tools = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "35.0.0"
badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(args.apk)], text=True)
package = dict(item.split("=", 1) for item in shlex.split(badging.splitlines()[0])[1:])
for name, value in expected.items():
    if package.get(name) != value:
        raise SystemExit(f"APK {name} must be {value}, got {package.get(name)}")
subprocess.run([str(tools / "apksigner"), "verify", "--print-certs", str(args.apk)], check=True)
print(f"Verified {expected['name']} {expected['versionName']} ({expected['versionCode']})")
