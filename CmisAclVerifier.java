/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Standalone CMIS ACL verification tool.
 *
 * Uses ONLY standard CMIS queries — no vendor-specific REST APIs.
 * The CMIS server enforces ACLs at query time: when authenticated as user X,
 * "SELECT * FROM cmis:document" returns ONLY documents user X can access.
 *
 * Flow:
 *   1. Decode Keycloak JWT bearer token → extract username
 *   2. Verify CMIS access via AtomPub service document
 *   3. Run CMIS query via Browser binding → count accessible documents (server-enforced ACLs)
 *   4. Analyze OpenSearch manifoldcf index → ACL tokens, counts, comparison with CMIS
 *
 * Usage:
 *   cd manifoldcf-saikat
 *   java CmisAclVerifier.java "bearer eyJhbG..."
 *   java CmisAclVerifier.java "eyJhbG..."                       # token without prefix
 *   java CmisAclVerifier.java "bearer eyJ..." http://localhost:9200   # custom OpenSearch URL
 *
 * Requirements: Java 11+ (single-file source-code execution). No external deps.
 */
public class CmisAclVerifier {

    // CMIS Browser binding URL (base — auto-discovers repositoryUrl from service doc)
    static final String CMIS_BROWSER_URL =
            "https://alfresco-demo.crestsolution.com:8080/alfresco/api/-default-/cmis/versions/1.1/browser";

    // CMIS AtomPub URL (for step 2 verification)
    static final String CMIS_ATOMPUB_URL =
            "https://alfresco-demo.crestsolution.com:8080/alfresco/api/-default-/cmis/versions/1.1/atom";

    // OpenSearch defaults
    static final String DEFAULT_OPENSEARCH_URL = "http://localhost:9200";
    static final String OPENSEARCH_INDEX = "manifoldcf";

    // ---- main ---------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java CmisAclVerifier.java \"bearer <JWT>\" [opensearch-url]");
            System.exit(1);
        }

        // Parse arguments
        String token = args[0];
        String opensearchUrl = args.length > 1 ? args[1] : DEFAULT_OPENSEARCH_URL;

        // Strip "bearer " / "Bearer " prefix
        if (token.toLowerCase().startsWith("bearer ")) {
            token = token.substring(7).trim();
        }

        // Trust all HTTPS certs (Alfresco uses self-signed / reverse proxy)
        trustAllCerts();

        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║          CMIS ACL Verification Tool (v2)                     ║");
        System.out.println("║          Pure CMIS queries — no vendor-specific REST APIs     ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Step 1: Decode JWT → username
        System.out.println("━━━ Step 1: Decode JWT Bearer Token ━━━");
        String username = decodeJwt(token);
        System.out.println();

        // Step 2: Verify CMIS access with bearer token (AtomPub)
        System.out.println("━━━ Step 2: Verify CMIS Access (AtomPub Service Document) ━━━");
        verifyCmisAccess(token);
        System.out.println();

        // Step 3: CMIS query to count accessible documents
        System.out.println("━━━ Step 3: CMIS Query — Count Accessible Documents ━━━");
        System.out.println("  The CMIS server enforces ACLs at query time.");
        System.out.println("  An authenticated query returns ONLY documents the user can access.");
        System.out.println("  No group resolution needed — the server knows the user's memberships.");
        System.out.println();
        long cmisCount = cmisQueryDocuments(token);
        System.out.println();

        // Step 4: OpenSearch analysis
        System.out.println("━━━ Step 4: OpenSearch Analysis ━━━");
        analyzeOpenSearch(opensearchUrl, username, cmisCount);
    }

    // ---- Step 1: JWT Decode -------------------------------------------------

    static String decodeJwt(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            throw new RuntimeException("Invalid JWT: expected 3 parts, got " + parts.length);
        }

        // Decode payload (part[1])
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        System.out.println("  JWT Payload (decoded):");

        // Extract fields manually (no JSON library)
        String usernameField = extractJsonField(payload, "preferred_username");
        String name = extractJsonField(payload, "name");
        String email = extractJsonField(payload, "email");
        String iss = extractJsonField(payload, "iss");
        String exp = extractJsonField(payload, "exp");

        System.out.println("    preferred_username: " + usernameField);
        System.out.println("    name:              " + name);
        System.out.println("    email:             " + email);
        System.out.println("    issuer:            " + iss);
        System.out.println("    expires:           " + exp);

        if (usernameField == null || usernameField.isEmpty()) {
            throw new RuntimeException("JWT does not contain 'preferred_username' claim");
        }

        // Check expiry
        if (exp != null) {
            try {
                long expTime = Long.parseLong(exp);
                long now = System.currentTimeMillis() / 1000;
                if (now > expTime) {
                    System.out.println("    ⚠ WARNING: Token expired " + ((now - expTime) / 60) + " minutes ago");
                } else {
                    System.out.println("    ✓ Token valid for " + ((expTime - now) / 60) + " more minutes");
                }
            } catch (NumberFormatException ignored) { }
        }

        System.out.println("  → Username: " + usernameField);
        return usernameField;
    }

    // ---- Step 2: CMIS Verification ------------------------------------------

    static void verifyCmisAccess(String bearerToken) {
        System.out.println("  Calling CMIS AtomPub service document...");
        System.out.println("  URL: " + CMIS_ATOMPUB_URL);

        try {
            String response = httpGet(CMIS_ATOMPUB_URL, bearerToken, "application/xml");
            if (response != null && response.contains("cmis")) {
                String repoName = extractXmlValue(response, "cmis:repositoryName");
                String repoId = extractXmlValue(response, "cmis:repositoryId");
                String productName = extractXmlValue(response, "cmis:productName");
                String productVersion = extractXmlValue(response, "cmis:productVersion");

                System.out.println("  ✓ CMIS authentication successful!");
                System.out.println("    Repository:  " + (repoName != null ? repoName : "N/A"));
                System.out.println("    Repo ID:     " + (repoId != null ? repoId : "N/A"));
                System.out.println("    Product:     " + (productName != null ? productName : "N/A"));
                System.out.println("    Version:     " + (productVersion != null ? productVersion : "N/A"));
            } else {
                System.out.println("  ✗ CMIS response was empty or unexpected");
            }
        } catch (Exception e) {
            System.out.println("  ✗ CMIS access failed: " + e.getMessage());
            System.out.println("    (Token may be expired)");
        }
    }

    // ---- Step 3: CMIS Query -------------------------------------------------

    /**
     * Uses the CMIS Browser binding (JSON) to run a query.
     * The server enforces ACLs — the result set contains only docs the user can access.
     *
     * 1. GET the Browser binding service document → extract repositoryUrl
     * 2. Fix http→https (servers behind HTTPS proxy return internal http:// URLs)
     * 3. GET repositoryUrl?cmisselector=query&q=...&maxItems=N
     * 4. Parse numItems from JSON response (or paginate if not available)
     */
    static long cmisQueryDocuments(String bearerToken) {
        try {
            // 3a: Discover repository URL from Browser binding service document
            System.out.println("  [3a] Discovering CMIS repository URL (Browser binding)...");
            System.out.println("    Service doc URL: " + CMIS_BROWSER_URL);
            String serviceDoc = httpGet(CMIS_BROWSER_URL, bearerToken, "application/json");

            if (serviceDoc == null || serviceDoc.isEmpty()) {
                System.out.println("  ✗ Empty service document response");
                return -1;
            }

            String repoUrl = extractJsonField(serviceDoc, "repositoryUrl");
            if (repoUrl == null) {
                System.out.println("  ✗ Could not extract repositoryUrl from service document");
                return -1;
            }

            // Unescape JSON string (e.g., \/ → /)
            repoUrl = unescapeJson(repoUrl);

            // Fix protocol — CMIS server behind HTTPS reverse proxy returns http:// URLs
            // (Same fix as HttpsForceHttpInvoker in the ManifoldCF CMIS connector)
            if (repoUrl.startsWith("http://") && CMIS_BROWSER_URL.startsWith("https://")) {
                repoUrl = "https://" + repoUrl.substring(7);
                System.out.println("    ⚡ Fixed http→https (reverse proxy rewrite)");
            }
            System.out.println("    Repository URL: " + repoUrl);

            // 3b: Run CMIS query — first page (sample docs)
            // Use IN_TREE filter matching the ManifoldCF crawl job folder
            String cmisql = "SELECT cmis:objectId, cmis:name, cmis:contentStreamFileName FROM cmis:document"
                    + " WHERE IN_TREE ('workspace://SpacesStore/1c199659-688f-4b52-9996-59688f4b5263')";
            System.out.println("  [3b] Running CMIS query (IN_TREE — same folder as crawl job)...");
            System.out.println("    CMISQL: " + cmisql);

            String encodedQuery = URLEncoder.encode(cmisql, "UTF-8");
            // succinct=true → uses succinctProperties with direct values (not verbose properties)
            String queryUrl = repoUrl + "?cmisselector=query&q=" + encodedQuery + "&maxItems=10&succinct=true";
            System.out.println("    Query URL: " + queryUrl);

            String response = httpGet(queryUrl, bearerToken, "application/json");
            if (response == null || response.isEmpty()) {
                System.out.println("  ✗ Empty response from CMIS query");
                return -1;
            }

            // 3c: Show sample documents from the first page
            int pageCount = countJsonArrayEntries(response, "results");
            String hasMoreStr = extractJsonField(response, "hasMoreItems");
            System.out.println("    First page: " + pageCount + " results, hasMoreItems=" + hasMoreStr);

            if (pageCount > 0) {
                System.out.println();
                System.out.println("  [3c] Sample CMIS documents (first " + Math.min(pageCount, 10) + "):");
                showCmisQueryResults(response, Math.min(pageCount, 10));
            }

            // 3d: Paginate to get the true total count
            // NOTE: Alfresco's numItems is unreliable (returns maxItems+1, not true total)
            System.out.println();
            System.out.println("  [3d] Counting total accessible documents (pagination)...");
            long totalCount = cmisCountByPaging(repoUrl, bearerToken, cmisql);
            System.out.println("    ✓ Total accessible documents: " + totalCount);

            return totalCount;

        } catch (Exception e) {
            System.out.println("  ✗ CMIS query failed: " + e.getMessage());
            e.printStackTrace(System.out);
            return -1;
        }
    }

    /**
     * Fallback: page through all CMIS query results to count them.
     */
    static long cmisCountByPaging(String repoUrl, String bearerToken, String cmisql) throws Exception {
        long total = 0;
        int skipCount = 0;
        int maxItems = 100;
        boolean hasMore = true;

        String encodedQuery = URLEncoder.encode(cmisql, "UTF-8");

        while (hasMore) {
            String url = repoUrl + "?cmisselector=query&q=" + encodedQuery
                    + "&maxItems=" + maxItems + "&skipCount=" + skipCount + "&succinct=true";
            String response = httpGet(url, bearerToken, "application/json");

            int pageCount = countJsonArrayEntries(response, "results");
            total += pageCount;

            String hasMoreStr = extractJsonField(response, "hasMoreItems");
            hasMore = "true".equals(hasMoreStr);
            skipCount += maxItems;

            System.out.print(".");
        }
        System.out.println(" done");
        return total;
    }

    /**
     * Display sample documents from a CMIS Browser binding query response.
     * Parses "succinctProperties" blocks from the "results" array.
     */
    static void showCmisQueryResults(String response, int maxShow) {
        int searchFrom = 0;
        int docNum = 0;

        // With succinct=true: "succinctProperties":{"cmis:name":"file.txt",...}
        // Without succinct:    "properties":{"cmis:name":{"value":"file.txt",...},...}
        String propKey = response.contains("succinctProperties") ? "succinctProperties" : "properties";

        while (docNum < maxShow) {
            int propIdx = response.indexOf("\"" + propKey + "\"", searchFrom);
            if (propIdx < 0) break;

            int braceStart = response.indexOf('{', propIdx);
            if (braceStart < 0) break;
            int braceEnd = findMatchingBrace(response, braceStart);
            if (braceEnd < 0) break;

            String props = response.substring(braceStart, braceEnd + 1);

            String name;
            if ("succinctProperties".equals(propKey)) {
                // Succinct: direct values — "cmis:name":"file.txt"
                name = extractJsonField(props, "cmis:contentStreamFileName");
                if (name == null) name = extractJsonField(props, "cmis:name");
            } else {
                // Verbose: nested — "cmis:name":{"value":"file.txt"}
                name = extractNestedPropertyValue(props, "cmis:contentStreamFileName");
                if (name == null) name = extractNestedPropertyValue(props, "cmis:name");
            }
            String objectId = extractJsonField(props, "cmis:objectId");

            docNum++;
            String display = name != null ? name : (objectId != null ? objectId : "(unknown)");
            System.out.println("    " + docNum + ". " + display);

            searchFrom = braceEnd + 1;
        }
    }

    /**
     * Extract value from verbose CMIS Browser binding property format:
     * "cmis:name":{"id":"cmis:name","value":"file.txt",...}
     */
    static String extractNestedPropertyValue(String props, String propertyId) {
        int idx = props.indexOf("\"" + propertyId + "\"");
        if (idx < 0) return null;
        int braceStart = props.indexOf('{', idx);
        if (braceStart < 0) return null;
        int braceEnd = findMatchingBrace(props, braceStart);
        if (braceEnd < 0) return null;
        String nested = props.substring(braceStart, braceEnd + 1);
        return extractJsonField(nested, "value");
    }

    /**
     * Count entries in a JSON array field (e.g., "results": [...]).
     */
    static int countJsonArrayEntries(String json, String arrayField) {
        String pattern = "\"" + arrayField + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return 0;

        int arrayStart = json.indexOf('[', idx);
        if (arrayStart < 0) return 0;

        // Count top-level objects in the array.
        // Track both bracket depth ([]) and brace depth ({}).
        // A top-level array element starts at bracket-depth 1 and brace-depth 0.
        int count = 0;
        int bracketDepth = 0;
        int braceDepth = 0;
        boolean inString = false;
        for (int i = arrayStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString) {
                if (c == '[') bracketDepth++;
                else if (c == ']') {
                    bracketDepth--;
                    if (bracketDepth == 0) break;
                }
                else if (c == '{') {
                    if (bracketDepth == 1 && braceDepth == 0) count++;
                    braceDepth++;
                }
                else if (c == '}') braceDepth--;
            }
        }
        return count;
    }

    // ---- Step 4: OpenSearch Analysis ----------------------------------------

    static void analyzeOpenSearch(String opensearchUrl, String username, long cmisCount) {
        System.out.println("  OpenSearch: " + opensearchUrl);
        System.out.println("  Index:      " + OPENSEARCH_INDEX);
        System.out.println();

        // 4a: Total documents in index
        System.out.println("  [4a] Total documents in index...");
        long totalDocs = countDocs(opensearchUrl, null);
        System.out.println("    Total documents: " + totalDocs);
        System.out.println();

        // 4b: Unique ACL tokens (aggregation) — also collect all group tokens
        System.out.println("  [4b] Unique ACL tokens in index (allow_token_document)...");
        Set<String> allGroupTokens = collectAclTokens(opensearchUrl);
        showAclTokenAggregation(opensearchUrl);
        System.out.println();

        // 4c: Documents matching username only
        System.out.println("  [4c] Documents matching username only (" + username.toLowerCase() + ")...");
        Set<String> usernameOnly = new LinkedHashSet<>(Arrays.asList(username.toLowerCase()));
        long usernameCount = countWithAcl(opensearchUrl, username, usernameOnly);
        System.out.println("    Count: " + usernameCount);
        System.out.println();

        // 4d: Documents matching username + group_everyone
        System.out.println("  [4d] Documents matching username + group_everyone...");
        Set<String> withEveryone = new LinkedHashSet<>(Arrays.asList(username.toLowerCase(), "group_everyone"));
        long everyoneCount = countWithAcl(opensearchUrl, username, withEveryone);
        System.out.println("    Count: " + everyoneCount);
        System.out.println();

        // 4e: Documents matching username + ALL group tokens from index
        //     This is the maximum possible — assumes user belongs to ALL groups
        Set<String> allTokens = new LinkedHashSet<>();
        allTokens.add(username.toLowerCase());
        allTokens.addAll(allGroupTokens);
        System.out.println("  [4e] Documents matching username + ALL " + allGroupTokens.size() + " group tokens from index...");
        System.out.println("    Tokens: " + allTokens);
        long allTokenCount = countWithAcl(opensearchUrl, username, allTokens);
        System.out.println("    Count: " + allTokenCount);
        System.out.println();

        // 4f: Sample documents (using ALL tokens)
        System.out.println("  [4f] Sample accessible documents (ALL tokens, top 10)...");
        showSampleDocs(opensearchUrl, username, allTokens);

        // ---- Summary ----
        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════════════════╗");
        System.out.println("║  Summary                                                           ║");
        System.out.println("╠════════════════════════════════════════════════════════════════════╣");
        System.out.printf("║  User:                     %-39s║%n", username);
        System.out.printf("║  CMIS query count:         %-39s║%n", cmisCount >= 0 ? cmisCount + " (server-enforced ACLs)" : "FAILED");
        System.out.printf("║  OpenSearch total:         %-39s║%n", totalDocs);
        System.out.printf("║  OS (username only):       %-39s║%n", usernameCount);
        System.out.printf("║  OS (+ group_everyone):    %-39s║%n", everyoneCount);
        System.out.printf("║  OS (ALL %d tokens):       %-39s║%n", allTokens.size(), allTokenCount);
        System.out.println("╠════════════════════════════════════════════════════════════════════╣");

        if (cmisCount >= 0 && allTokenCount >= 0) {
            if (cmisCount == allTokenCount) {
                System.out.println("║  ✓ CMIS count matches OpenSearch (ALL tokens) — PERFECT PARITY!  ║");
            } else if (cmisCount == everyoneCount) {
                System.out.println("║  ✓ CMIS count matches OpenSearch (username + group_everyone)      ║");
            } else if (cmisCount > allTokenCount) {
                System.out.printf("║  ⚠ CMIS shows %d more docs than OS (even with ALL tokens)%n", cmisCount - allTokenCount);
                System.out.println("║    → Some CMIS-accessible docs may not be indexed in OpenSearch.  ║");
            } else {
                // cmisCount < allTokenCount: user doesn't belong to all groups
                long diff = allTokenCount - cmisCount;
                System.out.printf("║  △ OS (ALL tokens) shows %d more — user isn't in all groups%n", diff);
                System.out.println("║    → Expected: user doesn't belong to ALL groups in the index.    ║");
                if (cmisCount == everyoneCount) {
                    System.out.println("║    → CMIS matches username+group_everyone. User has basic access. ║");
                } else if (cmisCount > everyoneCount) {
                    System.out.printf("║    → User has %d more docs via CMIS than basic (extra groups).%n", cmisCount - everyoneCount);
                    System.out.println("║    → User belongs to some (but not all) groups in the index.      ║");
                }
            }
        } else if (cmisCount < 0) {
            System.out.println("║  ⚠ CMIS query failed (token expired?). Only OpenSearch data shown. ║");
        }

        System.out.println("╚════════════════════════════════════════════════════════════════════╝");
    }

    /**
     * Collect all unique ACL tokens from OpenSearch (returns group tokens only).
     */
    static Set<String> collectAclTokens(String opensearchUrl) {
        Set<String> groupTokens = new LinkedHashSet<>();
        try {
            String query = "{\"size\":0,\"aggs\":{\"acl_tokens\":{\"terms\":{\"field\":\"allow_token_document\",\"size\":200}}}}";
            String response = httpPost(opensearchUrl + "/" + OPENSEARCH_INDEX + "/_search", query, null);
            if (response == null) return groupTokens;

            int bucketsIdx = response.indexOf("\"buckets\"");
            if (bucketsIdx < 0) return groupTokens;

            int arrayStart = response.indexOf('[', bucketsIdx);
            if (arrayStart < 0) return groupTokens;
            int arrayEnd = findMatchingBracket(response, arrayStart);

            int searchFrom = arrayStart;
            while (searchFrom < arrayEnd) {
                int keyIdx = response.indexOf("\"key\"", searchFrom);
                if (keyIdx < 0 || keyIdx > arrayEnd) break;
                String sub = response.substring(keyIdx - 1);
                String key = extractJsonField(sub, "key");
                if (key == null) break;
                // Collect ALL tokens (groups and users)
                groupTokens.add(key);
                searchFrom = keyIdx + 10;
            }
        } catch (Exception e) {
            System.out.println("    Error collecting tokens: " + e.getMessage());
        }
        return groupTokens;
    }

    /**
     * Show unique ACL tokens from OpenSearch using terms aggregation.
     */
    static void showAclTokenAggregation(String opensearchUrl) {
        try {
            String query = "{\"size\":0,\"aggs\":{\"acl_tokens\":{\"terms\":{\"field\":\"allow_token_document\",\"size\":100}}}}";
            String response = httpPost(opensearchUrl + "/" + OPENSEARCH_INDEX + "/_search", query, null);

            if (response == null) {
                System.out.println("    No response from OpenSearch");
                return;
            }

            // Parse aggregation buckets: "buckets":[{"key":"group_everyone","doc_count":451},...]
            int bucketsIdx = response.indexOf("\"buckets\"");
            if (bucketsIdx < 0) {
                System.out.println("    No aggregation results");
                return;
            }

            int arrayStart = response.indexOf('[', bucketsIdx);
            if (arrayStart < 0) return;
            int arrayEnd = findMatchingBracket(response, arrayStart);

            int searchFrom = arrayStart;
            int tokenNum = 0;
            while (searchFrom < arrayEnd) {
                int keyIdx = response.indexOf("\"key\"", searchFrom);
                if (keyIdx < 0 || keyIdx > arrayEnd) break;

                // Extract from a substring starting just before "key"
                String sub = response.substring(keyIdx - 1);
                String key = extractJsonField(sub, "key");
                String docCount = extractJsonField(sub, "doc_count");

                if (key == null) break;
                tokenNum++;

                String type = key.startsWith("group_") ? "group" : "user ";
                System.out.printf("    %2d. [%s] %-45s docs: %s%n", tokenNum, type, key, docCount);

                searchFrom = keyIdx + 10;
            }

            System.out.println("    ── Total unique tokens: " + tokenNum);

        } catch (Exception e) {
            System.out.println("    Error: " + e.getMessage());
        }
    }

    // ---- OpenSearch Count/Search helpers ------------------------------------

    static long countDocs(String opensearchUrl, String query) {
        try {
            String body = query != null ? query : "{\"query\":{\"match_all\":{}}}";
            String response = httpPost(opensearchUrl + "/" + OPENSEARCH_INDEX + "/_count", body, null);
            if (response == null) return -1;
            String count = extractJsonField(response, "count");
            return count != null ? Long.parseLong(count) : -1;
        } catch (Exception e) {
            System.out.println("      Error: " + e.getMessage());
            return -1;
        }
    }

    static long countWithAcl(String opensearchUrl, String username, Set<String> aclTokens) {
        try {
            String query = buildAclCountQuery(username, aclTokens);
            String response = httpPost(opensearchUrl + "/" + OPENSEARCH_INDEX + "/_count", query, null);
            if (response == null) return -1;
            String count = extractJsonField(response, "count");
            return count != null ? Long.parseLong(count) : -1;
        } catch (Exception e) {
            System.out.println("      Error: " + e.getMessage());
            return -1;
        }
    }

    static void showSampleDocs(String opensearchUrl, String username, Set<String> aclTokens) {
        try {
            String query = buildAclSearchQuery(username, aclTokens, 10);
            String response = httpPost(opensearchUrl + "/" + OPENSEARCH_INDEX + "/_search", query, null);
            if (response == null) {
                System.out.println("      No response from OpenSearch");
                return;
            }

            // Parse hits — extract _id (contains the content path)
            int docNum = 0;
            int firstHitsIdx = response.indexOf("\"hits\"");
            if (firstHitsIdx < 0) {
                System.out.println("      No hits found in response");
                return;
            }
            int hitsArrayStart = response.indexOf("\"hits\"", firstHitsIdx + 6);
            if (hitsArrayStart < 0) {
                System.out.println("      No hits array found in response");
                return;
            }

            int searchFrom = hitsArrayStart;
            while (docNum < 10) {
                int idIdx = response.indexOf("\"_id\"", searchFrom);
                if (idIdx < 0) break;

                String docId = extractJsonField(response.substring(idIdx - 1), "_id");
                if (docId == null) break;
                docNum++;

                // Extract filename from the content path in _id
                String displayName = docId;
                int contentPathIdx = docId.indexOf("contentPath=");
                if (contentPathIdx >= 0) {
                    String path = docId.substring(contentPathIdx + 12);
                    path = path.replace("+", " ");
                    try { path = java.net.URLDecoder.decode(path, "UTF-8"); } catch (Exception ignored) {}
                    displayName = path;
                }

                System.out.println("    " + docNum + ". " + displayName);
                searchFrom = idIdx + docId.length() + 10;
            }

            if (docNum == 0) {
                System.out.println("      No documents found");
            }

        } catch (Exception e) {
            System.out.println("      Error: " + e.getMessage());
        }
    }

    // ---- OpenSearch Query Builders ------------------------------------------

    static String buildAclCountQuery(String username, Set<String> aclTokens) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"query\":{\"bool\":{\"filter\":{\"bool\":{\"should\":[");

        boolean first = true;
        for (String token : aclTokens) {
            if (!first) sb.append(",");
            sb.append("{\"term\":{\"allow_token_document\":\"").append(escapeJson(token)).append("\"}}");
            first = false;
        }

        sb.append("],\"minimum_should_match\":1,\"must_not\":[");
        sb.append("{\"term\":{\"deny_token_document\":\"").append(escapeJson(username.toLowerCase())).append("\"}}");
        sb.append("]}}}}}");

        return sb.toString();
    }

    static String buildAclSearchQuery(String username, Set<String> aclTokens, int size) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"size\":").append(size);
        sb.append(",\"query\":{\"bool\":{\"filter\":{\"bool\":{\"should\":[");

        boolean first = true;
        for (String token : aclTokens) {
            if (!first) sb.append(",");
            sb.append("{\"term\":{\"allow_token_document\":\"").append(escapeJson(token)).append("\"}}");
            first = false;
        }

        sb.append("],\"minimum_should_match\":1,\"must_not\":[");
        sb.append("{\"term\":{\"deny_token_document\":\"").append(escapeJson(username.toLowerCase())).append("\"}}");
        sb.append("]}}}}}");

        return sb.toString();
    }

    // ---- HTTP Helpers -------------------------------------------------------

    static String httpGet(String urlStr, String bearerToken, String accept) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        if (bearerToken != null) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        if (accept != null) {
            conn.setRequestProperty("Accept", accept);
        }

        int status = conn.getResponseCode();
        if (status >= 400) {
            String errorBody = readStream(conn.getErrorStream());
            throw new RuntimeException("HTTP " + status + ": " + errorBody);
        }

        return readStream(conn.getInputStream());
    }

    static String httpPost(String urlStr, String jsonBody, String bearerToken) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        if (bearerToken != null) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        if (status >= 400) {
            String errorBody = readStream(conn.getErrorStream());
            throw new RuntimeException("HTTP " + status + ": " + errorBody);
        }

        return readStream(conn.getInputStream());
    }

    static String readStream(java.io.InputStream is) {
        if (is == null) return null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ---- JSON/XML Helpers (no external libraries) ---------------------------

    /**
     * Extract a string field value from JSON.
     * Handles both "key":"stringValue" and "key":numericValue.
     */
    static String extractJsonField(String json, String field) {
        String pattern = "\"" + field + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;

        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return null;

        // Skip whitespace after colon
        int valueStart = colonIdx + 1;
        while (valueStart < json.length() && json.charAt(valueStart) == ' ') valueStart++;

        if (valueStart >= json.length()) return null;

        char firstChar = json.charAt(valueStart);

        if (firstChar == '"') {
            // String value — handle escaped quotes
            int pos = valueStart + 1;
            while (pos < json.length()) {
                if (json.charAt(pos) == '"' && json.charAt(pos - 1) != '\\') {
                    return json.substring(valueStart + 1, pos);
                }
                pos++;
            }
            return null;
        } else if (firstChar == '[' || firstChar == '{') {
            return null; // Skip arrays and objects
        } else {
            // Numeric or boolean value
            int end = valueStart;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != ']') {
                end++;
            }
            return json.substring(valueStart, end).trim();
        }
    }

    /**
     * Extract a value from XML by tag name.
     */
    static String extractXmlValue(String xml, String tag) {
        String openTag = "<" + tag + ">";
        int start = xml.indexOf(openTag);
        if (start < 0) return null;
        start += openTag.length();
        String closeTag = "</" + tag + ">";
        int end = xml.indexOf(closeTag, start);
        if (end < 0) return null;
        return xml.substring(start, end).trim();
    }

    /**
     * Find the matching closing brace for an opening brace.
     */
    static int findMatchingBrace(String s, int openIdx) {
        if (openIdx < 0 || openIdx >= s.length() || s.charAt(openIdx) != '{') return -1;
        int depth = 0;
        boolean inString = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    /**
     * Find the matching closing bracket for an opening bracket.
     */
    static int findMatchingBracket(String s, int openIdx) {
        if (openIdx < 0 || openIdx >= s.length() || s.charAt(openIdx) != '[') return -1;
        int depth = 0;
        boolean inString = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString) {
                if (c == '[') depth++;
                else if (c == ']') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Unescape common JSON string escape sequences.
     */
    static String unescapeJson(String s) {
        if (s == null) return null;
        return s.replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\\", "\\");
    }

    // ---- SSL Helper ---------------------------------------------------------

    static void trustAllCerts() throws Exception {
        TrustManager[] trustAll = new TrustManager[] {
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(X509Certificate[] certs, String t) { }
                public void checkServerTrusted(X509Certificate[] certs, String t) { }
            }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAll, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
    }
}
