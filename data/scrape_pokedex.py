#!/usr/bin/env python3
"""포챔스 포켓덱스 다국어 DB 생성기 (en/ko/ja).

소스에서 포켓몬 뼈대(도감번호·타입·특성·종족값·기술풀)와 다국어 이름,
그리고 타입/특성/기술 로컬라이제이션 사전을 수집해 pokedex_db.json 으로 정규화한다.

설계: 소스 어댑터(fetch/parse)를 분리 → 나중에 다른 소스로 교체 가능.
현재 어댑터: OpggAdapter. 데이터는 자주 바뀌지 않으므로 스냅샷(1회 수집) 방식.
"""
import json, os, re, ssl, time, urllib.request

_SSL = ssl._create_unverified_context()  # 프록시 자체서명 체인 우회 (스냅샷 수집용)

# 포챔스 공식 지원 언어(10옵션, 스페인어 2종은 종족명 동일 → 9 고유 로케일).
# op.gg 로케일 코드 기준. 나중에 언어 추가/제거는 이 리스트만 수정.
LANGS = ["en", "ja", "ko", "de", "es", "fr", "it", "zh-cn", "zh-tw"]
OUT = os.path.join(os.path.dirname(__file__), "pokedex_db.json")

STAT_MAP = {"hp": "hp", "attack": "atk", "defense": "def",
            "spAttack": "spa", "spDefense": "spd", "speed": "spe"}

# 메가 key → base key 가 규칙으로 안 풀리는 예외
MEGA_BASE_OVERRIDES = {"mega-floette": "floette-eternal-flower",
                       "mega-meowstic": "meowstic-male"}


def link_megas(pokemon):
    """base ↔ 메가 상호 링크. 오버레이 '메가진화' 토글의 데이터 토대."""
    by = {p["key"]: p for p in pokemon}
    for p in pokemon:
        k = p["key"]
        if not k.startswith("mega-"):
            continue
        b = MEGA_BASE_OVERRIDES.get(k) or re.sub(r"-(x|y)$", "", k[len("mega-"):])
        if b in by:
            p["is_mega"] = True
            p["base_key"] = b
            by[b].setdefault("mega_keys", []).append(k)
    for p in pokemon:
        if not p["key"].startswith("mega-"):
            p["can_mega"] = "mega_keys" in p
    return pokemon


# --- RSC(React Flight) 디코딩 유틸 -------------------------------------------
def _scan(s, start, open_ch, close_ch):
    depth = 0; instr = False; esc = False; i = start
    while i < len(s):
        c = s[i]
        if esc: esc = False
        elif c == "\\": esc = True
        elif c == '"': instr = not instr
        elif not instr:
            if c == open_ch: depth += 1
            elif c == close_ch:
                depth -= 1
                if depth == 0: return s[start:i + 1], i + 1
        i += 1
    return s[start:i], i


def decode_rsc(html):
    chunks = []; i = 0; marker = "self.__next_f.push("
    while True:
        j = html.find(marker, i)
        if j < 0: break
        arr, end = _scan(html, j + len(marker), "[", "]"); i = end
        try:
            v = json.loads(arr)
            if isinstance(v, list) and len(v) >= 2 and isinstance(v[1], str):
                chunks.append(v[1])
        except Exception:
            pass
    return "".join(chunks)


# --- op.gg 어댑터 ------------------------------------------------------------
class OpggAdapter:
    name = "opgg"

    def _url(self, lang, page):
        prefix = "" if lang == "en" else f"/{lang}"  # en 은 루트, 나머지 /{lang}/
        return f"https://op.gg{prefix}/pokemon-champions/{page}"

    def _get(self, url):
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=30, context=_SSL) as r:
            return r.read().decode("utf-8", "replace")

    def parse(self, lang):
        # 포켓덱스 페이지: 포켓몬 + 타입/특성 (+부분 기술)
        blob = decode_rsc(self._get(self._url(lang, "pokedex")))
        out = {"pokemon": self._pokemon(blob), "types": {}, "abilities": {},
               "moves": {}, **self._entries(blob)}
        # 기술 페이지: 전체 기술 사전(920개)으로 교체 (movepool 100% 커버)
        mblob = decode_rsc(self._get(self._url(lang, "moves")))
        out["moves"] = self._entries(mblob)["moves"]
        return out

    def _pokemon(self, blob):
        best = None; idx = 0
        while True:
            j = blob.find('[{"id":', idx)
            if j < 0: break
            arr, end = _scan(blob, j, "[", "]"); idx = end
            try:
                v = json.loads(arr)
            except Exception:
                continue
            if (isinstance(v, list) and v and isinstance(v[0], dict)
                    and "stats" in v[0] and "moves" in v[0]):
                if best is None or len(v) > len(best):
                    best = v
        out = {}
        for p in (best or []):
            st = p.get("stats", {})
            out[p["key"]] = {
                "dex": p.get("id"),
                "key": p.get("key"),
                "name": p.get("name"),
                "generation": p.get("generation"),
                "types": p.get("types", []),
                "abilities": p.get("abilities", []),
                "base_stats": {STAT_MAP[k]: st.get(k) for k in STAT_MAP if k in st},
                "moves": p.get("moves", []),
            }
        return out

    def _entries(self, blob):
        types, abilities, moves = {}, {}, {}
        pat = re.compile(r'\{"id":\d+,"key":"[a-z0-9-]+","name":"')
        for m in pat.finditer(blob):
            obj, _ = _scan(blob, m.start(), "{", "}")
            try:
                o = json.loads(obj)
            except Exception:
                continue
            if "stats" in o or "generation" in o:
                continue  # 포켓몬 레코드
            slug, nm = o.get("key"), o.get("name")
            if not slug:
                continue
            if "color" in o:
                types[slug] = {"name": nm, "color": o.get("color")}
            elif "category" in o and "power" in o:
                moves[slug] = {"name": nm, "type": o.get("type"),
                               "category": o.get("category"), "power": o.get("power"),
                               "pp": o.get("pp"), "accuracy": o.get("accuracy")}
            elif "description" in o:
                abilities[slug] = {"name": nm}
        return {"types": types, "abilities": abilities, "moves": moves}


# --- 병합: 구조는 en 기준, 이름은 언어별로 ----------------------------------
def merge(per_lang):
    base = per_lang["en"]
    pokemon = []
    for key, rec in sorted(base["pokemon"].items(), key=lambda kv: (kv[1]["dex"] or 0, kv[0])):
        names = {}
        for l in LANGS:
            r = per_lang[l]["pokemon"].get(key)
            names[l] = (r or rec).get("name")
        pokemon.append({
            "dex": rec["dex"], "key": key, "generation": rec["generation"],
            "names": names, "types": rec["types"], "abilities": rec["abilities"],
            "base_stats": rec["base_stats"], "moves": rec["moves"],
        })

    def loc(cat, extra):
        d = {}
        for slug, info in base[cat].items():
            names = {l: (per_lang[l][cat].get(slug) or info).get("name") for l in LANGS}
            d[slug] = {"names": names, **{k: info.get(k) for k in extra}}
        return d

    link_megas(pokemon)

    return {
        "schema_version": 1,
        "languages": LANGS,
        "count": len(pokemon),
        "pokemon": pokemon,
        "dict": {
            "types": loc("types", ["color"]),
            "abilities": loc("abilities", []),
            "moves": loc("moves", ["type", "category", "power", "pp", "accuracy"]),
        },
    }


def main():
    adapter = OpggAdapter()
    per_lang = {}
    for l in LANGS:
        print(f"[{l}] fetch...", end=" ", flush=True)
        per_lang[l] = adapter.parse(l)
        p = per_lang[l]
        print(f"pokemon={len(p['pokemon'])} types={len(p['types'])} "
              f"abilities={len(p['abilities'])} moves={len(p['moves'])}")
        time.sleep(0.5)

    db = merge(per_lang)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=1)
    kb = os.path.getsize(OUT) / 1024
    print(f"\n생성: {OUT}")
    print(f"  포켓몬 {db['count']}종 · 타입 {len(db['dict']['types'])} · "
          f"특성 {len(db['dict']['abilities'])} · 기술 {len(db['dict']['moves'])} · {kb:.0f} KB")


if __name__ == "__main__":
    main()
