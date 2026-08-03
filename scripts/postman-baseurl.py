#!/usr/bin/env python3
"""Bake a concrete base URL into the generated Postman collection.

openapi-to-postmanv2 emits every request host as the {{baseUrl}} variable with no `raw` URL.
Postman resolves that; HTTPie's importer does not (the URL arrives empty). Walk the collection,
replace the variable host with the real base, and add the `raw` form importers prefer.

Usage: postman-baseurl.py <collection.json> <base-url>
"""
import json
import sys
from urllib.parse import urlparse

path, base = sys.argv[1], sys.argv[2].rstrip("/")
parsed = urlparse(base)

with open(path, encoding="utf-8") as fh:
    collection = json.load(fh)


def rewrite(url):
    if not isinstance(url, dict):
        return
    host = url.get("host")
    if host == ["{{baseUrl}}"] or host == "{{baseUrl}}":
        url["protocol"] = parsed.scheme
        url["host"] = parsed.hostname.split(".")
        if parsed.port:
            url["port"] = str(parsed.port)
    segments = url.get("path") or []
    url["raw"] = base + "/" + "/".join(segments)


def walk(items):
    for item in items:
        if "item" in item:
            walk(item["item"])
        elif "request" in item:
            rewrite(item["request"].get("url"))
            for response in item.get("response") or []:
                rewrite((response.get("originalRequest") or {}).get("url"))


walk(collection.get("item", []))
for variable in collection.get("variable", []):
    if variable.get("key") == "baseUrl":
        variable["value"] = base

with open(path, "w", encoding="utf-8") as fh:
    json.dump(collection, fh, indent=1, ensure_ascii=False)
print(f"baked base URL {base} into {path}")
