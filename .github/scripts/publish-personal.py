#!/usr/bin/env python3
"""Publish a personal single-extension Mihon repo index (index.pb + index.json).

Adapted from keiyoushi/extensions-source `.github/scripts/publish-repo.py`
(Apache-2.0), simplified for one extension whose APK/JAR are distributed as
assets of a GitHub Release in this same repository.

Inputs (CLI args):
  --build-dir   Gradle build dir of the extension module; must contain a
                keiyoushi-source-info.json plus outputs/apk/release/*.apk and
                outputs/jar/release/*.jar (same layout the upstream script uses).
  --release-tag Release tag the assets were uploaded under, e.g. ext-v86.
  --repo        GitHub repo slug, e.g. sabilmangu221/shinigami-ext.
  --out         Output dir that becomes the content of the `repo` branch.

The signing key fingerprint below MUST match the certificate of
signingkey.jks used to sign the APK/JAR, or Mihon rejects the repo.
"""
import argparse
import gzip
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import index_pb2  # noqa: E402
from google.protobuf import json_format  # noqa: E402

SIGNING_KEY_SHA256 = "23c10063b1946693a2121a9e57578a97aad294699810f0b6567ad6562d3feefd"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--build-dir", required=True)
    ap.add_argument("--release-tag", required=True)
    ap.add_argument("--repo", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    build_dir = Path(args.build_dir)
    info_files = list(build_dir.glob("**/keiyoushi-source-info.json"))
    if not info_files:
        raise SystemExit(f"keiyoushi-source-info.json not found under {build_dir}")
    info_path = info_files[0]
    info = json.loads(info_path.read_text(encoding="utf-8"))

    root = info_path.parent
    apk = next((root / "outputs/apk/release").glob("*.apk"), None)
    jar = next((root / "outputs/jar/release").glob("*.jar"), None)
    if apk is None or jar is None:
        raise SystemExit(f"release apk/jar not found under {root / 'outputs'}")

    base = f"https://github.com/{args.repo}/releases/download/{args.release_tag}"
    icon_url = (
        f"https://cdn.jsdelivr.net/gh/{args.repo}@main"
        "/src/id/shinigami/res/mipmap-xhdpi/ic_launcher.png"
    )

    ext = index_pb2.Extension(
        name=info["name"],
        packageName=info["packageName"],
        resources=index_pb2.Resources(
            apkUrl=f"{base}/{apk.name}",
            iconUrl=icon_url,
            jarUrl=f"{base}/{jar.name}",
        ),
        extensionLib=str(info["extensionLib"]),
        versionCode=int(info["versionCode"]),
        versionName=str(info["versionName"]),
        contentWarning=info["contentWarning"],
        sources=[
            index_pb2.Source(
                id=int(s["id"]),
                name=s["name"],
                language=s["lang"],
                homeUrl=s["baseUrl"],
                mirrorUrls=s.get("mirrorUrls", []),
            )
            for s in info["sources"]
        ],
    )

    index = index_pb2.Index(
        name="Shinigami Personal",
        badgeLabel="SGM",
        signingKey=SIGNING_KEY_SHA256,
        contact=index_pb2.Contact(website=f"https://github.com/{args.repo}"),
        extensionList=index_pb2.ExtensionList(extensions=[ext]),
    )

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "index.json").write_text(
        json_format.MessageToJson(
            index,
            always_print_fields_with_no_presence=False,
            preserving_proto_field_name=True,
        ),
        encoding="utf-8",
    )
    with (out / "index.pb").open("wb") as f:
        f.write(gzip.compress(index.SerializeToString(deterministic=True), mtime=0))

    print(f"Wrote index for {info['packageName']} v{info['versionName']} ({info['versionCode']})")
    print(f"  apk: {base}/{apk.name}")
    print(f"  jar: {base}/{jar.name}")


if __name__ == "__main__":
    main()
