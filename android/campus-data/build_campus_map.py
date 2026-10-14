"""Build an offline vector basemap from the same public OSM extract as the road graph."""
import json
import math
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent
boundary = json.loads((ROOT / "fengxian-boundary.json").read_text(encoding="utf-8"))["polygon"]
tree = ET.parse(ROOT / "fengxian-roads.osm").getroot()
nodes = {n.attrib["id"]: [float(n.attrib["lat"]), float(n.attrib["lon"])] for n in tree.findall("node")}
ways = {w.attrib["id"]: w for w in tree.findall("way")}


def tags(element):
    return {t.attrib["k"]: t.attrib["v"] for t in element.findall("tag")}


def inside(point):
    lat, lng = point
    result = False
    for (a, b), (c, d) in zip(boundary, boundary[1:] + boundary[:1]):
        if (a > lat) != (c > lat) and lng < (d - b) * (lat - a) / (c - a) + b:
            result = not result
    return result


def refs(way):
    values = [n.attrib["ref"] for n in way.findall("nd")]
    return values if all(n in nodes for n in values) else []


def kind(t):
    if "building" in t and t["building"] != "no":
        return "building"
    if t.get("natural") == "water" or t.get("waterway") == "riverbank":
        return "water"
    if t.get("leisure") in {"pitch", "track"}:
        return "sports"
    if t.get("natural") in {"wood", "scrub", "grassland"} or t.get("landuse") in {"grass", "forest", "meadow"} or t.get("leisure") in {"park", "garden"}:
        return "green"
    return None


def join_rings(parts):
    rings = []
    pending = [p[:] for p in parts if len(p) >= 2]
    while pending:
        ring = pending.pop()
        while ring[0] != ring[-1]:
            for i, part in enumerate(pending):
                if ring[-1] == part[0]:
                    ring.extend(part[1:])
                elif ring[-1] == part[-1]:
                    ring.extend(part[-2::-1])
                elif ring[0] == part[-1]:
                    ring = part[:-1] + ring
                elif ring[0] == part[0]:
                    ring = part[:0:-1] + ring
                else:
                    continue
                pending.pop(i)
                break
            else:
                break
        if len(ring) >= 4 and ring[0] == ring[-1]:
            rings.append([nodes[n] for n in ring])
    return rings


def label(t):
    value = t.get("name:zh", t.get("name", "")).strip()
    for prefix in ("华东理工大学奉贤校区", "华东理工大学", "奉贤校区"):
        value = value.removeprefix(prefix).strip()
    return value[:14]


areas, roads, labels = [], [], []


def add_area(t, rings):
    style = kind(t)
    if not style or not rings or not any(inside(p) for ring in rings for p in ring):
        return
    areas.append({"kind": style, "rings": rings})
    name = label(t)
    if name:
        outer = rings[0][:-1]
        centre = [sum(p[i] for p in outer) / len(outer) for i in range(2)]
        if inside(centre):
            priority = 90 if t.get("amenity") == "library" else 70 if style in {"water", "sports"} else 20 if t.get("building") == "dormitory" else 50
            labels.append({"name": name, "point": centre, "priority": priority})


for way in ways.values():
    t, ids = tags(way), refs(way)
    points = [nodes[n] for n in ids]
    if len(ids) >= 4 and ids[0] == ids[-1]:
        add_area(t, [points])
    if t.get("highway") and len(points) >= 2 and any(inside(p) for p in points):
        width = 2 if t["highway"] in {"service", "residential", "living_street", "unclassified"} else 1
        roads.append({"width": width, "points": points})

for relation in tree.findall("relation"):
    t = tags(relation)
    if t.get("type") != "multipolygon" or not kind(t):
        continue
    outer, inner = [], []
    for member in relation.findall("member"):
        if member.attrib.get("type") != "way" or member.attrib["ref"] not in ways:
            continue
        (inner if member.attrib.get("role") == "inner" else outer).append(refs(ways[member.attrib["ref"]]))
    add_area(t, join_rings(outer) + join_rings(inner))

for node in tree.findall("node"):
    t, point = tags(node), nodes[node.attrib["id"]]
    if label(t) and inside(point) and t.get("amenity") in {"library", "restaurant", "food_court"}:
        labels.append({"name": label(t), "point": point, "priority": 80})

assert areas and roads
for feature in areas:
    for ring in feature["rings"]:
        assert len(ring) >= 4 and ring[0] == ring[-1]
        assert all(math.isfinite(n) for point in ring for n in point)
labels.sort(key=lambda entry: (-entry["priority"], entry["name"]))
unique_labels = []
for entry in labels:
    lat, lng = entry["point"]
    duplicate = False
    for previous in unique_labels:
        a, b = previous["point"]
        metres = math.hypot((lat - a) * 111195, (lng - b) * 111195 * math.cos(math.radians(lat)))
        if entry["name"] == previous["name"] and metres < 150:
            duplicate = True
            break
    if not duplicate:
        unique_labels.append(entry)
labels = unique_labels
data = {
    "name": "华东理工大学奉贤校区", "coordinate_system": "WGS84",
    "source": "https://www.openstreetmap.org/copyright", "license": "ODbL-1.0", "date": "2026-10-12",
    "boundary": boundary, "areas": areas, "roads": roads, "labels": labels,
}
dest = ROOT.parent / "app/src/main/assets/campus-map.json"
dest.write_text(json.dumps(data, separators=(",", ":"), ensure_ascii=False), encoding="utf-8")
print(f"Campus map: {dict(Counter(a['kind'] for a in areas))}, {len(roads)} roads, {len(labels)} labels")
