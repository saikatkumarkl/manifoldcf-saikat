#!/bin/bash
# Test script for REST API connector

MCF_API="http://localhost:8345/mcf-api-service"

echo "=== Step 1: Create REST API Repository Connection ==="
curl -sf -X PUT "${MCF_API}/json/repositoryconnections/TestRestAPI" \
  -H 'Content-Type: application/json' \
  -d '{"repositoryconnection":{"name":"TestRestAPI","class_name":"org.apache.manifoldcf.crawler.connectors.restapi.RestApiRepositoryConnector","description":"Test REST API connector - GitHub","max_connections":"10","configuration":{"_PARAMETER_":[{"_attribute_name":"VENDOR","_value_":"github"},{"_attribute_name":"PROTOCOL","_value_":"https"},{"_attribute_name":"SERVER","_value_":"api.github.com"},{"_attribute_name":"PORT","_value_":"443"},{"_attribute_name":"AUTHTYPE","_value_":"none"},{"_attribute_name":"BASEPATH","_value_":"/repos/octocat/Hello-World"},{"_attribute_name":"SEEDENDPOINT","_value_":"/issues"},{"_attribute_name":"DOCENDPOINT","_value_":"/issues/{id}"},{"_attribute_name":"CONTENTENDPOINT","_value_":"/issues/{id}"},{"_attribute_name":"ACLENDPOINT","_value_":""},{"_attribute_name":"PAGINATIONTYPE","_value_":"page"},{"_attribute_name":"PAGESIZE","_value_":"30"},{"_attribute_name":"PAGEPARAM","_value_":"page"},{"_attribute_name":"PAGESIZEPARAM","_value_":"per_page"},{"_attribute_name":"ITEMSPATH","_value_":"$"},{"_attribute_name":"IDFIELD","_value_":"id"},{"_attribute_name":"TITLEFIELD","_value_":"title"},{"_attribute_name":"CONTENTFIELD","_value_":"body"},{"_attribute_name":"RESPONSETYPE","_value_":"json"}]}}}'
echo ""

echo "=== Step 2: Verify Connection Exists ==="
curl -sf "${MCF_API}/json/repositoryconnections/TestRestAPI" | python3 -m json.tool 2>/dev/null || echo "Connection NOT found"

echo ""
echo "=== Step 3: Check Connection Status ==="
curl -sf "${MCF_API}/json/status/repositoryconnections/TestRestAPI" | python3 -m json.tool 2>/dev/null || echo "Status check failed"

echo ""
echo "=== Done ==="
