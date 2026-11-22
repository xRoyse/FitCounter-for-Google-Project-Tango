"""Декодирование открытых тестовых материалов и запуск модели из Android APK."""

import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import uuid
import zipfile


def run(*args, capture=False):
    return subprocess.run(args, check=True, capture_output=capture).stdout


def decode(ffmpeg, fixture, media_dir, destination):
    source = media_dir / fixture["file"] if "file" in fixture else None
    if "repoFile" in fixture:
        source = Path(__file__).resolve().parents[1] / fixture["repoFile"]
    if source is None:
        inputs = ["-f", "lavfi", "-i", fixture["lavfi"]]
    elif fixture.get("image"):
        inputs = ["-loop", "1", "-i", str(source)]
    else:
        inputs = ["-ss", str(fixture.get("start", 0)), "-i", str(source)]
    width, height, fps = (fixture[key] for key in ("width", "height", "fps"))
    filters = fixture.get("filter", [])
    filters = [*filters, f"fps={fps}", f"scale={width}:{height}:force_original_aspect_ratio=decrease",
               f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2,setsar=1"]
    run(ffmpeg, "-nostdin", "-v", "error", *inputs, "-t", str(fixture["duration"]),
        "-an", "-vf", ",".join(filters), "-pix_fmt", "nv21", "-f", "rawvideo", str(destination))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--media-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("build/pose-media/results"))
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--fixture", action="append", help="ID сценария. Можно указать несколько раз.")
    args = parser.parse_args()
    adb = [args.adb, *(["-s", args.serial] if args.serial else [])]
    fixtures = json.loads(args.manifest.read_text(encoding="utf-8"))
    if args.fixture:
        fixtures = [fixture for fixture in fixtures if fixture["id"] in args.fixture]
        if len(fixtures) != len(set(args.fixture)):
            parser.error("Неизвестный ID сценария")
    if not fixtures or any(not item["id"].replace("-", "").replace("_", "").isalnum()
                           for item in fixtures):
        parser.error("Пустая разметка или недопустимый ID")
    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as temporary:
        archive = Path(temporary) / "media.zip"
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=1) as bundle:
            bundle.writestr("manifest.json", json.dumps(fixtures))
            for fixture in fixtures:
                raw = Path(temporary) / (fixture["id"] + ".nv21")
                decode(args.ffmpeg, fixture, args.media_dir, raw)
                bundle.write(raw, raw.name)
                raw.unlink()

        remote = "/data/local/tmp/fitcounter-media-" + uuid.uuid4().hex + ".zip"
        output_names = ["pose-media-report.json", *(f"pose-{item['id']}.jsonl" for item in fixtures)]
        try:
            run(*adb, "push", str(archive), remote)
            run(*adb, "shell", "chmod", "644", remote)
            run(*adb, "shell", "run-as", "com.example.fitcounter", "rm", "-f",
                *("files/" + name for name in output_names))
            output = run(*adb, "shell", "am", "instrument", "-w", "-e", "class",
                         "com.example.fitcounter.PoseMediaTest", "-e", "media", remote,
                         "com.example.fitcounter.test/androidx.test.runner.AndroidJUnitRunner",
                         capture=True).decode("utf-8", errors="replace")
            print(output)
            for name in output_names:
                with (args.output / name).open("wb") as file:
                    subprocess.run([*adb, "shell", "run-as", "com.example.fitcounter",
                                    "cat", "files/" + name], stdout=file, check=True)
            report = json.loads((args.output / output_names[0]).read_text(encoding="utf-8"))
            if "OK (1 test)" not in output or report["errors"]:
                raise SystemExit(1)
        finally:
            run(*adb, "shell", "rm", "-f", remote)


if __name__ == "__main__":
    main()
