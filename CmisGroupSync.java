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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Group membership sync and ACL-filtered document search.
 *
 * Architecture:
 *
 *   ┌─────────────────────────────────────────────────────────────────┐
 *   │  SYNC PHASE (runs alongside ManifoldCF crawl)                  │
 *   │                                                                 │
 *   │  1. Read group tokens from 'manifoldcf' document index         │
 *   │  2. Query Alfresco REST API for matching groups + members      │
 *   │  3. Store group→members mapping in 'manifoldcf-groups' index   │
 *   └─────────────────────────────────────────────────────────────────┘
 *                              ↓
 *   ┌─────────────────────────────────────────────────────────────────┐
 *   │  QUERY PHASE (at user login / search time)                     │
 *   │                                                                 │
 *   │  1. Look up username in 'manifoldcf-groups' → get user's groups│
 *   │  2. Build ACL filter: username + discovered group tokens       │
 *   │  3. Query 'manifoldcf' document index → accessible docs only  │
 *   └─────────────────────────────────────────────────────────────────┘
 *
 * Usage:
 *   cd manifoldcf-saikat
 *
 *   # Sync groups from Alfresco to OpenSearch (requires valid bearer token)
 *   java CmisGroupSync.java sync "bearer eyJhbG..."
 *   java CmisGroupSync.java sync "bearer eyJhbG..." http://localhost:9200
 *
 *   # Query accessible documents for a user (no bearer token needed)
 *   java CmisGroupSync.java query USERNAME
 *   java CmisGroupSync.java query USERNAME http://localhost:9200
 *
 *   # Full-text search with ACL filtering
 *   java CmisGroupSync.java search USERNAME "contract template"
 *   java CmisGroupSync.java search USERNAME "contract template" http://localhost:9200
 *
 * Requirements: Java 11+ (single-file source-code execution). No external deps.
 */
public class CmisGroupSync {

    // Alfresco REST API (for group membership queries)
    static final String ALFRESCO_API =
            "https://alfresco-demo.crestsolution.com:8080/alfresco/api/-default-/public/alfresco/versions/1";

    // OpenSearch
    static final String DEFAULT_OPENSEARCH_URL = "http://localhost:9200";
    static final String GROUPS_INDEX = "manifoldcf-groups";
    static final String DOCS_INDEX = "manifoldcf";

    // ---- main ---------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            printUsage();
            System.exit(1);
        }

        trustAllCerts();

        String action = args[0].toLowerCase();

        switch (action) {
            case "sync": {
                String token = args[1];
                if (token.toLowerCase().startsWith("bearer ")) {
                    token = token.substring(7).trim();
                }
                String osUrl = args.length > 2 ? args[2] : DEFAULT_OPENSEARCH_URL;
                syncGroups(token, osUrl);
                break;
            }
            case "query": {
                String username = args[1].toLowerCase();
                String osUrl = args.length > 2 ? args[2] : DEFAULT_OPENSEARCH_URL;
                queryUserDocuments(username, osUrl, null);
                break;
            }
            case "search": {
                String username = args[1].toLowerCase();
                String searchTerms = args.length > 2 ? args[2] : "";
                String osUrl = args.length > 3 ? args[3] : DEFAULT_OPENSEARCH_URL;
                queryUserDocuments(username, osUrl, searchTerms);
                break;
            }
            default:
                printUsage();
                System.exit(1);
        }
    }

    static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  java CmisGroupSync.java sync   \"bearer TOKEN\" [opensearch-url]");
        System.err.println("  java CmisGroupSync.java query  USERNAME [opensearch-url]");
        System.err.println("  java CmisGroupSync.java search USERNAME \"search terms\" [opensearch-url]");
    }

    // =========================================================================
    //  SYNC PHASE: Alfresco groups → OpenSearch manifoldcf-groups index
    // =========================================================================

    static void syncGroups(String bearerToken, String opensearchUrl) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     Group Membership Sync: Alfresco → OpenSearch             ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Step 1: Get unique ACL group tokens from the document index
        System.out.println("━━━ Step 1: Discover group tokens from document index ━━━");
        Set<String> docGroupTokens = collectGroupTokensFromDocs(opensearchUrl);
        Set<String> docUserTokens = collectUserTokensFromDocs(opensearchUrl);
        System.out.println("  Group tokens: " + docGroupTokens.size());
        for (String t : docGroupTokens) System.out.println("    [group] " + t);
        System.out.println("  User tokens: " + docUserTokens.size());
        for (String t : docUserTokens) System.out.println("    [user ] " + t);
        System.out.println();

        // Step 2: List Alfresco groups via REST API
        System.out.println("━━━ Step 2: Fetch Alfresco groups via REST API ━━━");
        Map<String, String> alfrescoGroups = listAlfrescoGroups(bearerToken);
        if (alfrescoGroups.isEmpty()) {
            System.out.println("  ✗ Could not fetch Alfresco groups (token may lack admin access).");
            System.out.println("    Falling back to person-based group lookup...");
            System.out.println();

            // Fallback: for each known user, query their groups individually
            System.out.println("━━━ Step 2b: Fetch groups per user via REST API ━━━");
            Map<String, Set<String>> userGroups = new LinkedHashMap<>();
            for (String user : docUserTokens) {
                Set<String> groups = getPersonGroups(bearerToken, user);
                if (!groups.isEmpty()) {
                    userGroups.put(user, groups);
                    System.out.println("  " + user + " → " + groups);
                }
            }

            // Build group→members reverse mapping
            Map<String, Set<String>> groupMembers = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> entry : userGroups.entrySet()) {
                String user = entry.getKey();
                for (String group : entry.getValue()) {
                    groupMembers.computeIfAbsent(group, k -> new LinkedHashSet<>()).add(user);
                }
            }

            // Also add group_everyone
            groupMembers.put("group_everyone", new LinkedHashSet<>(docUserTokens));

            System.out.println();
            indexGroupMemberships(opensearchUrl, groupMembers);
            return;
        }

        System.out.println("  Found " + alfrescoGroups.size() + " Alfresco groups");

        // Match Alfresco groups to document ACL tokens
        // Alfresco group ID: GROUP_site_xxx_SiteManager
        // ManifoldCF token:  group_site_xxx_sitemanager (lowercased)
        Map<String, String> matchedGroups = new LinkedHashMap<>(); // alfrescoId → aclToken
        for (Map.Entry<String, String> entry : alfrescoGroups.entrySet()) {
            String alfGroupId = entry.getKey();
            String aclToken = alfGroupId.toLowerCase();
            if (docGroupTokens.contains(aclToken)) {
                matchedGroups.put(alfGroupId, aclToken);
                System.out.println("  ✓ " + alfGroupId + " → " + aclToken);
            }
        }

        // Also check for unmatched document tokens
        Set<String> unmatchedDocTokens = new LinkedHashSet<>(docGroupTokens);
        unmatchedDocTokens.removeAll(matchedGroups.values());
        if (!unmatchedDocTokens.isEmpty()) {
            System.out.println("  ⚠ Unmatched document tokens (no Alfresco group found):");
            for (String t : unmatchedDocTokens) System.out.println("    " + t);
        }

        System.out.println("  Matched: " + matchedGroups.size() + "/" + docGroupTokens.size());
        System.out.println();

        // Step 3: Get members for each matched group
        System.out.println("━━━ Step 3: Fetch group members ━━━");
        Map<String, Set<String>> groupMembers = new LinkedHashMap<>();
        Set<String> allUsers = new LinkedHashSet<>(docUserTokens);

        for (Map.Entry<String, String> entry : matchedGroups.entrySet()) {
            String alfGroupId = entry.getKey();
            String aclToken = entry.getValue();
            Set<String> members = getGroupMembers(bearerToken, alfGroupId);
            groupMembers.put(aclToken, members);
            allUsers.addAll(members);
            System.out.println("  " + aclToken + " → " + members.size() + " members: " + members);
        }

        // group_everyone = all known users
        groupMembers.put("group_everyone", allUsers);
        System.out.println("  group_everyone → " + allUsers.size() + " members (all known users)");
        System.out.println();

        // Step 4: Index in OpenSearch
        indexGroupMemberships(opensearchUrl, groupMembers);
    }

    /**
     * Create the groups index and index all group→members mappings.
     */
    static void indexGroupMemberships(String opensearchUrl, Map<String, Set<String>> groupMembers) {
        System.out.println("━━━ Step 4: Index group memberships in OpenSearch ━━━");
        System.out.println("  Index: " + GROUPS_INDEX);

        // Create index with proper mapping
        createGroupsIndex(opensearchUrl);

        // Index each group
        int indexed = 0;
        for (Map.Entry<String, Set<String>> entry : groupMembers.entrySet()) {
            String aclToken = entry.getKey();
            Set<String> members = entry.getValue();
            if (indexGroup(opensearchUrl, aclToken, members)) {
                indexed++;
            }
        }

        // Refresh index
        try {
            httpPost(opensearchUrl + "/" + GROUPS_INDEX + "/_refresh", "", null);
        } catch (Exception ignored) {}

        System.out.println("  ✓ Indexed " + indexed + " groups");
        System.out.println();

        // Summary
        Set<String> allUsers = new LinkedHashSet<>();
        for (Set<String> members : groupMembers.values()) {
            allUsers.addAll(members);
        }

        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║  Sync Complete                                                ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Groups indexed:        %-36d║%n", indexed);
        System.out.printf("║  Total users discovered: %-35d║%n", allUsers.size());
        System.out.println("║                                                              ║");
        System.out.println("║  Now query documents per user:                                ║");
        System.out.println("║    java CmisGroupSync.java query USERNAME                    ║");
        System.out.println("║    java CmisGroupSync.java search USERNAME \"terms\"            ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
    }

    // =========================================================================
    //  QUERY PHASE: username → groups → documents
    // =========================================================================

    /**
     * Resolve a username to their groups, then query documents with proper ACL filter.
     *
     * @param username    The username to resolve
     * @param opensearchUrl  OpenSearch base URL
     * @param searchTerms Optional full-text search terms (null for all accessible docs)
     */
    static void queryUserDocuments(String username, String opensearchUrl, String searchTerms) {
        boolean isSearch = searchTerms != null && !searchTerms.isEmpty();

        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        if (isSearch) {
            System.out.println("║     ACL-Filtered Document Search                              ║");
        } else {
            System.out.println("║     ACL-Filtered Document Query                               ║");
        }
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Step 1: Resolve username → groups
        System.out.println("━━━ Step 1: Resolve user's groups from '" + GROUPS_INDEX + "' index ━━━");
        System.out.println("  Username: " + username);
        Set<String> userGroups = resolveUserGroups(opensearchUrl, username);

        if (userGroups.isEmpty()) {
            System.out.println("  ⚠ No groups found for '" + username + "' in the groups index.");
            System.out.println("    Run sync first: java CmisGroupSync.java sync \"bearer TOKEN\"");
            return;
        }

        System.out.println("  Groups found: " + userGroups.size());
        for (String g : userGroups) {
            System.out.println("    [group] " + g);
        }
        System.out.println();

        // Step 2: Build ACL token set (username + all groups)
        Set<String> aclTokens = new LinkedHashSet<>();
        aclTokens.add(username);
        aclTokens.addAll(userGroups);

        System.out.println("━━━ Step 2: Query documents with ACL filter ━━━");
        System.out.println("  ACL tokens (" + aclTokens.size() + "): " + aclTokens);
        if (isSearch) {
            System.out.println("  Search terms: \"" + searchTerms + "\"");
        }
        System.out.println();

        // Step 3: Count accessible documents
        long accessibleCount;
        if (isSearch) {
            accessibleCount = countWithAclAndSearch(opensearchUrl, username, aclTokens, searchTerms);
        } else {
            accessibleCount = countWithAcl(opensearchUrl, username, aclTokens);
        }
        System.out.println("  Accessible documents: " + accessibleCount);
        System.out.println();

        // Step 4: Show sample documents
        System.out.println("━━━ Step 3: Accessible documents ━━━");
        if (isSearch) {
            showSearchResults(opensearchUrl, username, aclTokens, searchTerms, 20);
        } else {
            showSampleDocs(opensearchUrl, username, aclTokens, 20);
        }

        // Summary
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║  Result                                                       ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  User:              %-40s║%n", username);
        System.out.printf("║  Groups:            %-40d║%n", userGroups.size());
        System.out.printf("║  Accessible docs:   %-40d║%n", accessibleCount);
        if (isSearch) {
            System.out.printf("║  Search terms:      %-40s║%n", "\"" + searchTerms + "\"");
        }
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
    }

    /**
     * Query the manifoldcf-groups index to find all groups a user belongs to.
     * Returns the set of acl_token values (group names as stored in document ACLs).
     */
    static Set<String> resolveUserGroups(String opensearchUrl, String username) {
        Set<String> groups = new LinkedHashSet<>();
        try {
            // Query: find all group documents where 'members' contains this username
            String query = "{\"size\":200,\"_source\":[\"acl_token\",\"members\"],"
                    + "\"query\":{\"term\":{\"members\":\"" + escapeJson(username) + "\"}}}";

            String response = httpPost(opensearchUrl + "/" + GROUPS_INDEX + "/_search", query, null);
            if (response == null) return groups;

            // Parse hits → extract acl_token values
            int hitsIdx = response.indexOf("\"hits\"");
            if (hitsIdx < 0) return groups;

            int searchFrom = hitsIdx;
            while (true) {
                int aclIdx = response.indexOf("\"acl_token\"", searchFrom);
                if (aclIdx < 0) break;

                String token = extractJsonField(response.substring(aclIdx - 1), "acl_token");
                if (token == null) break;

                groups.add(token);
                searchFrom = aclIdx + 15;
            }
        } catch (Exception e) {
            System.out.println("  Error querying groups: " + e.getMessage());
        }
        return groups;
    }

    // =========================================================================
    //  Alfresco REST API helpers
    // =========================================================================

    /**
     * List all groups from Alfresco REST API.
     * Returns map: groupId → displayName
     */
    static Map<String, String> listAlfrescoGroups(String bearerToken) {
        Map<String, String> groups = new LinkedHashMap<>();
        try {
            int skipCount = 0;
            int maxItems = 100;
            boolean hasMore = true;

            while (hasMore) {
                String url = ALFRESCO_API + "/groups?skipCount=" + skipCount + "&maxItems=" + maxItems;
                String response = httpGet(url, bearerToken, "application/json");

                if (response == null || response.isEmpty()) {
                    System.out.println("  ✗ Empty response from Alfresco groups API");
                    break;
                }

                // Check for error
                if (response.contains("\"error\"")) {
                    String statusCode = extractJsonField(response, "statusCode");
                    String briefSummary = extractJsonField(response, "briefSummary");
                    System.out.println("  ✗ Alfresco API error: " + statusCode + " - " + briefSummary);
                    return groups; // Return empty — caller will use fallback
                }

                // Parse entries: {"list":{"entries":[{"entry":{"id":"GROUP_xxx","displayName":"..."}}]}}
                // Extract group IDs from the response
                int searchFrom = 0;
                int foundInPage = 0;
                while (true) {
                    // Find next "entry" block
                    int entryIdx = response.indexOf("\"entry\"", searchFrom);
                    if (entryIdx < 0) break;

                    int braceStart = response.indexOf('{', entryIdx + 7);
                    if (braceStart < 0) break;
                    int braceEnd = findMatchingBrace(response, braceStart);
                    if (braceEnd < 0) break;

                    String entryJson = response.substring(braceStart, braceEnd + 1);
                    String groupId = extractJsonField(entryJson, "id");
                    String displayName = extractJsonField(entryJson, "displayName");

                    if (groupId != null) {
                        groups.put(groupId, displayName != null ? displayName : groupId);
                        foundInPage++;
                    }

                    searchFrom = braceEnd + 1;
                }

                // Check pagination
                String hasMoreStr = extractJsonField(response, "hasMoreItems");
                hasMore = "true".equals(hasMoreStr) && foundInPage > 0;
                skipCount += maxItems;
                System.out.print(".");
            }
            if (!groups.isEmpty()) System.out.println(" done");

        } catch (Exception e) {
            System.out.println("  ✗ Error listing Alfresco groups: " + e.getMessage());
        }
        return groups;
    }

    /**
     * Get members of a specific Alfresco group.
     * Recursively resolves sub-groups.
     * Returns set of usernames (lowercased).
     */
    static Set<String> getGroupMembers(String bearerToken, String groupId) {
        return getGroupMembersRecursive(bearerToken, groupId, new LinkedHashSet<>(), 0);
    }

    static Set<String> getGroupMembersRecursive(String bearerToken, String groupId,
                                                 Set<String> visited, int depth) {
        Set<String> members = new LinkedHashSet<>();
        if (depth > 5 || visited.contains(groupId)) return members; // Prevent infinite loops
        visited.add(groupId);

        try {
            int skipCount = 0;
            int maxItems = 100;
            boolean hasMore = true;

            while (hasMore) {
                String url = ALFRESCO_API + "/groups/" + URLEncoder.encode(groupId, "UTF-8")
                        + "/members?skipCount=" + skipCount + "&maxItems=" + maxItems;
                String response = httpGet(url, bearerToken, "application/json");

                if (response == null || response.contains("\"error\"")) break;

                int searchFrom = 0;
                int foundInPage = 0;
                while (true) {
                    int entryIdx = response.indexOf("\"entry\"", searchFrom);
                    if (entryIdx < 0) break;

                    int braceStart = response.indexOf('{', entryIdx + 7);
                    if (braceStart < 0) break;
                    int braceEnd = findMatchingBrace(response, braceStart);
                    if (braceEnd < 0) break;

                    String entryJson = response.substring(braceStart, braceEnd + 1);
                    String memberId = extractJsonField(entryJson, "id");
                    String memberType = extractJsonField(entryJson, "memberType");

                    if (memberId != null) {
                        if ("PERSON".equals(memberType)) {
                            members.add(memberId.toLowerCase());
                        } else if ("GROUP".equals(memberType)) {
                            // Recursively resolve sub-group
                            Set<String> subMembers = getGroupMembersRecursive(
                                    bearerToken, memberId, visited, depth + 1);
                            members.addAll(subMembers);
                        }
                        foundInPage++;
                    }

                    searchFrom = braceEnd + 1;
                }

                String hasMoreStr = extractJsonField(response, "hasMoreItems");
                hasMore = "true".equals(hasMoreStr) && foundInPage > 0;
                skipCount += maxItems;
            }
        } catch (Exception e) {
            System.out.println("    Error getting members for " + groupId + ": " + e.getMessage());
        }
        return members;
    }

    /**
     * Get groups for a specific person via Alfresco REST API.
     * Returns set of ACL tokens (lowercased group IDs).
     */
    static Set<String> getPersonGroups(String bearerToken, String personId) {
        Set<String> groups = new LinkedHashSet<>();
        try {
            String url = ALFRESCO_API + "/people/" + URLEncoder.encode(personId, "UTF-8") + "/groups";
            String response = httpGet(url, bearerToken, "application/json");

            if (response == null || response.contains("\"error\"")) return groups;

            int searchFrom = 0;
            while (true) {
                int entryIdx = response.indexOf("\"entry\"", searchFrom);
                if (entryIdx < 0) break;

                int braceStart = response.indexOf('{', entryIdx + 7);
                if (braceStart < 0) break;
                int braceEnd = findMatchingBrace(response, braceStart);
                if (braceEnd < 0) break;

                String entryJson = response.substring(braceStart, braceEnd + 1);
                String groupId = extractJsonField(entryJson, "id");

                if (groupId != null) {
                    groups.add(groupId.toLowerCase());
                }

                searchFrom = braceEnd + 1;
            }
        } catch (Exception e) {
            System.out.println("    Error getting groups for " + personId + ": " + e.getMessage());
        }
        return groups;
    }

    // =========================================================================
    //  OpenSearch: Document index helpers
    // =========================================================================

    /**
     * Collect all unique group ACL tokens from the document index (tokens starting with "group_").
     */
    static Set<String> collectGroupTokensFromDocs(String opensearchUrl) {
        Set<String> groupTokens = new LinkedHashSet<>();
        try {
            String query = "{\"size\":0,\"aggs\":{\"tokens\":{\"terms\":{\"field\":\"allow_token_document\",\"size\":200}}}}";
            String response = httpPost(opensearchUrl + "/" + DOCS_INDEX + "/_search", query, null);
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
                String key = extractJsonField(response.substring(keyIdx - 1), "key");
                if (key == null) break;
                if (key.startsWith("group_")) {
                    groupTokens.add(key);
                }
                searchFrom = keyIdx + 10;
            }
        } catch (Exception e) {
            System.out.println("  Error collecting group tokens: " + e.getMessage());
        }
        return groupTokens;
    }

    /**
     * Collect all unique user ACL tokens from the document index (tokens NOT starting with "group_").
     */
    static Set<String> collectUserTokensFromDocs(String opensearchUrl) {
        Set<String> userTokens = new LinkedHashSet<>();
        try {
            String query = "{\"size\":0,\"aggs\":{\"tokens\":{\"terms\":{\"field\":\"allow_token_document\",\"size\":200}}}}";
            String response = httpPost(opensearchUrl + "/" + DOCS_INDEX + "/_search", query, null);
            if (response == null) return userTokens;

            int bucketsIdx = response.indexOf("\"buckets\"");
            if (bucketsIdx < 0) return userTokens;
            int arrayStart = response.indexOf('[', bucketsIdx);
            if (arrayStart < 0) return userTokens;
            int arrayEnd = findMatchingBracket(response, arrayStart);

            int searchFrom = arrayStart;
            while (searchFrom < arrayEnd) {
                int keyIdx = response.indexOf("\"key\"", searchFrom);
                if (keyIdx < 0 || keyIdx > arrayEnd) break;
                String key = extractJsonField(response.substring(keyIdx - 1), "key");
                if (key == null) break;
                if (!key.startsWith("group_") && !key.equals("__nosecurity__")) {
                    userTokens.add(key);
                }
                searchFrom = keyIdx + 10;
            }
        } catch (Exception e) {
            System.out.println("  Error collecting user tokens: " + e.getMessage());
        }
        return userTokens;
    }

    // =========================================================================
    //  OpenSearch: Groups index management
    // =========================================================================

    /**
     * Create the manifoldcf-groups index with proper mapping.
     */
    static void createGroupsIndex(String opensearchUrl) {
        try {
            // Delete existing index if it exists
            httpDelete(opensearchUrl + "/" + GROUPS_INDEX);

            // Create with mapping
            String mapping = "{"
                    + "\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0},"
                    + "\"mappings\":{\"properties\":{"
                    + "\"acl_token\":{\"type\":\"keyword\"},"
                    + "\"display_name\":{\"type\":\"text\"},"
                    + "\"members\":{\"type\":\"keyword\"},"
                    + "\"member_count\":{\"type\":\"integer\"},"
                    + "\"synced_at\":{\"type\":\"date\"}"
                    + "}}}";

            String response = httpPut(opensearchUrl + "/" + GROUPS_INDEX, mapping);
            if (response != null && response.contains("\"acknowledged\":true")) {
                System.out.println("  ✓ Created index '" + GROUPS_INDEX + "'");
            } else {
                System.out.println("  ⚠ Index creation response: " + response);
            }
        } catch (Exception e) {
            System.out.println("  Error creating index: " + e.getMessage());
        }
    }

    /**
     * Index a single group document.
     */
    static boolean indexGroup(String opensearchUrl, String aclToken, Set<String> members) {
        try {
            StringBuilder doc = new StringBuilder();
            doc.append("{\"acl_token\":\"").append(escapeJson(aclToken)).append("\",");
            doc.append("\"display_name\":\"").append(escapeJson(aclToken)).append("\",");
            doc.append("\"members\":[");
            boolean first = true;
            for (String m : members) {
                if (!first) doc.append(",");
                doc.append("\"").append(escapeJson(m)).append("\"");
                first = false;
            }
            doc.append("],");
            doc.append("\"member_count\":").append(members.size()).append(",");
            doc.append("\"synced_at\":\"").append(java.time.Instant.now().toString()).append("\"");
            doc.append("}");

            // Use acl_token as document _id for idempotent updates
            String encodedId = URLEncoder.encode(aclToken, "UTF-8");
            String response = httpPut(opensearchUrl + "/" + GROUPS_INDEX + "/_doc/" + encodedId, doc.toString());

            if (response != null && (response.contains("\"created\"") || response.contains("\"updated\""))) {
                return true;
            }
            return response != null;
        } catch (Exception e) {
            System.out.println("  Error indexing group " + aclToken + ": " + e.getMessage());
            return false;
        }
    }

    // =========================================================================
    //  OpenSearch: ACL-filtered document queries
    // =========================================================================

    static long countWithAcl(String opensearchUrl, String username, Set<String> aclTokens) {
        try {
            String query = buildAclCountQuery(username, aclTokens, null);
            String response = httpPost(opensearchUrl + "/" + DOCS_INDEX + "/_count", query, null);
            if (response == null) return -1;
            String count = extractJsonField(response, "count");
            return count != null ? Long.parseLong(count) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    static long countWithAclAndSearch(String opensearchUrl, String username,
                                       Set<String> aclTokens, String searchTerms) {
        try {
            String query = buildAclCountQuery(username, aclTokens, searchTerms);
            String response = httpPost(opensearchUrl + "/" + DOCS_INDEX + "/_count", query, null);
            if (response == null) return -1;
            String count = extractJsonField(response, "count");
            return count != null ? Long.parseLong(count) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    static void showSampleDocs(String opensearchUrl, String username, Set<String> aclTokens, int maxDocs) {
        showSearchResults(opensearchUrl, username, aclTokens, null, maxDocs);
    }

    static void showSearchResults(String opensearchUrl, String username,
                                   Set<String> aclTokens, String searchTerms, int maxDocs) {
        try {
            String query = buildAclSearchQuery(username, aclTokens, searchTerms, maxDocs);
            String response = httpPost(opensearchUrl + "/" + DOCS_INDEX + "/_search", query, null);
            if (response == null) {
                System.out.println("  No response from OpenSearch");
                return;
            }

            // Parse total hits
            String totalStr = extractNestedTotal(response);
            System.out.println("  Total accessible: " + (totalStr != null ? totalStr : "unknown"));
            System.out.println();

            // Parse hits → show documents
            int docNum = 0;
            int firstHitsIdx = response.indexOf("\"hits\"");
            if (firstHitsIdx < 0) return;

            int searchFrom = firstHitsIdx;
            while (docNum < maxDocs) {
                int idIdx = response.indexOf("\"_id\"", searchFrom);
                if (idIdx < 0) break;

                String docId = extractJsonField(response.substring(idIdx - 1), "_id");
                if (docId == null) break;
                docNum++;

                // Extract readable path from _id
                String displayName = docId;
                int contentPathIdx = docId.indexOf("contentPath=");
                if (contentPathIdx >= 0) {
                    String path = docId.substring(contentPathIdx + 12);
                    path = path.replace("+", " ");
                    try { path = java.net.URLDecoder.decode(path, "UTF-8"); } catch (Exception ignored) {}
                    displayName = path;
                }

                // Extract ACL tokens for this doc
                int sourceIdx = response.indexOf("\"_source\"", idIdx);
                String aclInfo = "";
                if (sourceIdx >= 0 && sourceIdx < response.indexOf("\"_id\"", idIdx + 10)) {
                    // Try to extract allow_token_document
                    int srcBrace = response.indexOf('{', sourceIdx);
                    if (srcBrace >= 0) {
                        int srcEnd = findMatchingBrace(response, srcBrace);
                        if (srcEnd > 0) {
                            String src = response.substring(srcBrace, srcEnd + 1);
                            String title = extractJsonField(src, "file_title");
                            if (title != null && !title.isEmpty()) {
                                displayName = title;
                            }
                        }
                    }
                }

                System.out.println("  " + docNum + ". " + displayName);
                searchFrom = idIdx + docId.length() + 10;
            }

            if (docNum == 0) {
                System.out.println("  No documents found");
            }
        } catch (Exception e) {
            System.out.println("  Error: " + e.getMessage());
        }
    }

    // =========================================================================
    //  OpenSearch Query Builders
    // =========================================================================

    /**
     * Build an ACL-filtered count query.
     * Optionally includes full-text search terms.
     */
    static String buildAclCountQuery(String username, Set<String> aclTokens, String searchTerms) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"query\":{\"bool\":{");

        // Must clause: full-text search (if provided)
        if (searchTerms != null && !searchTerms.isEmpty()) {
            sb.append("\"must\":[{\"multi_match\":{\"query\":\"")
              .append(escapeJson(searchTerms))
              .append("\",\"fields\":[\"content\",\"file_title\",\"file_description\"]}}],");
        }

        // Filter clause: ACL tokens
        sb.append("\"filter\":{\"bool\":{\"should\":[");
        boolean first = true;
        for (String token : aclTokens) {
            if (!first) sb.append(",");
            sb.append("{\"term\":{\"allow_token_document\":\"").append(escapeJson(token)).append("\"}}");
            first = false;
        }
        sb.append("],\"minimum_should_match\":1,\"must_not\":[");
        sb.append("{\"term\":{\"deny_token_document\":\"").append(escapeJson(username)).append("\"}}");
        sb.append("]}}}}}");
        return sb.toString();
    }

    /**
     * Build an ACL-filtered search query with results.
     */
    static String buildAclSearchQuery(String username, Set<String> aclTokens,
                                       String searchTerms, int size) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"size\":").append(size);
        sb.append(",\"_source\":[\"file_title\",\"allow_token_document\"]");
        sb.append(",\"query\":{\"bool\":{");

        if (searchTerms != null && !searchTerms.isEmpty()) {
            sb.append("\"must\":[{\"multi_match\":{\"query\":\"")
              .append(escapeJson(searchTerms))
              .append("\",\"fields\":[\"content\",\"file_title\",\"file_description\"]}}],");
        }

        sb.append("\"filter\":{\"bool\":{\"should\":[");
        boolean first = true;
        for (String token : aclTokens) {
            if (!first) sb.append(",");
            sb.append("{\"term\":{\"allow_token_document\":\"").append(escapeJson(token)).append("\"}}");
            first = false;
        }
        sb.append("],\"minimum_should_match\":1,\"must_not\":[");
        sb.append("{\"term\":{\"deny_token_document\":\"").append(escapeJson(username)).append("\"}}");
        sb.append("]}}}}}");
        return sb.toString();
    }

    // =========================================================================
    //  HTTP Helpers
    // =========================================================================

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
            if (status == 401 || status == 403) {
                return "{\"error\":{\"statusCode\":" + status + ",\"briefSummary\":\"Access denied\"}}";
            }
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

    static String httpPut(String urlStr, String jsonBody) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("PUT");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        // Accept 200, 201 (created), and 404 (for delete check)
        if (status >= 400 && status != 404) {
            String errorBody = readStream(conn.getErrorStream());
            throw new RuntimeException("HTTP " + status + ": " + errorBody);
        }

        return readStream(conn.getInputStream());
    }

    static void httpDelete(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("DELETE");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        // Ignore response — index may not exist
        conn.getResponseCode();
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

    // =========================================================================
    //  JSON Helpers (no external libraries)
    // =========================================================================

    static String extractJsonField(String json, String field) {
        String pattern = "\"" + field + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;

        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return null;

        int valueStart = colonIdx + 1;
        while (valueStart < json.length() && json.charAt(valueStart) == ' ') valueStart++;
        if (valueStart >= json.length()) return null;

        char firstChar = json.charAt(valueStart);

        if (firstChar == '"') {
            int pos = valueStart + 1;
            while (pos < json.length()) {
                if (json.charAt(pos) == '"' && json.charAt(pos - 1) != '\\') {
                    return json.substring(valueStart + 1, pos);
                }
                pos++;
            }
            return null;
        } else if (firstChar == '[' || firstChar == '{') {
            return null;
        } else {
            int end = valueStart;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != ']') {
                end++;
            }
            return json.substring(valueStart, end).trim();
        }
    }

    static String extractNestedTotal(String response) {
        // Parse: "total":{"value":5,"relation":"eq"}
        int totalIdx = response.indexOf("\"total\"");
        if (totalIdx < 0) return null;
        int braceStart = response.indexOf('{', totalIdx);
        if (braceStart < 0) return null;
        int braceEnd = findMatchingBrace(response, braceStart);
        if (braceEnd < 0) return null;
        String totalJson = response.substring(braceStart, braceEnd + 1);
        return extractJsonField(totalJson, "value");
    }

    static int findMatchingBrace(String s, int openIdx) {
        if (openIdx < 0 || openIdx >= s.length() || s.charAt(openIdx) != '{') return -1;
        int depth = 0;
        boolean inString = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inString = !inString;
            if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') { depth--; if (depth == 0) return i; }
            }
        }
        return -1;
    }

    static int findMatchingBracket(String s, int openIdx) {
        if (openIdx < 0 || openIdx >= s.length() || s.charAt(openIdx) != '[') return -1;
        int depth = 0;
        boolean inString = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inString = !inString;
            if (!inString) {
                if (c == '[') depth++;
                else if (c == ']') { depth--; if (depth == 0) return i; }
            }
        }
        return -1;
    }

    static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // =========================================================================
    //  SSL Helper
    // =========================================================================

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
