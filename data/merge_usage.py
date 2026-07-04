#!/usr/bin/env python3
"""포챔스 사용률 DB 생성기 (championsbattledata).

싱글/더블 각각의 실사용률(기술/아이템/특성/성격/EV/파트너)을 수집해
usage_db.json 으로 저장한다. pokedex_db.json(op.gg) 과 slug/key 로 조인.

기술/특성/파트너는 pokedex_db 의 영문명으로 slug/key 를 역매핑해 붙여
오버레이가 다국어 사전으로 현지화할 수 있게 한다.
소스 교체 시 이 파일만 갈아끼우면 된다 (pokedex_db 는 그대로).
"""
import json, os, re, ssl, urllib.request
from concurrent.futures import ThreadPoolExecutor

_SSL = ssl._create_unverified_context()
BASE = "https://championsbattledata.com"
HERE = os.path.dirname(__file__)
POKEDEX = os.path.join(HERE, "pokedex_db.json")
OUT = os.path.join(HERE, "usage_db.json")
FORMATS = {"doubles": "Doubles", "singles": "Singles"}

# cbd category → 우리 필드명
CAT = {"move": "moves", "held_item": "items", "ability": "abilities",
       "stat_alignment": "natures", "teammate": "teammates", "stat_points": "spreads"}

# 폼 명명 불규칙 수동 매핑 (cbd slug → 우리 key)
OVERRIDES = {
    "aegislash-shield-forme": "aegislash",
    "florges-red-flower": "florges",
    "furfrou-natural-form": "furfrou",
    "gourgeist": "gourgeist-average",
    "gourgeist-jumbo-variety": "gourgeist-super",
    "maushold": "maushold-family-of-four",
    "meowstic": "meowstic-male",
    "mr-rime": "mr.-rime",
    "palafin-zero-form": "palafin",
    "paldean-tauros-aqua-breed": "tauros-paldean-aqua",
    "paldean-tauros-blaze-breed": "tauros-paldean-blaze",
    "paldean-tauros-combat-breed": "tauros-paldean-combat",
    "vivillon-fancy-pattern": "vivillon",
    # "floette": 우리 포켓덱스에 base floette 없음 → skip
}


def get(path):
    req = urllib.request.Request(BASE + path, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=30, context=_SSL) as r:
        return json.load(r)


def norm(s):
    for pre, suf in [("alolan-", "-alolan"), ("galarian-", "-galarian"),
                     ("hisuian-", "-hisui")]:
        if s.startswith(pre):
            s = s[len(pre):] + suf
    return s.replace("-variety", "").replace("-forme", "").replace("-form", "")


def resolve(slug, our_keys):
    if slug in our_keys: return slug
    if slug in OVERRIDES: return OVERRIDES[slug]
    n = norm(slug)
    return n if n in our_keys else None


def pct(v):
    if not v: return None
    m = re.match(r"([0-9.]+)", str(v))
    return float(m.group(1)) if m else None


def norm_name(s):
    return (s or "").lower().replace("’", "'")  # 곡선 아포스트로피 정규화


def main():
    pdx = json.load(open(POKEDEX, encoding="utf-8"))
    our_keys = {p["key"] for p in pdx["pokemon"]}
    mv_en = {norm_name(i["names"]["en"]): s for s, i in pdx["dict"]["moves"].items()}
    ab_en = {norm_name(i["names"]["en"]): s for s, i in pdx["dict"]["abilities"].items()}
    pk_en = {norm_name(p["names"]["en"]): p["key"] for p in pdx["pokemon"]}

    idx = get("/api/index")
    entries = idx["pokemon"]  # 235종, battleDataCsvs 보유
    print(f"cbd 사용률 보유: {len(entries)}종")

    # slug → key 매핑
    slug2key, unresolved = {}, []
    for e in entries:
        k = resolve(e["slug"], our_keys)
        (slug2key.__setitem__(e["slug"], k) if k else unresolved.append(e["slug"]))
    print(f"조인 성공: {len(slug2key)} · 미해결(사용률 스킵): {len(unresolved)} {unresolved}")

    season = idx.get("defaultSeason")

    def fetch_one(slug, fmt_key, fmt_api):
        try:
            d = get(f"/api/battle/{fmt_api}/{slug}")
        except Exception as ex:
            return slug, fmt_key, None
        buckets = {v: [] for v in CAT.values()}
        for r in d.get("rows", []):
            field = CAT.get(r.get("category"))
            if not field: continue
            name = r.get("name")
            item = {"name": name, "pct": pct(r.get("percentage"))}
            low = norm_name(name)
            if field == "moves" and low in mv_en: item["slug"] = mv_en[low]
            elif field == "abilities" and low in ab_en: item["slug"] = ab_en[low]
            elif field == "teammates" and low in pk_en: item["key"] = pk_en[low]
            buckets[field].append(item)
        if d.get("season"): buckets["_season"] = d["season"]
        return slug, fmt_key, buckets

    tasks = [(s, fk, fa) for s in slug2key for fk, fa in FORMATS.items()]
    usage = {}
    with ThreadPoolExecutor(max_workers=8) as ex:
        for slug, fmt_key, buckets in ex.map(lambda t: fetch_one(*t), tasks):
            if buckets is None: continue
            key = slug2key[slug]
            usage.setdefault(key, {})[fmt_key] = {
                k: v for k, v in buckets.items() if not k.startswith("_")
            }

    out = {
        "schema_version": 1,
        "source_format": "championsbattledata",
        "season": season,
        "formats": list(FORMATS.keys()),
        "count": len(usage),
        "usage": usage,
    }
    json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    kb = os.path.getsize(OUT) / 1024
    print(f"\n생성: {OUT}\n  {len(usage)}종 사용률(싱글+더블) · {kb:.0f} KB")

    # 샘플 검증
    g = usage.get("garchomp", {}).get("doubles", {})
    print("  garchomp 더블 기술:", [(m["name"], m["pct"], m.get("slug"))
                                   for m in g.get("moves", [])[:4]])


if __name__ == "__main__":
    main()
