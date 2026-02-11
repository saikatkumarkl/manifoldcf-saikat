#!/usr/bin/env python3
"""End-to-end ACL query test: resolve user groups, then query documents."""
import json
import urllib.request
import sys

OPENSEARCH = "http://localhost:9200"
username = sys.argv[1] if len(sys.argv) > 1 else "afc-it-user"

# Step 1: Resolve user's groups from manifoldcf-groups index
groups_query = json.dumps({
    "query": {"term": {"members": username}},
    "_source": ["acl_token"]
}).encode()
req = urllib.request.Request(
    f"{OPENSEARCH}/manifoldcf-groups/_search",
    data=groups_query,
    headers={"Content-Type": "application/json"}
)
with urllib.request.urlopen(req) as resp:
    groups_data = json.loads(resp.read())

tokens = [username]
for hit in groups_data["hits"]["hits"]:
    tokens.append(hit["_source"]["acl_token"])

print(f"=== {username} resolves to tokens: {tokens}")

# Step 2: Query documents with ACL filter
doc_query = json.dumps({
    "query": {
        "bool": {
            "filter": {
                "bool": {
                    "should": [{"term": {"allow_token_document": t}} for t in tokens],
                    "minimum_should_match": 1
                }
            }
        }
    },
    "_source": ["file_title", "allow_token_document"],
    "size": 20
}).encode()
req2 = urllib.request.Request(
    f"{OPENSEARCH}/manifoldcf/_search",
    data=doc_query,
    headers={"Content-Type": "application/json"}
)
with urllib.request.urlopen(req2) as resp2:
    docs_data = json.loads(resp2.read())

total = docs_data["hits"]["total"]["value"]
print(f"=== Documents accessible: {total}")
for hit in docs_data["hits"]["hits"]:
    title = hit["_source"].get("file_title", "N/A")
    acl = hit["_source"].get("allow_token_document", [])
    print(f"  - {title}")
    print(f"    ACL: {acl}")
