#!/usr/bin/env python3
"""2025 backtest: which parts of the player-analysis design survive measurement?

A one-week test already overturned one idea. Adding last week's volume to
ESPN's projection made start/sit decisions WORSE — 73.4% alone, 69.9% as a
50/50 blend, 59.9% on volume alone — across 4,205 same-position pairs. The
rule had been built on a single example.

This asks the same kind of question of everything else in the spec, on a
full season rather than one week. 2025 is used to calibrate the METHOD, never
to judge a player: whether usage predicts floor in general does not depend on
who played where.

    A. Does trailing volume, over 1-4 weeks, add anything to ESPN's projection?
    B. Do the reliability components persist from one half-season to the next?
       A measure that does not persist cannot predict anything.
    C. Does a volatile player really boom and bust more — the premise of the
       favoured/underdog rule?
    D. How much should small samples be shrunk (K), and does correcting ESPN's
       projection by a player's bias help or hurt?

Every test scores the decision actually made — picking the better of two
players at the same position in the same week — not just average error.

Usage:
    python3 backtest_2025.py              fetch once, cache, analyse
    python3 backtest_2025.py --offline    re-analyse the cached season

Writes ~/fantasy-backtest/season-2025.json (reduced cache) and
~/fantasy-backtest/results-2025.json.
"""
import itertools
import json
import math
import os
import re
import statistics as st
import sys
import urllib.error
import urllib.parse
import urllib.request

OUT = os.path.expanduser("~/fantasy-backtest")
CACHE = os.path.join(OUT, "season-2025.json")
RESULTS = os.path.join(OUT, "results-2025.json")
POS = {1: "QB", 2: "RB", 3: "WR", 4: "TE"}
# Startable players each week, by projection. Pairs outside this are never a
# real start/sit question.
TOP_N = {"QB": 24, "RB": 48, "WR": 60, "TE": 24}
MIN_PROJ = 5.0
TARGET_WEIGHT = 1.5
FLOOR = 0.8
EXIT = 0.4
LAST_WEEK = 17  # week 18 excluded: contenders rested starters

# Full PPR, by ESPN stat id. appliedTotal is computed from a LEAGUE's scoring
# settings, and the 2025 route has no league — so it comes back empty and
# every player reads as zero. Scoring the raw stat line ourselves applies one
# system to both actuals and projections.
SCORING = {
    "3": 0.04,   # passing yards
    "4": 4,      # passing touchdowns
    "20": -2,    # interceptions thrown
    "19": 2,     # two-point pass
    "24": 0.1,   # rushing yards
    "25": 6,     # rushing touchdowns
    "26": 2,     # two-point rush
    "53": 1,     # receptions
    "42": 0.1,   # receiving yards
    "43": 6,     # receiving touchdowns
    "44": 2,     # two-point reception
    "72": -2,    # fumbles lost
}


def points(stats):
    return sum(float(stats.get(k) or 0) * v for k, v in SCORING.items())


# Checked against Chem's own archive for 2026 week 2, which ESPN scored with
# the league's settings. If these ever fail, the stat ids have moved.
assert abs(points({"3": 248, "4": 3, "24": 69, "25": 2}) - 40.82) < 0.01, "Allen"
assert abs(points({"42": 55, "53": 3}) - 8.5) < 0.01, "Jefferson"


# ---------------------------------------------------------------- fetch ---

def fetch():
    env = os.path.expanduser("~/.config/fantasy-brief/env")
    vals = dict(re.findall(r'^\s*(?:export\s+)?(\w+)=["\']?([^"\'\n]+)',
                           open(env).read(), re.M))
    key = vals.get("FB_READ_KEY") or vals.get("READ_KEY")
    base = "https://us-central1-fantasy-brief.cloudfunctions.net/espn"
    path = "/apis/v3/games/ffl/seasons/2025/players?scoringPeriodId=5&view=kona_player_info"

    # The probe got the whole pool with a filter ESPN then ignored. Try a
    # sorted wide limit first, then the exact shape that worked.
    filters = [
        {"players": {"limit": 3000,
                     "sortPercOwned": {"sortAsc": False, "sortPriority": 1}}},
        {"players": {"filterIds": {"value": [3918298]},
                     "sortPercOwned": {"sortAsc": False, "sortPriority": 1},
                     "limit": 10}},
    ]
    for f in filters:
        u = (f"{base}?key={urllib.parse.quote(key)}&path={urllib.parse.quote(path)}"
             f"&filter={urllib.parse.quote(json.dumps(f))}")
        print("fetching 2025 season …")
        try:
            with urllib.request.urlopen(u, timeout=180) as r:
                d = json.loads(r.read().decode())
        except urllib.error.HTTPError as e:
            print("  http", e.code, e.read().decode()[:160])
            continue
        rows = d.get("players") if isinstance(d, dict) else d
        players = [x.get("player", x) for x in rows or [] if isinstance(x, dict)]
        print(f"  {len(players)} players")
        if len(players) >= 1500:
            return reduce_season(players)
    sys.exit("could not fetch the 2025 pool")


def reduce_season(players):
    """Keep only what the tests need: per week, projection, actual, volume."""
    season = {}
    seen = {"actual": 0, "proj": 0, "applied": 0, "stats": 0, "proj_stats": 0}
    for p in players:
        pos = POS.get(p.get("defaultPositionId"))
        if not pos:
            continue
        weeks = {}
        for r in p.get("stats") or []:
            if r.get("seasonId") != 2025 or r.get("statSplitTypeId") != 1:
                continue
            w = r.get("scoringPeriodId")
            if not w or w > LAST_WEEK:
                continue
            e = weeks.setdefault(str(w), {})
            s = r.get("stats") or {}
            seen["applied"] += bool(r.get("appliedTotal"))
            if r.get("statSourceId") == 0:
                seen["actual"] += 1
                seen["stats"] += bool(s)
                e["a"] = round(points(s), 2) if s else round(r.get("appliedTotal") or 0, 2)
                e["tgt"] = s.get("58", 0)
                e["car"] = s.get("23", 0)
                e["att"] = s.get("0", 0)
            elif r.get("statSourceId") == 1:
                seen["proj"] += 1
                seen["proj_stats"] += bool(s)
                e["p"] = round(points(s), 2) if s else round(r.get("appliedTotal") or 0, 2)
        if weeks:
            season[str(p["id"])] = {"name": p.get("fullName"), "pos": pos, "w": weeks}
    print(f"  weekly rows: {seen['actual']} actual ({seen['stats']} with a stat line), "
          f"{seen['proj']} projected ({seen['proj_stats']} with a stat line); "
          f"appliedTotal present on {seen['applied']}")
    usable = sum(1 for pl in season.values() for e in pl["w"].values()
                 if e.get("p", 0) >= MIN_PROJ and "a" in e)
    print(f"  {usable} player-weeks projected {MIN_PROJ}+ with an actual line")
    if usable == 0:
        sys.exit("  nothing to test — the stat lines did not come through; "
                 "not caching a useless season")
    os.makedirs(OUT, exist_ok=True)
    json.dump(season, open(CACHE, "w"))
    print(f"  cached {len(season)} players -> {CACHE}")
    return season


# -------------------------------------------------------------- helpers ---

def opp(e, pos):
    if pos == "QB":
        return (e.get("att") or 0) + (e.get("car") or 0)
    return (e.get("tgt") or 0) * TARGET_WEIGHT + (e.get("car") or 0)


def games(player, lo=1, hi=LAST_WEEK):
    """Games actually played in [lo, hi]: an actual line with touches and a
    projection. Injury exits — opportunity under 40% of his median in that
    window — are removed, as the spec defines them."""
    out = []
    for w in range(lo, hi + 1):
        e = player["w"].get(str(w))
        if not e or "a" not in e or "p" not in e:
            continue
        o = opp(e, player["pos"])
        if o <= 0 or e["p"] <= 0:
            continue
        out.append({"w": w, "p": e["p"], "a": e["a"], "o": o})
    if len(out) >= 3:
        med = st.median(g["o"] for g in out)
        out = [g for g in out if g["o"] >= EXIT * med]
    return out


def pearson(xs, ys):
    n = len(xs)
    if n < 3:
        return float("nan")
    mx, my = st.mean(xs), st.mean(ys)
    sx = math.sqrt(sum((x - mx) ** 2 for x in xs))
    sy = math.sqrt(sum((y - my) ** 2 for y in ys))
    if sx == 0 or sy == 0:
        return float("nan")
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / (sx * sy)


def metrics(gs):
    """Floor, usage stability, bias, volatility over a list of games."""
    if len(gs) < 2:
        return None
    os_ = [g["o"] for g in gs]
    ratios = [g["a"] / g["p"] for g in gs]
    mo = st.mean(os_)
    return {
        "F": sum(g["a"] >= FLOOR * g["p"] for g in gs) / len(gs),
        "U": max(0.0, 1 - st.pstdev(os_) / mo) if mo else 0.0,
        "B": sum(g["a"] for g in gs) / sum(g["p"] for g in gs),
        "V": st.pstdev(ratios),
        "n": len(gs),
    }


def pair_accuracy(groups, fn, close=None):
    """Share of same-position, same-week pairs where the method picked the
    player who actually scored more. Forecast ties count half."""
    right = total = 0.0
    for rows in groups:
        for x, y in itertools.combinations(rows, 2):
            if x["a"] == y["a"]:
                continue
            if close is not None and abs(x["p"] - y["p"]) > close:
                continue
            fx, fy = fn(x), fn(y)
            total += 1
            if fx == fy:
                right += 0.5
            elif (fx > fy) == (x["a"] > y["a"]):
                right += 1
    return (right / total if total else float("nan")), int(total)


def pct(x):
    return "   n/a" if x != x else f"{x * 100:5.1f}%"


# ------------------------------------------------------------- analyses ---

def weekly_rows(season, lo, hi):
    """Startable players per (position, week) with the inputs every forecast
    needs, built only from information available before that week."""
    groups = []
    # Position points-per-opportunity, from weeks before w only.
    for w in range(lo, hi + 1):
        ppo = {}
        for pos in TOP_N:
            num = den = 0.0
            for pl in season.values():
                if pl["pos"] != pos:
                    continue
                for g in games(pl, 1, w - 1):
                    if g["o"] >= 3:
                        num += g["a"]
                        den += g["o"]
            ppo[pos] = num / den if den else 0.0
        for pos, n in TOP_N.items():
            cands = []
            for pid, pl in season.items():
                if pl["pos"] != pos:
                    continue
                e = pl["w"].get(str(w))
                if not e or "a" not in e or e.get("p", 0) < MIN_PROJ:
                    continue
                if opp(e, pos) <= 0:
                    continue  # did not play; not a forecasting question
                prior = games(pl, 1, w - 1)
                if not prior:
                    continue
                vols = [g["o"] for g in prior]
                row = {"pid": pid, "pos": pos, "w": w, "p": e["p"], "a": e["a"],
                       "vol1": vols[-1] * ppo[pos],
                       "vol3": st.mean(vols[-3:]) * ppo[pos],
                       "vol4": st.mean(vols[-4:]) * ppo[pos],
                       "prior": prior}
                cands.append(row)
            cands.sort(key=lambda r: -r["p"])
            if len(cands) >= 2:
                groups.append(cands[:n])
    return groups


def test_a(season):
    print("\n" + "=" * 72)
    print("A. DOES TRAILING VOLUME ADD ANYTHING TO ESPN'S PROJECTION?")
    print("=" * 72)
    groups = weekly_rows(season, 5, LAST_WEEK)
    rows = [r for g in groups for r in g]
    print(f"{len(rows)} player-weeks, weeks 5-{LAST_WEEK}, startable players only\n")
    if len(rows) < 50:
        print("too few player-weeks to judge anything — skipping")
        return {}

    methods = {"ESPN projection": lambda r: r["p"],
               "volume, last week": lambda r: r["vol1"],
               "volume, last 3": lambda r: r["vol3"],
               "volume, last 4": lambda r: r["vol4"]}
    for wt in (0.9, 0.8, 0.7, 0.5):
        methods[f"{round(wt * 100)}% ESPN + {round((1 - wt) * 100)}% vol3"] = \
            (lambda wt: lambda r: wt * r["p"] + (1 - wt) * r["vol3"])(wt)

    res = {}
    print(f"{'method':<28}{'avg miss':>9}{'start/sit':>11}{'close calls':>13}")
    for name, fn in methods.items():
        mae = st.mean(abs(fn(r) - r["a"]) for r in rows)
        acc, n = pair_accuracy(groups, fn)
        cacc, cn = pair_accuracy(groups, fn, close=3)
        res[name] = {"mae": mae, "acc": acc, "close": cacc}
        print(f"{name:<28}{mae:>9.2f}{pct(acc):>11}{pct(cacc):>13}")
    print(f"({n:,} pairs; {cn:,} close calls within 3 projected points)")

    # Fitted weights, out of sample: fit on weeks 5-11, test on 12-17.
    train = [r for r in rows if r["w"] <= 11]
    test = [r for r in rows if r["w"] >= 12]
    b, c, a0 = ols2(train, "p", "vol3")
    fit = lambda r: a0 + b * r["p"] + c * r["vol3"]
    tgroups = [g for g in groups if g[0]["w"] >= 12]
    facc, _ = pair_accuracy(tgroups, fit)
    eacc, _ = pair_accuracy(tgroups, lambda r: r["p"])
    print(f"\nfitted on weeks 5-11: actual = {a0:.2f} + {b:.2f} x ESPN + {c:.2f} x vol3")
    print(f"tested on weeks 12-17: fitted {pct(facc)} vs ESPN alone {pct(eacc)}")
    res["fitted"] = {"intercept": a0, "espn": b, "vol3": c, "acc": facc, "espn_acc": eacc}

    # Judge on the out-of-sample fit, not the best of eight methods in-sample:
    # picking the luckiest blend after the fact flatters it.
    gain = facc - eacc
    print("\nVERDICT:", f"volume earns a weight of {c:.2f} — it beat ESPN by "
          f"{gain * 100:.1f} points on weeks it never saw"
          if gain > 0.005 and c > 0 else
          "volume adds nothing out of sample — keep ESPN's projection as the baseline")
    return res


def ols2(rows, kx, kz):
    """Least squares for y = a + b x + c z, by the normal equations."""
    n = len(rows)
    xs = [r[kx] for r in rows]; zs = [r[kz] for r in rows]; ys = [r["a"] for r in rows]
    mx, mz, my = st.mean(xs), st.mean(zs), st.mean(ys)
    sxx = sum((x - mx) ** 2 for x in xs); szz = sum((z - mz) ** 2 for z in zs)
    sxz = sum((x - mx) * (z - mz) for x, z in zip(xs, zs))
    sxy = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    szy = sum((z - mz) * (y - my) for z, y in zip(zs, ys))
    det = sxx * szz - sxz ** 2
    if not det:
        return 1.0, 0.0, 0.0
    b = (sxy * szz - szy * sxz) / det
    c = (szy * sxx - sxy * sxz) / det
    return b, c, my - b * mx - c * mz


def test_b(season):
    print("\n" + "=" * 72)
    print("B. DO THE RELIABILITY COMPONENTS PERSIST?")
    print("=" * 72)
    print("Each measured on weeks 1-8, then again on 9-17. A component that")
    print("does not carry from one half to the other cannot predict anything.\n")
    halves = {}
    for pid, pl in season.items():
        h1, h2 = metrics(games(pl, 1, 8)), metrics(games(pl, 9, LAST_WEEK))
        if h1 and h2 and h1["n"] >= 5 and h2["n"] >= 5:
            if st.mean(g["p"] for g in games(pl)) >= MIN_PROJ:
                halves[pid] = (pl["pos"], h1, h2)
    print(f"{len(halves)} players with 5+ games in each half\n")
    res = {}
    names = {"F": "floor rate", "U": "usage stability", "B": "bias", "V": "volatility"}
    print(f"{'component':<22}" + "".join(f"{p:>8}" for p in ("ALL", *TOP_N)))
    for k in ("F", "U", "B", "V"):
        line = f"{names[k] + ' -> itself':<22}"
        res[k] = {}
        for pos in ("ALL", *TOP_N):
            xs = [(h1[k], h2[k]) for (ps, h1, h2) in halves.values()
                  if pos == "ALL" or ps == pos]
            r = pearson([x for x, _ in xs], [y for _, y in xs]) if xs else float("nan")
            res[k][pos] = r
            line += f"{r:>8.2f}" if r == r else f"{'n/a':>8}"
        print(line)
    for src in ("U", "F"):
        xs = [(h1[src], h2["F"]) for (_, h1, h2) in halves.values()]
        r = pearson([x for x, _ in xs], [y for _, y in xs])
        res[f"{src}->F"] = r
        print(f"{names[src] + ' -> floor':<22}{r:>8.2f}")

    # Terciles of the proposed score, first half against second-half floor.
    tiers = {"top third": [], "middle": [], "bottom third": []}
    for pos in TOP_N:
        ps = [(h1, h2) for (p, h1, h2) in halves.values() if p == pos]
        ps.sort(key=lambda t: -(0.6 * t[0]["U"] + 0.4 * t[0]["F"]))
        k = len(ps) // 3
        for i, t in enumerate(ps):
            tiers["top third" if i < k else "bottom third" if i >= len(ps) - k
                  else "middle"].append(t[1]["F"])
    print("\nproposed score (0.6 U + 0.4 F) on weeks 1-8, floor rate on 9-17:")
    for name, fs in tiers.items():
        if fs:
            print(f"   {name:<14} floor {pct(st.mean(fs))}   ({len(fs)} players)")
            res[f"tier_{name}"] = st.mean(fs)

    print("\nVERDICT (correlation over 0.3 is useful signal, under 0.1 is noise):")
    for k in ("F", "U", "B", "V"):
        r = res[k]["ALL"]
        word = "usable" if r > 0.3 else "weak" if r > 0.1 else "noise — do not use"
        print(f"   {names[k]:<18} {r:5.2f}   {word}")
    return res


def test_c(season):
    print("\n" + "=" * 72)
    print("C. DO VOLATILE PLAYERS REALLY BOOM AND BUST MORE?")
    print("=" * 72)
    print("Pairs projected within 2 points in weeks 9-17, split by which player")
    print("was more volatile in weeks 1-8. The underdog rule needs the volatile")
    print("one to boom more; the favoured rule needs the steady one to bust less.\n")
    v1 = {pid: metrics(games(pl, 1, 8)) for pid, pl in season.items()}
    hi = {"boom": 0, "bust": 0, "n": 0}
    lo = {"boom": 0, "bust": 0, "n": 0}
    for g in weekly_rows(season, 9, LAST_WEEK):
        for x, y in itertools.combinations(g, 2):
            mx, my = v1.get(x["pid"]), v1.get(y["pid"])
            if not mx or not my or mx["n"] < 4 or my["n"] < 4:
                continue
            if abs(x["p"] - y["p"]) > 2 or mx["V"] == my["V"]:
                continue
            h, l = (x, y) if mx["V"] > my["V"] else (y, x)
            for side, r in ((hi, h), (lo, l)):
                side["n"] += 1
                side["boom"] += r["a"] >= 1.5 * r["p"]
                side["bust"] += r["a"] < 0.5 * r["p"]
    res = {}
    for label, s in (("more volatile", hi), ("steadier", lo)):
        if s["n"]:
            b, u = s["boom"] / s["n"], s["bust"] / s["n"]
            res[label] = {"boom": b, "bust": u, "n": s["n"]}
            print(f"   {label:<14} boom {pct(b)}   bust {pct(u)}   ({s['n']:,} player-games)")
    if len(res) == 2:
        db = res["more volatile"]["boom"] - res["steadier"]["boom"]
        du = res["more volatile"]["bust"] - res["steadier"]["bust"]
        print(f"\n   difference: boom {db * 100:+.1f} points, bust {du * 100:+.1f} points")
        print("\nVERDICT:", "volatility is real and predictable — the game-state rule has "
              "something to work with" if db > 0.02 and du > 0.02 else
              "volatile and steady players boom and bust alike — the game-state "
              "rule would be choosing on noise")
    return res


def test_d(season):
    print("\n" + "=" * 72)
    print("D. HOW MUCH SHRINKAGE, AND DOES CORRECTING ESPN'S BIAS HELP?")
    print("=" * 72)
    players = {pid: (pl["pos"], games(pl)) for pid, pl in season.items()}
    players = {k: v for k, v in players.items() if len(v[1]) >= 10}
    posF = {}
    for pos in TOP_N:
        fs = [metrics(g)["F"] for p, g in players.values() if p == pos]
        posF[pos] = st.mean(fs) if fs else 0.5
    res = {"F": {}, "B": {}}
    print("error estimating a player's rest-of-season floor rate / bias from")
    print("his first n games, shrunk toward the position norm (F) or 1.0 (B):\n")
    Ks = (0, 2, 4, 8, 12, 20, 35, 60)
    for comp in ("F", "B"):
        print(f"  {'floor rate' if comp == 'F' else 'bias'}")
        print("   n  " + "".join(f"  K={k:<4}" for k in Ks) + "  best")
        res[comp] = {}
        for n in (2, 3, 4, 6):
            errs = {}
            for K in Ks:
                e = []
                for pos, gs in players.values():
                    first, rest = metrics(gs[:n]), metrics(gs[n:])
                    if not first or not rest:
                        continue
                    prior = posF[pos] if comp == "F" else 1.0
                    est = (n * first[comp] + K * prior) / (n + K)
                    e.append(abs(est - rest[comp]))
                errs[K] = st.mean(e) if e else float("nan")
            best = min(errs, key=errs.get)
            res[comp][n] = {"errors": errs, "bestK": best}
            print(f"   {n}  " + "".join(f"  {errs[K]:.3f} " for K in Ks) + f"  K={best}")
        print()

    # The decisive test: does a bias-corrected projection pick better?
    print("start/sit with ESPN's projection corrected by each player's bias so far")
    print("(shrunk with K, capped at +/-20%), weeks 9-17:")
    groups = weekly_rows(season, 9, LAST_WEEK)
    base, _ = pair_accuracy(groups, lambda r: r["p"])
    print(f"   ESPN alone       {pct(base)}")
    res["bias_correction"] = {"espn": base}
    for K in (2, 4, 8, 20):
        def adj(r, K=K):
            gs = r["prior"]
            b = sum(g["a"] for g in gs) / sum(g["p"] for g in gs)
            b = (len(gs) * b + K) / (len(gs) + K)
            return r["p"] * min(1.2, max(0.8, b))
        acc, _ = pair_accuracy(groups, adj)
        res["bias_correction"][K] = acc
        print(f"   corrected, K={K:<3}  {pct(acc)}")
    best = max((k for k in res["bias_correction"] if k != "espn"),
               key=lambda k: res["bias_correction"][k])
    gain = res["bias_correction"][best] - base
    for comp, label in (("F", "floor rate"), ("B", "bias")):
        top = [res[comp][n]["bestK"] for n in res[comp]]
        if all(k == Ks[-1] for k in top):
            print(f"NOTE: best K for {label} is the largest tested at every n — "
                  f"a player's own record barely beats the norm")
    print("\nVERDICT:", f"bias correction helps (+{gain * 100:.1f} points at K={best})"
          if gain > 0.005 else "bias correction does not help — trust ESPN's projection as is")
    return res


# ----------------------------------------------------------------- main ---

def main():
    if "--offline" in sys.argv:
        season = json.load(open(CACHE))
        print(f"offline: {len(season)} players from {CACHE}")
    else:
        season = fetch()
    results = {"A": test_a(season), "B": test_b(season),
               "C": test_c(season), "D": test_d(season)}
    os.makedirs(OUT, exist_ok=True)
    json.dump(results, open(RESULTS, "w"), indent=1, default=str)
    print(f"\nresults saved -> {RESULTS}")


if __name__ == "__main__":
    main()
