"""Feasibility probe: can every refresh keep checkpoint-to-track gap <= 30 m?

Ports RouteEngine.buildTrack (seed -> track on the fixed corridor) to Python so the
refresh-to-refresh variance of the nearest-sample gap can be measured for synthetic
checkpoints placed at a known perpendicular distance from the corridor.

Real checkpoint coordinates are redacted in the captures, so the probe sweeps the
checkpoint position (arc along the corridor, perpendicular offset) instead.
"""

import json
import math
import random
from statistics import mean

M_DEG = 111320.0
EARTH = 6371000.0
TWO_PI = 2.0 * math.pi
CORRIDOR = r"../app/src/main/assets/corridor.json"
DURATION = 741          # 2.03 km at 365 s/km, the fixed profile
# Real refreshes seed with System.currentTimeMillis(): direction and start offset are
# pseudo-random across refreshes, so probe epoch-sized seeds instead of 0..N.
SEEDS = [1_762_000_000_000 + i * 137 for i in range(400)]


def haversine(lat1, lng1, lat2, lng2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lng2 - lng1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH * math.asin(math.sqrt(h))


class Corridor:
    def __init__(self, points, step=0.5):
        lat, lng, arc = [], [], []
        total = 0.0
        for a, b in zip(points, points[1:]):
            seg = haversine(a[0], a[1], b[0], b[1])
            if seg <= 1e-6:
                continue
            pieces = max(1, math.ceil(seg / step))
            for k in range(pieces):
                r = k / pieces
                lat.append(a[0] + (b[0] - a[0]) * r)
                lng.append(a[1] + (b[1] - a[1]) * r)
                arc.append(total + seg * r)
            total += seg
        lat.append(points[-1][0])
        lng.append(points[-1][1])
        arc.append(total)
        self.lat, self.lng, self.arc = lat, lng, arc
        self.length = total

    def _lower(self, d):
        lo, hi = 0, len(self.arc) - 1
        while lo < hi - 1:
            mid = (lo + hi) >> 1
            if self.arc[mid] <= d:
                lo = mid
            else:
                hi = mid
        return lo, hi

    def at(self, d):
        d = min(max(d, 0.0), self.length)
        lo, hi = self._lower(d)
        span = self.arc[hi] - self.arc[lo]
        r = 0.0 if span <= 0 else (d - self.arc[lo]) / span
        return (self.lat[lo] + (self.lat[hi] - self.lat[lo]) * r,
                self.lng[lo] + (self.lng[hi] - self.lng[lo]) * r)

    def bearing(self, d):
        b = self.at(max(0.0, d - 2.0))
        a = self.at(min(self.length, d + 2.0))
        return math.atan2(a[1] - b[1], a[0] - b[0])


def build_track(cor, seed, duration=DURATION):
    rng = random.Random(seed)
    phase_a = rng.random() * TWO_PI
    phase_b = rng.random() * TWO_PI
    wob_a = rng.random() * TWO_PI
    wob_b = rng.random() * TWO_PI
    direction = 1.0 if seed % 2 == 0 else -1.0
    offset = ((seed >> 3) % 1000) / 1000.0 * 60.0
    traversable = max(cor.length - offset, cor.length * 0.9)
    dur = float(duration)

    def raw_speed(t):
        ramp = 0.30 + 0.70 * min(1.0, t / 14.0)
        remaining = dur - t
        if remaining < 16.0:
            ramp *= max(0.45, 0.45 + 0.55 * (remaining / 16.0))
        wobble = 1.0 + 0.11 * math.sin(TWO_PI * t / 103.0 + phase_a) \
            + 0.055 * math.sin(TWO_PI * t / 29.0 + phase_b)
        return max(0.15, ramp * wobble)

    ticks = duration * 10 + 1
    speeds = [raw_speed(i * 0.1) for i in range(ticks)]
    area = sum((speeds[i] + speeds[i + 1]) / 2.0 * 0.1 for i in range(ticks - 1))
    scale = traversable / area
    integ = [0.0] * ticks
    for i in range(ticks - 1):
        integ[i + 1] = integ[i] + (speeds[i] + speeds[i + 1]) / 2.0 * 0.1 * scale

    def travelled_at(t):
        t = min(max(t, 0.0), dur)
        pos = t / 0.1
        lo = int(pos)
        if lo >= ticks - 1:
            return integ[ticks - 1]
        r = pos - lo
        return integ[lo] + (integ[lo + 1] - integ[lo]) * r

    noise = random.Random(seed + 1)
    samples = []
    for i in range(duration // 2 + 1):
        t = i * 2.0
        dist = travelled_at(t)
        arc = (offset + dist) if direction > 0 else (cor.length - offset - dist)
        arc = min(max(arc, 0.0), cor.length)
        lat, lng = cor.at(arc)
        side = 1.1 * math.sin(arc / 41.0 + wob_a) + 0.45 * math.sin(arc / 9.7 + wob_b) \
            + noise.gauss(0.0, 0.18)
        heading = cor.bearing(arc) + math.pi / 2.0 + (math.pi if direction < 0 else 0.0)
        lat += side * math.cos(heading) / M_DEG
        lng += side * math.sin(heading) / M_DEG / max(0.25, math.cos(math.radians(lat)))
        samples.append((lat, lng))
    return samples


def make_checkpoint(cor, arc, offset_m):
    """Point offset perpendicular to the corridor at [arc] by [offset_m] metres."""
    lat, lng = cor.at(arc)
    heading = cor.bearing(arc) + math.pi / 2.0
    lat += offset_m * math.cos(heading) / M_DEG
    lng += offset_m * math.sin(heading) / M_DEG / max(0.25, math.cos(math.radians(lat)))
    return (lat, lng)


def gaps_for(cor, cp, seeds=SEEDS):
    out = []
    for s in seeds:
        track = cor.build_cache(s) if hasattr(cor, "build_cache") else build_track(cor, s)
        out.append(min(haversine(cp[0], cp[1], lat, lng) for lat, lng in track))
    return out


def stats(vals):
    vals = sorted(vals)
    return dict(min=vals[0], p50=vals[len(vals) // 2], mean=mean(vals),
                max=vals[-1], pct30=100.0 * sum(v <= 30.0 for v in vals) / len(vals))


def main():
    points = json.load(open(CORRIDOR))["points"]
    cor = Corridor(points)
    print(f"corridor: {len(points)} vertices, {cor.length:.1f} m")
    seeds = list(SEEDS)

    # cache tracks once
    cache = {s: build_track(cor, s) for s in seeds}
    def gaps(cp):
        return [min(haversine(cp[0], cp[1], lat, lng) for lat, lng in cache[s]) for s in seeds]

    print("\nA. interior checkpoint (arc=1000), gap vs perpendicular offset")
    for d in (0, 10, 20, 25, 30, 60, 90, 120):
        st = stats(gaps(make_checkpoint(cor, 1000, d)))
        print(f"  d={d:3d} m -> min {st['min']:6.1f} | median {st['p50']:6.1f} | "
              f"max {st['max']:6.1f} | <=30 m {st['pct30']:5.1f}%")

    print("\nB. end bands (d=0, on-corridor), gap across seeds")
    for a in (5, 20, 40, 60, 80, 200, 1900, 2000, 2040, 2060):
        st = stats(gaps(make_checkpoint(cor, a, 0)))
        print(f"  arc={a:4d} m -> min {st['min']:6.1f} | median {st['p50']:6.1f} | "
              f"max {st['max']:6.1f} | <=30 m {st['pct30']:5.1f}%")

    print("\nC. 30 m budget: max perpendicular offset that still keeps every seed <= 30 m")
    for d in (24, 25, 26, 27, 28, 29, 30):
        st = stats(gaps(make_checkpoint(cor, 1000, d)))
        print(f"  d={d} m -> max {st['max']:5.2f} | <=30 m {st['pct30']:5.1f}%")

    print("\nD. per-checkpoint stability: spread of gap for fixed arc/d across seeds")
    for a in (300, 1000, 1700):
        for d in (10, 30, 60):
            v = gaps(make_checkpoint(cor, a, d))
            print(f"  arc={a:4d} d={d:3d} -> spread {max(v) - min(v):5.1f} m")

    print("\nE. would refreshing help? check a fixed checkpoint at d=90 m over 400 seeds")
    v = gaps(make_checkpoint(cor, 1000, 90))
    print(f"  min={min(v):.1f} max={max(v):.1f} -> best possible across 400 refreshes")


if __name__ == "__main__":
    main()
