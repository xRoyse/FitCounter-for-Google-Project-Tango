"""Получает исходники тестов локально и проверяет SHA-256."""

import argparse
import hashlib
from pathlib import Path
from urllib.request import Request, urlopen
import zipfile


COMMONS = "https://thumb.wikimedia.org/wikipedia/commons/thumb/"
SOURCES = {
    "517502.mp4": (
        "https://d34w7g4gy10iej.cloudfront.net/video/1704/DOD_104245094/DOD_104245094-1024x576-1769k.mp4",
        "05b5870da6ebc66abcfd5715a2864e21299d176dc2c87524d3f5904c37450f66",
    ),
    "empty-room.jpg": (
        COMMONS + "5/5a/Empty_apartment_room_with_extension_cord.jpg/960px-Empty_apartment_room_with_extension_cord.jpg",
        "46eae175c145c769f1de46ac1ccd14d081806ac5bec16bddf5e9a1beccebce79",
    ),
    "furniture.jpg": (
        COMMONS + "3/32/Furnitures_in_the_sunlight_%28Unsplash%29.jpg/960px-Furnitures_in_the_sunlight_%28Unsplash%29.jpg",
        "d012012da58e3c111f0950dcc3984bfd988804782238602cc8fb9ddf6c8019ac",
    ),
    "tile-holdout.jpg": (
        COMMONS + "4/45/Tesselated_tile_floor_pattern.jpg/960px-Tesselated_tile_floor_pattern.jpg",
        "d745041d229d24d8173de3e0225a09ff13046d1cd77863a9ffb694c0a0b8c7a0",
    ),
}

# Dataset Exercise-wise.zip, версия 3, DOI 10.17632/kgbb3yn47p.3.
ARCHIVE_URL = ("https://prod-dcd-datasets-public-files-eu-west-1.s3.eu-west-1.amazonaws.com/"
               "17882996-ee32-4d0c-b53b-bcaa7a5f8bb3")
ARCHIVE_SIZE = 3_113_452_092
PUSH_UP = "subject_002_push_up_good_side.mp4"
PUSH_UP_SHA256 = "2f4780fbbe7c628051043d3cbc36b0b13c1ab29996d7c69fd9f90180c66181fa"


def download(url, headers=None):
    request = Request(url, headers={"User-Agent": "FitCounter-local-media-test/1.0", **(headers or {})})
    with urlopen(request, timeout=45) as response:
        if headers and response.status != 206:
            raise OSError("Сервер не поддерживает чтение диапазона ZIP")
        return response.read()


class ArchiveRanges:
    """Интерфейс чтения ZIP, который скачивает только каталог и выбранный ролик."""
    def __init__(self):
        self.offset = 0

    def seekable(self):
        return True

    def tell(self):
        return self.offset

    def seek(self, offset, whence=0):
        self.offset = (0, self.offset, ARCHIVE_SIZE)[whence] + offset
        return self.offset

    def read(self, size=-1):
        end = ARCHIVE_SIZE if size < 0 else min(ARCHIVE_SIZE, self.offset + size)
        if end <= self.offset:
            return b""
        data = download(ARCHIVE_URL, {"Range": f"bytes={self.offset}-{end - 1}"})
        if len(data) != end - self.offset:
            raise OSError("Неполный диапазон ZIP")
        self.offset = end
        return data


def ensure(path, expected, fetch):
    if path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() == expected:
        return
    data = fetch()
    if hashlib.sha256(data).hexdigest() != expected:
        raise OSError(f"SHA-256 не совпадает для {path.name}")
    path.write_bytes(data)


def push_up():
    with zipfile.ZipFile(ArchiveRanges()) as archive:
        return archive.read("Dataset Exercise-wise/push_up/good/side/" + PUSH_UP)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, nargs="?", default=Path("build/pose-media"))
    directory = parser.parse_args().directory
    directory.mkdir(parents=True, exist_ok=True)
    ensure(directory / PUSH_UP, PUSH_UP_SHA256, push_up)
    for name, (url, sha256) in SOURCES.items():
        ensure(directory / name, sha256, lambda: download(url))


if __name__ == "__main__":
    main()
