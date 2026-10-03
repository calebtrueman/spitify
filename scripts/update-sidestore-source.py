#!/usr/bin/env python3
"""Adds a release to ios/sidestore-source.json (newest first) so SideStore/AltStore can offer the update.
Usage: update-sidestore-source.py VERSION IPA_PATH DOWNLOAD_URL [NOTES]"""
import json, os, sys, datetime, plistlib, zipfile, subprocess

version, ipa, url = sys.argv[1:4]
notes = sys.argv[4] if len(sys.argv) > 4 else f"Spitify {version}"
subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__), "check-ios-widgets.py"), ipa], check=True)
path = os.path.join(os.path.dirname(__file__), "..", "ios", "sidestore-source.json")
src = json.load(open(path))
app = src["apps"][0]

with zipfile.ZipFile(ipa) as z:
    info = plistlib.loads(z.read(next(n for n in z.namelist() if n.endswith(".app/Info.plist") and n.count("/") == 2)))

entry = {
    "version": info.get("CFBundleShortVersionString", version),
    "buildVersion": info.get("CFBundleVersion", "1"),
    "date": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "localizedDescription": notes,
    "downloadURL": url,
    "size": os.path.getsize(ipa),
    "minOSVersion": info.get("MinimumOSVersion", "17.0"),
}
app["versions"] = [entry] + [v for v in app["versions"] if v["version"] != entry["version"]]
app["appPermissions"]["privacy"] = {k: v for k, v in info.items() if k.startswith("NS") and k.endswith("UsageDescription")}
json.dump(src, open(path, "w"), indent=2)
print(f"Added {entry['version']} ({entry['size']} bytes)")
