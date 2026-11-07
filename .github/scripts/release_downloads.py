import sys
from pathlib import Path

folder, version, repository = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
download = f"https://github.com/{repository}/releases/download/v{version}"
name = f"Mobitool-{version}"


# a platform whose build failed has no file here and leaves its cell empty
def files(*entries):
    return " &nbsp; ".join(f"[{label}]({download}/{file})" for label, file in entries if (folder / file).is_file())


android = files(("APK", f"{name}.apk"))
header = ["Architecture", "Windows", "Linux", "macOS", "Android", "iOS", "Web"]
rows = [
    [
        "x86-64 (64-bit)",
        files(("EXE", f"{name}-windows-setup.exe"), ("MSI", f"{name}-windows.msi")),
        files(("DEB", f"{name}-linux-amd64.deb"), ("RPM", f"{name}-linux-x86_64.rpm")),
        "",
        android,
        "",
        "[Open](https://vasmarfas.com/#tools)",
    ],
    ["AArch64 (ARM64)", "", "", files(("DMG", f"{name}-macos-arm64.dmg")), android, "", ""],
    [
        "Stores",
        "[Microsoft Store](https://apps.microsoft.com/detail/9MWHC2SBXFV1)",
        "",
        "[Mac App Store](https://apps.apple.com/app/id6816425850?platform=mac)",
        "[Google Play](https://play.google.com/store/apps/details?id=com.vasmarfas.mobitool) &nbsp; "
        "[RuStore](https://www.rustore.ru/catalog/app/com.vasmarfas.mobitool)",
        "[App Store](https://apps.apple.com/app/id6816425850)",
        "",
    ],
]
print("| " + " | ".join(header) + " |")
print("|" + "|".join("---" for _ in header) + "|")
for row in rows:
    if any(row[1:]):
        print("| " + " | ".join(row) + " |")
