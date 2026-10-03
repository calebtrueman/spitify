#!/usr/bin/env python3
"""Stop a release if the iPhone app is missing its matching widget extension."""
import plistlib
import sys
import subprocess
import tempfile
from pathlib import Path
import zipfile

with zipfile.ZipFile(sys.argv[1]) as ipa:
    app_path = next((name for name in ipa.namelist() if name.endswith('.app/Info.plist') and name.count('/') == 2), None)
    if app_path is None:
        raise SystemExit('The IPA does not contain the iPhone app.')
    root = app_path.removesuffix('Info.plist')
    widget_path = root + 'PlugIns/SpitifyWidgets.appex/Info.plist'
    if widget_path not in ipa.namelist():
        raise SystemExit('Missing Spitify widgets. Do not publish an app-only IPA.')
    app = plistlib.loads(ipa.read(app_path))
    widget = plistlib.loads(ipa.read(widget_path))
    for key in ('CFBundleShortVersionString', 'CFBundleVersion'):
        if app.get(key) != widget.get(key):
            raise SystemExit('The widget and app must have the same release version.')
    if widget.get('NSExtension', {}).get('NSExtensionPointIdentifier') != 'com.apple.widgetkit-extension':
        raise SystemExit('The bundled extension is not an iPhone widget.')
    executable = root + 'PlugIns/SpitifyWidgets.appex/' + widget.get('CFBundleExecutable', '')
    if executable not in ipa.namelist():
        raise SystemExit('The widget executable is missing.')
    with tempfile.TemporaryDirectory(prefix="spitify-widget-check-") as folder:
        for label, binary in [('app', root + app['CFBundleExecutable']), ('widget', executable)]:
            path = Path(folder) / label
            path.write_bytes(ipa.read(binary))
            result = subprocess.run(['codesign', '-d', '--entitlements', ':-', str(path)], capture_output=True)
            try:
                entitlements = plistlib.loads(result.stdout)
            except Exception:
                raise SystemExit(f'The {label} is missing the signed sharing permission. Run the widget signing step before publishing.')
            if 'group.com.calebtrueman.spitify' not in entitlements.get('com.apple.security.application-groups', []):
                raise SystemExit(f'The {label} is missing the widget artwork sharing group.')
    print(f"Widgets included: {app['CFBundleShortVersionString']} ({app['CFBundleVersion']})")
