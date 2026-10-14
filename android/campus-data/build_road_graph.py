"""Build the offline campus road graph from the public OSM extract, never account data."""
import json
import math
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
boundary = json.loads((ROOT / "fengxian-boundary.json").read_text(encoding="utf-8"))["polygon"]

def inside(lat, lng):
    result = False
    for (a, b), (c, d) in zip(boundary, boundary[1:] + boundary[:1]):
        if (a > lat) != (c > lat) and lng < (d - b) * (lat - a) / (c - a) + b:
            result = not result
    return result

tree = ET.parse(ROOT / "fengxian-roads.osm")
coords = {n.attrib["id"]: [float(n.attrib["lat"]), float(n.attrib["lon"])] for n in tree.findall("node")}
allowed = {"footway", "path", "pedestrian", "living_street", "service", "residential", "track", "unclassified", "cycleway"}
edges = set()
for way in tree.findall("way"):
    tags = {t.attrib["k"]: t.attrib["v"] for t in way.findall("tag")}
    if tags.get("highway") not in allowed or tags.get("foot") == "no":
        continue
    refs = [n.attrib["ref"] for n in way.findall("nd")]
    for a, b in zip(refs, refs[1:]):
        if a != b and a in coords and b in coords and inside(*coords[a]) and inside(*coords[b]):
            edges.add(tuple(sorted((a, b))))

adj = {}
for a, b in edges:
    adj.setdefault(a, set()).add(b)
    adj.setdefault(b, set()).add(a)
components = []
remaining = set(adj)
while remaining:
    pending = [next(iter(remaining))]
    component = set()
    while pending:
        n = pending.pop()
        if n in component:
            continue
        component.add(n)
        pending.extend(adj[n] - component)
    remaining -= component
    components.append(component)
largest = max(components, key=len)
ids = sorted(largest, key=int)
indexes = {n: i for i, n in enumerate(ids)}
graph = {
    "source": "https://www.openstreetmap.org/copyright",
    "license": "ODbL-1.0", "date": "2026-10-12", "coordinate_system": "WGS84",
    "campus": "fengxian",
    "nodes": [coords[n] for n in ids],
    "edges": [[indexes[a], indexes[b]] for a, b in sorted(edges) if a in largest and b in largest],
}
dest = ROOT.parent / "app/src/main/assets/campus-roads.json"
dest.parent.mkdir(parents=True, exist_ok=True)
dest.write_text(json.dumps(graph, separators=(",", ":")), encoding="utf-8")
print(f"Road graph: {len(ids)} nodes, {len(graph['edges'])} edges; {len(components)} components before filtering")
