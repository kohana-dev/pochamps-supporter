#!/usr/bin/env python3
"""표시명 → 후보 매칭 인덱스 생성기.

배틀 화면의 표시명이 폼 정보를 생략하는 경우가 많고(예: 아르칸인 히스이폼도
화면엔 "Arcanine"), 어떤 이름 문자열이 실제로 뜨는지 소스마다 다르다
(라이츄 알로라폼은 "Alolan Raichu"로 구분 표시 vs 아르칸인 히스이폼은 base와
동일 문자열). 그래서 "종족 루트"로 후보를 묶고, 후보들의 모든 이름 변형을
전부 인덱싱해 어느 쪽으로 뜨든 잡아낸다. 메가는 별도 토글(mega_keys)로 이미
처리되므로 이 인덱스에서는 제외 — 이름 충돌 후보가 아니라 "진화 버튼" 대상.

출력: candidate_index.json
  species[]: {root, candidates:[key,...]}
  lookup[lang][normalized_name] -> root   (OCR fuzzy 매칭 1차 조회용)
"""
import json, os, re

HERE = os.path.dirname(__file__)
POKEDEX = os.path.join(HERE, "pokedex_db.json")
USAGE = os.path.join(HERE, "usage_db.json")
OUT = os.path.join(HERE, "candidate_index.json")

REGIONAL_SUFFIX = re.compile(r"-(alolan|galarian|hisui|paldean)$")


def species_root(key):
    """메가/리전폼 접미사를 뗀 species 루트. 그 외 폼(로토무·가디안 등)은 자기 자신이 루트."""
    if key.startswith("mega-"):
        return None  # 메가는 후보 그룹 대상 아님(토글로 처리)
    return REGIONAL_SUFFIX.sub("", key)


def normalize(s):
    return re.sub(r"[\s\-.'’]", "", (s or "")).lower()


def usage_rank(usage_db, key, fmt="doubles"):
    """해당 포켓몬의 더블 사용 빈도 프록시: 최고 사용률 기술의 pct. 없으면 0."""
    u = usage_db.get("usage", {}).get(key, {}).get(fmt)
    if not u or not u.get("moves"):
        return 0.0
    return max((m.get("pct") or 0) for m in u["moves"])


def main():
    pdx = json.load(open(POKEDEX, encoding="utf-8"))
    usage_db = json.load(open(USAGE, encoding="utf-8")) if os.path.exists(USAGE) else {"usage": {}}
    langs = pdx["languages"]

    groups = {}
    for p in pdx["pokemon"]:
        root = species_root(p["key"])
        if root is None:
            continue
        groups.setdefault(root, []).append(p["key"])

    species = []
    lookup = {l: {} for l in langs}
    by_key = {p["key"]: p for p in pdx["pokemon"]}

    for root, keys in sorted(groups.items()):
        ranked = sorted(keys, key=lambda k: -usage_rank(usage_db, k))
        species.append({
            "root": root,
            "candidates": [
                {"key": k, "usage_rank": usage_rank(usage_db, k),
                 "types": by_key[k]["types"], "names": by_key[k]["names"]}
                for k in ranked
            ],
        })
        # 후보들의 "모든" 언어별 이름 변형을 인덱싱 (어느 쪽 문자열이 뜨든 매칭되게)
        for k in keys:
            for l in langs:
                nm = normalize(by_key[k]["names"][l])
                if nm:
                    lookup[l].setdefault(nm, root)

    out = {
        "schema_version": 1,
        "note": "종별 표시명 충돌 그룹. 메가는 제외(별도 토글 처리). "
                "lookup 은 OCR 정규화 문자열 → species root.",
        "species_count": len(species),
        "collision_count": sum(1 for s in species if len(s["candidates"]) > 1),
        "species": species,
        "lookup": lookup,
    }
    json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    kb = os.path.getsize(OUT) / 1024
    print(f"생성: {OUT}")
    print(f"  종 그룹 {out['species_count']} · 충돌(후보 2+) {out['collision_count']} · {kb:.0f} KB")

    # 검증 샘플
    arc = next(s for s in species if s["root"] == "arcanine")
    print("\n  arcanine 그룹 후보(사용률순):",
          [(c["key"], c["usage_rank"], c["types"]) for c in arc["candidates"]])
    print("  ko lookup '아르칸인' ->", lookup["ko"].get(normalize("아르칸인")))


if __name__ == "__main__":
    main()
