#!/usr/bin/env python3
"""Build the data file used by the PvP search-string app.

Sources (all fetched from GitHub, never from pvpoke.com):
  * PvPoke gamemaster.json + overall rankings (MIT licence) — species list, base stats,
    evolution families, release flags, PvP rankings.
  * PokeMiners Game Master — CP multiplier table, legendary/mythical/ultra-beast class,
    and a cross-check of PvPoke's base stats.

Both repos are pinned to a commit SHA, so every run uses one consistent snapshot. If
neither SHA changed since the last published manifest the script exits without
downloading anything else (use --force to rebuild anyway).

Output (only rewritten when the content changes):
  <out>/v1/data.json      everything the app needs
  <out>/v1/manifest.json  small file the app polls: hashes, sources, counts, warnings

Exit codes: 0 = ok (published or unchanged), 1 = sanity check failed (nothing written).
Standard library only; works on Python 3.8+.
"""
import argparse
import datetime
import hashlib
import json
import math
import os
import re
import struct
import sys
import urllib.request

SCHEMA_VERSION = 1
PIPELINE_VERSION = 2  # bump when the output changes for the same inputs

PVPOKE_REPO = "pvpoke/pvpoke"
POKEMINERS_REPO = "PokeMiners/game_masters"
LEAGUES = {"little": 500, "great": 1500, "ultra": 2500, "master": 10000}
MAX_LEVEL = 51  # best buddy; the table is published up to here
GM_CLASS_TAG = {
    "POKEMON_CLASS_LEGENDARY": "legendary",
    "POKEMON_CLASS_MYTHIC": "mythical",
    "POKEMON_CLASS_ULTRA_BEAST": "ultrabeast",
}
# PvPoke tags passed through to the app (the rest are PvPoke-internal).
KEEP_TAGS = {"legendary", "mythical", "ultrabeast", "shadoweligible", "regional",
             "alolan", "galarian", "hisuian", "paldean", "untradeable", "starter",
             "wildlegendary"}

warnings = []


def warn(msg):
    if msg in warnings:
        return
    warnings.append(msg)
    print("WARNING:", msg, file=sys.stderr)
    if os.environ.get("GITHUB_ACTIONS"):
        print("::warning::" + msg.replace("\n", " "))


def fetch(url, api=False):
    headers = {"User-Agent": "pvp-search-data-pipeline"}
    token = os.environ.get("GITHUB_TOKEN")
    if api and token:
        headers["Authorization"] = "Bearer " + token
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=120) as r:
        return r.read()


def latest_commit(repo, path):
    data = json.loads(fetch("https://api.github.com/repos/%s/commits?path=%s&per_page=1" % (repo, path), api=True))
    return {"sha": data[0]["sha"], "date": data[0]["commit"]["committer"]["date"]}


def raw(repo, sha, path):
    return fetch("https://raw.githubusercontent.com/%s/%s/%s" % (repo, sha, path))


def f32(x):
    """The Game Master prints float32 values rounded; the game uses the exact float32."""
    return struct.unpack("f", struct.pack("f", x))[0]


def build_cpm(gm):
    ints = next(t for t in gm if t["templateId"] == "PLAYER_LEVEL_SETTINGS")["data"]["playerLevel"]["cpMultiplier"]
    ints = [f32(v) for v in ints[:MAX_LEVEL]]
    out = []
    for i, lo in enumerate(ints):
        out.append(lo)
        if i + 1 < len(ints):
            hi = ints[i + 1]
            out.append(math.sqrt((lo * lo + hi * hi) / 2))
    return out  # index k = level 1 + k/2


def check_cpm_against_pvpoke(cpm, pokemon_js):
    m = re.search(r"var cpms = \[(.*?)\]", pokemon_js)
    if not m:
        warn("could not find PvPoke's CPM table to cross-check")
        return
    theirs = [float(x) for x in m.group(1).split(",")]
    worst = max(abs(a - b) / b for a, b in zip(cpm, theirs))
    if worst > 1e-9:
        warn("CPM table differs from PvPoke's (max relative diff %.3g)" % worst)


def gm_index(gm):
    """From the Game Master: dex -> class tag, the set of (dex, atk, def, hp), and the set of
    (dex, dex) evolution pairs."""
    klass, stats, dex_of, branches = {}, set(), {}, []
    for t in gm:
        m = re.match(r"V(\d{4})_POKEMON_", t["templateId"])
        p = t["data"].get("pokemonSettings")
        if not m or not p:
            continue
        dex = int(m.group(1))
        dex_of[p["pokemonId"]] = dex
        if p.get("pokemonClass") in GM_CLASS_TAG:
            klass[dex] = GM_CLASS_TAG[p["pokemonClass"]]
        s = p.get("stats") or {}
        if "baseAttack" in s:
            stats.add((dex, s["baseAttack"], s["baseDefense"], s["baseStamina"]))
        for b in p.get("evolutionBranch") or []:
            if b.get("evolution"):
                branches.append((dex, b["evolution"]))
    evo_pairs = {(d, dex_of[e]) for d, e in branches if e in dex_of}
    return klass, stats, evo_pairs


def build_species(pvp, klass, gm_stats, gm_evo):
    shadow_ids = {p["speciesId"] for p in pvp["pokemon"] if "shadow" in (p.get("tags") or [])}
    keep = []
    for p in pvp["pokemon"]:
        tags = set(p.get("tags") or [])
        if tags & {"shadow", "mega", "duplicate"}:
            continue
        keep.append(p)
    ids = {p["speciesId"] for p in keep}
    by_id = {p["speciesId"]: p for p in keep}
    # PvPoke's "evolutions" lists miss many regional forms (Quilava -> Hisuian Typhlosion); the
    # child's "parent" link has them. Use both, keeping a link only if the Game Master has an
    # evolution between those dex numbers (drops errors like Carkol's parent "boltund").
    # Missing a pre-evolution hides Pokémon from the search; an extra one only adds a hit.
    links = []
    for p in keep:
        for e in (p.get("family") or {}).get("evolutions") or []:
            links.append((p["speciesId"], e))
        par = (p.get("family") or {}).get("parent")
        if par:
            links.append((par, p["speciesId"]))
    children = {}
    for par, child in links:
        if par not in by_id or child not in by_id:
            if not par.endswith("_shadow") and not child.endswith("_shadow") and (par in by_id or child in by_id):
                warn("evolution %s -> %s refers to an unknown species (dropped)" % (par, child))
            continue
        pd, cd = by_id[par]["dex"], by_id[child]["dex"]
        if pd != cd and (pd, cd) not in gm_evo:
            warn("evolution %s -> %s is not in the Game Master (dropped)" % (par, child))
            continue
        if child not in children.setdefault(par, []):
            children[par].append(child)
    species = []
    for p in keep:
        tags = set(p.get("tags") or []) & KEEP_TAGS
        if p["dex"] in klass:
            tags.add(klass[p["dex"]])
        if p["speciesId"] + "_shadow" in shadow_ids:
            tags.add("shadoweligible")
        orig = p.get("originalFormId")
        if orig and orig != p["speciesId"]:
            tags.add("battleform")  # e.g. Mimikyu (Busted): never in storage
        evo = children.get(p["speciesId"], [])
        bs = p["baseStats"]
        if (p["dex"], bs["atk"], bs["def"], bs["hp"]) not in gm_stats:
            (warn if p.get("released") else lambda m: None)(
                "base stats of %s %s not found in the Game Master" % (p["speciesId"], bs))
        species.append({
            "id": p["speciesId"], "name": p["speciesName"], "dex": p["dex"],
            "atk": bs["atk"], "def": bs["def"], "hp": bs["hp"],
            "released": bool(p.get("released")),
            "evolvesTo": evo, "tags": sorted(tags),
        })
    species.sort(key=lambda s: (s["dex"], s["id"]))
    return species


def build_rankings(files, species_ids):
    out = {}
    for league, raw_json in files.items():
        rows = json.loads(raw_json)
        ranks = {}
        for i, r in enumerate(rows, 1):
            sid = r["speciesId"]
            if sid in species_ids or (sid.endswith("_shadow") and sid[:-7] in species_ids):
                ranks[sid] = i
        out[league] = {"total": len(rows), "ranks": ranks}
    return out


def sanity(data, previous):
    errs = []
    sp = data["species"]
    released = sum(s["released"] for s in sp)
    if released < 900:
        errs.append("only %d released species" % released)
    if previous and released < 0.9 * previous["counts"]["released"]:
        errs.append("released species dropped from %d to %d" % (previous["counts"]["released"], released))
    for s in sp:
        if not all(isinstance(s[k], int) and 1 <= s[k] <= 1000 for k in ("atk", "def", "hp")):
            errs.append("bad stats for %s" % s["id"])
    # PvPoke only ranks viable Pokémon (currently ~170 Little, ~1150 Great, ~840 Ultra, ~400 Master).
    for lg, r in data["rankings"].items():
        before = previous["counts"].get("ranked_" + lg, 0) if previous else 0
        if r["total"] < 100 or r["total"] < 0.7 * before:
            errs.append("%s rankings have %d entries (previously %d)" % (lg, r["total"], before))
    if len(data["cpm"]) != 2 * MAX_LEVEL - 1 or any(b <= a for a, b in zip(data["cpm"], data["cpm"][1:])):
        errs.append("CPM table malformed")
    return errs


def digest(obj):
    return hashlib.sha256(json.dumps(obj, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..", "docs"))
    ap.add_argument("--force", action="store_true", help="rebuild even if sources are unchanged")
    args = ap.parse_args()
    out_dir = os.path.join(args.out, "v1")
    manifest_path = os.path.join(out_dir, "manifest.json")
    previous = json.load(open(manifest_path)) if os.path.exists(manifest_path) else None

    pv_commit = latest_commit(PVPOKE_REPO, "src/data")
    pm_commit = latest_commit(POKEMINERS_REPO, "latest")
    sources = {"pvpoke": pv_commit, "pokeminers": pm_commit}
    if (previous and not args.force and previous["sources"] == sources
            and previous.get("pipelineVersion") == PIPELINE_VERSION):
        print("Sources unchanged since last build; nothing to do.")
        return 0

    print("Downloading PvPoke @", pv_commit["sha"][:10], "and PokeMiners @", pm_commit["sha"][:10])
    pvp = json.loads(raw(PVPOKE_REPO, pv_commit["sha"], "src/data/gamemaster.json"))
    pokemon_js = raw(PVPOKE_REPO, pv_commit["sha"], "src/js/pokemon/Pokemon.js").decode()
    ranking_files = {lg: raw(PVPOKE_REPO, pv_commit["sha"], "src/data/rankings/all/overall/rankings-%d.json" % cp)
                     for lg, cp in LEAGUES.items()}
    gm = json.loads(raw(POKEMINERS_REPO, pm_commit["sha"], "latest/latest.json"))

    cpm = build_cpm(gm)
    check_cpm_against_pvpoke(cpm, pokemon_js)
    klass, gm_stats, gm_evo = gm_index(gm)
    species = build_species(pvp, klass, gm_stats, gm_evo)
    data = {
        "schemaVersion": SCHEMA_VERSION,
        "cpm": cpm,  # levels 1, 1.5, 2, ... 51
        "leagues": LEAGUES,
        "species": species,
        "rankings": build_rankings(ranking_files, {s["id"] for s in species}),
    }
    errs = sanity(data, previous)
    if errs:
        for e in errs:
            print("ERROR:", e, file=sys.stderr)
            if os.environ.get("GITHUB_ACTIONS"):
                print("::error::" + e)
        return 1

    # The app re-runs its (slow) IV ranking only when statsHash changes.
    stats_hash = digest([cpm, [[s["id"], s["dex"], s["atk"], s["def"], s["hp"]] for s in species]])
    body = json.dumps(data, separators=(",", ":"), ensure_ascii=False).encode()
    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "pipelineVersion": PIPELINE_VERSION,
        "dataFile": "data.json",
        "dataSha256": hashlib.sha256(body).hexdigest(),
        "dataBytes": len(body),
        "statsHash": stats_hash,
        "sources": sources,
        "counts": {"species": len(species), "released": sum(s["released"] for s in species),
                   **{"ranked_" + lg: r["total"] for lg, r in data["rankings"].items()}},
        "warnings": warnings,
    }
    if previous and previous.get("dataSha256") == manifest["dataSha256"] and previous.get("warnings") == warnings \
            and previous.get("pipelineVersion") == PIPELINE_VERSION:
        manifest["generatedAt"] = previous["generatedAt"]
        print("Sources moved but the data is identical; updating source SHAs only.")
    else:
        manifest["generatedAt"] = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        os.makedirs(out_dir, exist_ok=True)
        with open(os.path.join(out_dir, "data.json"), "wb") as f:
            f.write(body)
    with open(manifest_path, "w") as f:
        json.dump(manifest, f, indent=1)
        f.write("\n")
    print("Built %d species (%d released), %d bytes, %d warnings." % (
        len(species), manifest["counts"]["released"], len(body), len(warnings)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
