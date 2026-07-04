#!/usr/bin/env python3
"""원격 갱신용 배포 패키지 생성기 (DESIGN.md 4-6 갱신 전략).

앱 assets 에 내장된 JSON 3종(pokedex_db / usage_db / candidate_index)을
gzip 압축하고 manifest.json(버전 + 파일별 sha256/size)을 만들어 data/dist/ 에 출력한다.
이 dist/ 폴더를 그대로 GitHub Pages / Cloudflare Pages 등 정적 호스팅에 올리면
앱의 DbUpdateManager 가 manifest 를 조회해 신규 버전을 다운로드·검증·교체한다.

서버비 0원: 모든 처리가 정적 파일 서빙 + 온디바이스. 클라우드 연산 없음.

사용:
  python3 build_release.py                 # dataVersion = 오늘 날짜스탬프(YYYYMMDD)
  python3 build_release.py --version 42    # dataVersion 명시(정수/문자열)

출력(data/dist/):
  pokedex_db.json.gz
  usage_db.json.gz
  candidate_index.json.gz
  manifest.json
"""
import argparse
import datetime
import gzip
import hashlib
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
DIST = os.path.join(HERE, "dist")

# 앱 assets 와 동일한 3종. 파일명은 앱 AssetsPokedexLoader 상수와 일치해야 한다.
SOURCES = [
    "pokedex_db.json",
    "usage_db.json",
    "candidate_index.json",
]

# manifest 스키마 버전(앱 파서와 호환성 계약). 구조가 바뀌면 올린다.
MANIFEST_SCHEMA = 1


def sha256_of(data: bytes) -> str:
    h = hashlib.sha256()
    h.update(data)
    return h.hexdigest()


def build(data_version: str) -> dict:
    os.makedirs(DIST, exist_ok=True)
    files = []
    for name in SOURCES:
        src = os.path.join(HERE, name)
        if not os.path.exists(src):
            raise SystemExit(f"소스 없음: {src} (스크래핑 파이프라인을 먼저 실행하세요)")
        with open(src, "rb") as f:
            raw = f.read()
        gz = gzip.compress(raw, compresslevel=9)
        out_name = name + ".gz"
        with open(os.path.join(DIST, out_name), "wb") as f:
            f.write(gz)
        # sha256 은 **압축 해제 후 원본** 기준(앱이 해제 후 검증하므로).
        files.append({
            "name": name,          # 논리 파일명(앱 db/ 디렉터리에 이 이름으로 저장)
            "url": out_name,       # manifest 기준 상대 경로(gzip)
            "sha256": sha256_of(raw),
            "size": len(raw),      # 원본(해제 후) 바이트
            "gzipSize": len(gz),
        })
        print(f"  {name}: {len(raw):>9,}B -> {len(gz):>9,}B gz  sha256={files[-1]['sha256'][:12]}…")

    manifest = {
        "manifestSchema": MANIFEST_SCHEMA,
        "dataVersion": data_version,
        "generatedAt": datetime.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ"),
        "files": files,
    }
    with open(os.path.join(DIST, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    return manifest


def main():
    ap = argparse.ArgumentParser(description="원격 갱신 배포 패키지 생성(dist/)")
    ap.add_argument(
        "--version",
        default=None,
        help="dataVersion(정수 또는 문자열). 미지정 시 오늘 날짜스탬프 YYYYMMDD.",
    )
    args = ap.parse_args()

    data_version = args.version or datetime.date.today().strftime("%Y%m%d")
    print(f"[build_release] dataVersion={data_version} -> {DIST}")
    manifest = build(data_version)
    print(f"[build_release] 완료: {len(manifest['files'])}개 파일 + manifest.json")
    print("  이 폴더(data/dist/)를 GitHub/Cloudflare Pages 로 호스팅하세요. (README 참조)")


if __name__ == "__main__":
    main()
