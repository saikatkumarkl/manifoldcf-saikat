/* $Id$ */

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.manifoldcf.crawler.connectors.cmis;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.manifoldcf.crawler.system.Logging;

/**
 * Syncs group membership data from vendor REST API to an OpenSearch index
 * during the ManifoldCF crawl. This runs inline with document processing:
 * when a document's ACL tokens are extracted, any new group tokens are
 * resolved via the configured vendor REST API and their members are indexed in the
 * {@code manifold_{repoName}_authorities} OpenSearch index.
 *
 * <p>Index naming convention:</p>
 * <ul>
 *   <li>Authorities index: {@code manifold_{repoConnectionName}_authorities}</li>
 *   <li>The repoConnectionName is sanitized: lowercased, spaces→underscores, special chars removed</li>
 * </ul>
 *
 * <p>Architecture:</p>
 * <pre>
 *   Document crawl → extractAndSetAcl() → resolveAcesToTokens()
 *                                              ↓
 *                                   for each group_ token:
 *                                              ↓
 *                               CmisGroupMembershipSyncer.syncGroupTokens()
 *                                              ↓
 *                               Vendor REST API: /groups/{id}/members
 *                                              ↓
 *                               OpenSearch: PUT manifold_{repo}_authorities/_doc/{token}
 * </pre>
 *
 * <p>Configuration via environment variables:</p>
 * <ul>
 *   <li>{@code MCF_OPENSEARCH_URL} – OpenSearch base URL (default: http://localhost:9200)</li>
 *   <li>{@code MCF_GROUP_SYNC_ENABLED} – set to "false" to disable (default: true)</li>
 * </ul>
 *
 * <p>The syncer maintains an in-memory cache of already-resolved groups so
 * each group is only queried once per crawl session.</p>
 */
public class CmisGroupMembershipSyncer {

  private static final int MAX_RECURSION_DEPTH = 5;
  private static final int HTTP_CONNECT_TIMEOUT = 15000;
  private static final int HTTP_READ_TIMEOUT = 30000;

  /** Base URL for the CMIS server (protocol://server:port) */
  private final String baseUrl;

  /** CMIS vendor type (alfresco, sharepoint, filenet, opentext, nuxeo, hptrim, other) */
  private final String vendor;

  /** Configured group list API path (relative to baseUrl) */
  private final String groupApiPath;

  /** Configured group members API path template with {groupId} placeholder */
  private final String groupMembersApiPath;

  /** Authorities index name — must be provided by the admin app, not auto-generated */
  private final String authoritiesIndex;

  /** OpenSearch base URL, e.g. http://localhost:9200 */
  private final String opensearchUrl;

  /** Basic auth header value for Alfresco REST API */
  private final String basicAuthHeader;

  /** Cache of groups already resolved in this crawl session */
  private final Set<String> resolvedGroups = new HashSet<>();

  /** Whether the groups index has been initialized */
  private boolean indexInitialized = false;

  /** Whether this syncer is enabled */
  private final boolean enabled;

  /** Whether SSL trust-all has been set up */
  private static boolean sslInitialized = false;

  /**
   * Create a new syncer.
   *
   * @param protocol  CMIS server protocol (http or https)
   * @param server    CMIS server hostname
   * @param port      CMIS server port
   * @param username  CMIS/Alfresco username (used for Basic auth to REST API)
   * @param password  CMIS/Alfresco password
   * @param vendor    CMIS vendor type (alfresco, sharepoint, etc.) — may be null
   * @param groupApiPath  Group list API path (relative to baseUrl) — may be null/empty
   * @param groupMembersApiPath  Group members API path template with {groupId} — may be null/empty
   * @param repoConnectionName  Repository connection name (used for index naming) — may be null
   */
  public CmisGroupMembershipSyncer(String protocol, String server, String port,
                                    String username, String password,
                                    String vendor, String groupApiPath, String groupMembersApiPath,
                                    String authorityIndexName) {
    this.baseUrl = protocol + "://" + server + ":" + port;

    // Authority index name MUST be provided by the admin app.
    // No auto-generated "manifold_*" names — only admin-app-provided names are allowed.
    if (authorityIndexName != null && !authorityIndexName.isEmpty()) {
      // Reject any index name starting with "manifold_" — these are legacy auto-generated names
      if (authorityIndexName.toLowerCase().startsWith("manifold_") || authorityIndexName.toLowerCase().startsWith("manifoldcf")) {
        Logging.connectors.warn("CMIS GroupSync: Rejected authority index name '" + authorityIndexName
            + "' — names starting with 'manifold_' or 'manifoldcf' are not allowed. "
            + "Index names must be provided by the admin app.");
        this.authoritiesIndex = null;
      } else {
        this.authoritiesIndex = authorityIndexName;
      }
    } else {
      this.authoritiesIndex = null;
    }
    this.vendor = (vendor != null && !vendor.isEmpty()) ? vendor : "other";

    // Use configured paths or fall back to Alfresco defaults for backward compatibility
    if (groupApiPath != null && !groupApiPath.isEmpty()) {
      this.groupApiPath = groupApiPath;
    } else if ("alfresco".equals(this.vendor)) {
      this.groupApiPath = "/alfresco/api/-default-/public/alfresco/versions/1/groups";
    } else {
      this.groupApiPath = "";
    }

    if (groupMembersApiPath != null && !groupMembersApiPath.isEmpty()) {
      this.groupMembersApiPath = groupMembersApiPath;
    } else if ("alfresco".equals(this.vendor)) {
      this.groupMembersApiPath = "/alfresco/api/-default-/public/alfresco/versions/1/groups/{groupId}/members";
    } else {
      this.groupMembersApiPath = "";
    }

    // OpenSearch URL from environment or default
    String osUrl = System.getenv("MCF_OPENSEARCH_URL");
    this.opensearchUrl = (osUrl != null && !osUrl.isEmpty()) ? osUrl : "http://localhost:9200";

    // Basic auth for Alfresco REST API
    String credentials = username + ":" + password;
    this.basicAuthHeader = "Basic " + Base64.getEncoder()
        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

    // Enable/disable via environment, also disable if no API paths or authority index configured
    String enabledStr = System.getenv("MCF_GROUP_SYNC_ENABLED");
    if ("false".equalsIgnoreCase(enabledStr)) {
      this.enabled = false;
    } else if (this.authoritiesIndex == null) {
      this.enabled = false;
      Logging.connectors.info("CMIS GroupSync: Disabled — no authority index name provided by admin app. "
          + "Group syncing only works when the admin app provides an explicit index name.");
    } else if (this.groupApiPath.isEmpty() || this.groupMembersApiPath.isEmpty()) {
      this.enabled = false;
      Logging.connectors.info("CMIS GroupSync: Disabled — group API paths not configured");
    } else {
      this.enabled = true;
    }

    // Set up SSL trust-all for HTTPS connections (once per JVM)
    if ("https".equalsIgnoreCase(protocol) && !sslInitialized) {
      try {
        trustAllCerts();
        sslInitialized = true;
      } catch (Exception e) {
        Logging.connectors.warn("CMIS GroupSync: Failed to set up trust-all SSL: " + e.getMessage());
      }
    }

    Logging.connectors.info("CMIS GroupSync: Initialized"
        + " | vendor=" + this.vendor
        + " | groupApiPath=" + this.groupApiPath
        + " | groupMembersApiPath=" + this.groupMembersApiPath
        + " | authoritiesIndex=" + this.authoritiesIndex
        + " | opensearch=" + opensearchUrl
        + " | enabled=" + enabled);
  }

  /**
   * Get the authorities index name for this repository connection.
   * @return index name provided by the admin app, or null if not configured
   */
  public String getAuthoritiesIndexName() {
    return authoritiesIndex;
  }

  /**
   * Sanitize a connection name for use as an OpenSearch index name.
   * Lowercases, replaces spaces/special chars with underscores, removes leading underscores.
   */
  static String sanitizeIndexName(String name) {
    return name.toLowerCase()
        .replaceAll("[^a-z0-9_]", "_")
        .replaceAll("_+", "_")
        .replaceAll("^_|_$", "");
  }

  /**
   * Process a list of allow tokens extracted from a document's ACL.
   * For each group token (starting with "group_") that hasn't been resolved
   * yet, query vendor REST API for the group's members and index the
   * group→members mapping in the authorities OpenSearch index.
   *
   * <p>This method is safe to call repeatedly — resolved groups are cached
   * and won't be re-queried.</p>
   *
   * @param allowTokens the list of lowercase ACL allow tokens from resolveAcesToTokens()
   */
  public void syncGroupTokens(List<String> allowTokens) {
    if (!enabled || allowTokens == null || allowTokens.isEmpty()) {
      return;
    }

    // Find new group tokens that haven't been resolved yet
    List<String> newGroupTokens = new ArrayList<>();
    for (String token : allowTokens) {
      if (token.startsWith("group_") && !resolvedGroups.contains(token)) {
        newGroupTokens.add(token);
      }
    }

    if (newGroupTokens.isEmpty()) {
      return;
    }

    // Ensure the groups index exists (once per session)
    if (!indexInitialized) {
      ensureGroupsIndex();
      indexInitialized = true;
    }

    // Resolve each new group
    for (String aclToken : newGroupTokens) {
      try {
        // The ACL token is lowercased (e.g. group_site_asian-football-confederation-afc_siteconsumer)
        // but the Alfresco REST API requires the exact-case group ID
        // (e.g. GROUP_site_Asian-Football-Confederation-AFC_SiteConsumer).
        // We must look up the correct-case ID first.
        String correctCaseGroupId = resolveCorrectCaseGroupId(aclToken);

        Set<String> members;
        if (correctCaseGroupId != null) {
          members = getGroupMembersRecursive(correctCaseGroupId, new HashSet<>(), 0);
          Logging.connectors.info("CMIS GroupSync: Resolved " + aclToken
              + " (actual ID: " + correctCaseGroupId + ") → "
              + members.size() + " members: " + members);
        } else {
          Logging.connectors.warn("CMIS GroupSync: Could not find correct-case group ID for '"
              + aclToken + "' — indexing with 0 members");
          members = new LinkedHashSet<>();
        }

        // Index group→members mapping in OpenSearch
        indexGroupDocument(aclToken, members);
        resolvedGroups.add(aclToken);

      } catch (Exception e) {
        // Don't fail the crawl if group resolution fails — just log and skip
        Logging.connectors.warn("CMIS GroupSync: Failed to resolve group '"
            + aclToken + "': " + e.getMessage());
        // Still mark as resolved to avoid retrying every document
        resolvedGroups.add(aclToken);
      }
    }
  }

  /**
   * Also sync "group_everyone" with all user tokens found in the allow tokens.
   * Called once when the first set of user tokens is discovered.
   *
   * @param userTokens all unique non-group ACL tokens (usernames)
   */
  public void syncEveryoneGroup(Set<String> userTokens) {
    if (!enabled || userTokens == null || userTokens.isEmpty()) {
      return;
    }
    if (resolvedGroups.contains("group_everyone")) {
      return;
    }

    if (!indexInitialized) {
      ensureGroupsIndex();
      indexInitialized = true;
    }

    try {
      indexGroupDocument("group_everyone", userTokens);
      resolvedGroups.add("group_everyone");
      Logging.connectors.info("CMIS GroupSync: Indexed group_everyone with "
          + userTokens.size() + " members");
    } catch (Exception e) {
      Logging.connectors.warn("CMIS GroupSync: Failed to index group_everyone: " + e.getMessage());
    }
  }

  /**
   * Clear the resolved groups cache. Call this when starting a fresh crawl.
   */
  public void resetCache() {
    resolvedGroups.clear();
    indexInitialized = false;
  }

  /**
   * Get the number of groups resolved so far.
   */
  public int getResolvedGroupCount() {
    return resolvedGroups.size();
  }

  /**
   * Proactively sync ALL groups from the vendor REST API into the authorities index.
   *
   * <p>This method is called when a job uses the special CMIS query marker
   * {@code __SYNC_AUTHORITIES_ONLY__}. Instead of waiting to encounter groups
   * during document ACL processing, this fetches the complete list of groups
   * from the vendor API and resolves each one.</p>
   *
   * <p>For Alfresco, this paginates through {@code GET /groups?skipCount=...&maxItems=...}
   * to enumerate all groups, then resolves members for each group using
   * the configured members API path.</p>
   */
  public void syncAllGroups() {
    if (!enabled) {
      Logging.connectors.info("CMIS GroupSync: syncAllGroups() skipped — syncer not enabled "
          + "(vendor='" + vendor + "', groupApiUrl='" + groupApiPath + "')");
      return;
    }

    Logging.connectors.info("CMIS GroupSync: Starting full group sync for all groups...");

    if (!indexInitialized) {
      ensureGroupsIndex();
      indexInitialized = true;
    }

    int totalGroups = 0;
    int totalMembers = 0;

    try {
      int skipCount = 0;
      int maxItems = 100;
      boolean hasMore = true;

      while (hasMore) {
        String url = baseUrl + groupApiPath + "?skipCount=" + skipCount + "&maxItems=" + maxItems;
        String response = httpGet(url);
        if (response == null || response.contains("\"error\"")) {
          Logging.connectors.warn("CMIS GroupSync: Error fetching groups page at skipCount=" + skipCount);
          break;
        }

        int searchFrom = 0;
        int foundInPage = 0;

        // Parse group entries (Alfresco format: {"list":{"entries":[{"entry":{"id":"GROUP_..."}}]}})
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
            foundInPage++;
            // Convert to ACL token format (lowercase)
            String aclToken = groupId.toLowerCase();
            if (!aclToken.startsWith("group_")) {
              aclToken = "group_" + aclToken;
            }

            // Skip if already resolved
            if (resolvedGroups.contains(aclToken)) {
              continue;
            }

            try {
              Set<String> members = getGroupMembersRecursive(groupId, new HashSet<>(), 0);
              indexGroupDocument(aclToken, members);
              resolvedGroups.add(aclToken);
              totalGroups++;
              totalMembers += members.size();
              Logging.connectors.info("CMIS GroupSync: [" + totalGroups + "] "
                  + aclToken + " → " + members.size() + " members");
            } catch (Exception e) {
              Logging.connectors.warn("CMIS GroupSync: Failed to sync group '"
                  + groupId + "': " + e.getMessage());
              resolvedGroups.add(aclToken);
            }
          }

          searchFrom = braceEnd + 1;
        }

        String hasMoreStr = extractJsonField(response, "hasMoreItems");
        hasMore = "true".equals(hasMoreStr) && foundInPage > 0;
        skipCount += maxItems;
      }
    } catch (Exception e) {
      Logging.connectors.warn("CMIS GroupSync: Error during full group sync: " + e.getMessage());
    }

    Logging.connectors.info("CMIS GroupSync: Full group sync complete — "
        + totalGroups + " groups, " + totalMembers + " total member entries");
  }

  // =========================================================================
  //  Group member resolution (vendor-aware, uses configured API paths)
  // =========================================================================

  /**
   * Resolve the correct-case group ID from a lowercased ACL token.
   *
   * <p>For Alfresco: The REST API is case-sensitive for group IDs, but ACL
   * tokens stored in OpenSearch are lowercased. This method uses the
   * configured groups API to search for the group and recover the
   * original-case ID.</p>
   *
   * <p>For other vendors: Uses the ACL token suffix directly (uppercase
   * prefix convention may differ).</p>
   *
   * @param aclToken the lowercased ACL token (e.g. "group_site_xxx_siteconsumer")
   * @return the correct-case group ID, or null if not found
   */
  private String resolveCorrectCaseGroupId(String aclToken) {
    if (aclToken == null || !aclToken.startsWith("group_")) {
      return null;
    }

    String suffix = aclToken.substring("group_".length());

    if ("alfresco".equals(vendor)) {
      return resolveCorrectCaseGroupIdAlfresco(aclToken, suffix);
    } else {
      // For non-Alfresco vendors, try the suffix as-is (many APIs are case-insensitive)
      // Also try with common prefixes
      String candidateId = suffix;
      try {
        // Try direct lookup: baseUrl + groupApiPath + "/" + candidateId
        String url = baseUrl + groupApiPath + "/" + URLEncoder.encode(candidateId, "UTF-8");
        String response = httpGet(url);
        if (response != null && !response.contains("\"error\"") && !response.contains("\"statusCode\"")) {
          String actualId = extractJsonField(response, "id");
          if (actualId != null) return actualId;
          actualId = extractJsonField(response, "Id");
          if (actualId != null) return actualId;
          return candidateId;
        }
      } catch (Exception e) {
        // Fall through
      }
      // Return the suffix as-is — the members API might still work
      Logging.connectors.info("CMIS GroupSync: Using ACL token suffix '" + suffix
          + "' as group ID for vendor '" + vendor + "'");
      return suffix;
    }
  }

  /**
   * Alfresco-specific: Resolve correct-case group ID using Alfresco REST API.
   */
  private String resolveCorrectCaseGroupIdAlfresco(String aclToken, String suffix) {
    // Attempt 1: Direct lookup with "GROUP_" prefix (works if suffix case happens to match)
    String candidateId = "GROUP_" + suffix;
    try {
      String url = baseUrl + groupApiPath + "/" + URLEncoder.encode(candidateId, "UTF-8");
      String response = httpGet(url);
      if (response != null && !response.contains("\"error\"")) {
        String actualId = extractJsonField(response, "id");
        if (actualId != null) {
          return actualId;
        }
        return candidateId;
      }
    } catch (Exception e) {
      // 404 or other error — fall through to search
    }

    // Attempt 2: Search all groups and match by lowercased ID
    try {
      int skipCount = 0;
      int maxItems = 100;
      boolean hasMore = true;

      while (hasMore) {
        String url = baseUrl + groupApiPath + "?skipCount=" + skipCount + "&maxItems=" + maxItems;
        String response = httpGet(url);
        if (response == null || response.contains("\"error\"")) {
          break;
        }

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
          String groupId = extractJsonField(entryJson, "id");

          if (groupId != null) {
            String normalizedId = groupId.toLowerCase();
            if (normalizedId.equals(aclToken)) {
              Logging.connectors.info("CMIS GroupSync: Mapped ACL token '"
                  + aclToken + "' → group ID '" + groupId + "'");
              return groupId;
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
      Logging.connectors.warn("CMIS GroupSync: Error searching for correct-case ID of '"
          + aclToken + "': " + e.getMessage());
    }

    Logging.connectors.warn("CMIS GroupSync: Could not resolve correct-case group ID for '"
        + aclToken + "' after searching all groups");
    return null;
  }

  /**
   * Recursively get all PERSON members of a group.
   * Sub-groups are followed up to MAX_RECURSION_DEPTH levels.
   * Uses the configured groupMembersApiPath with {groupId} replacement.
   *
   * @param groupId  group ID (e.g. GROUP_site_xxx_SiteManager for Alfresco)
   * @param visited  set of already-visited group IDs (prevents cycles)
   * @param depth    current recursion depth
   * @return set of usernames (lowercased)
   */
  private Set<String> getGroupMembersRecursive(String groupId, Set<String> visited, int depth) {
    Set<String> members = new LinkedHashSet<>();
    if (depth > MAX_RECURSION_DEPTH || visited.contains(groupId)) {
      return members;
    }
    visited.add(groupId);

    try {
      int skipCount = 0;
      int maxItems = 100;
      boolean hasMore = true;

      while (hasMore) {
        // Construct URL from configured path template
        String encodedGroupId = URLEncoder.encode(groupId, "UTF-8");
        String membersPath = groupMembersApiPath
            .replace("{groupId}", encodedGroupId)
            .replace("({groupId})", "(" + encodedGroupId + ")");
        String url = baseUrl + membersPath
            + (membersPath.contains("?") ? "&" : "?")
            + "skipCount=" + skipCount + "&maxItems=" + maxItems;
        String response = httpGet(url);

        if (response == null || response.contains("\"error\"")) {
          break;
        }

        int searchFrom = 0;
        int foundInPage = 0;

        if ("alfresco".equals(vendor)) {
          // Alfresco format: {"list":{"entries":[{"entry":{"id":"...","memberType":"PERSON|GROUP"}}]}}
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
                Set<String> subMembers = getGroupMembersRecursive(memberId, visited, depth + 1);
                members.addAll(subMembers);
              }
              foundInPage++;
            }

            searchFrom = braceEnd + 1;
          }
        } else {
          // Generic format: try to extract user IDs from JSON response
          // Look for objects with "id", "Id", "LoginName", "userId", or "name" fields
          String idField = detectIdField(response);
          if (idField != null) {
            while (true) {
              String pattern = "\"" + idField + "\"";
              int idx = response.indexOf(pattern, searchFrom);
              if (idx < 0) break;

              int colonIdx = response.indexOf(':', idx + pattern.length());
              if (colonIdx < 0) break;

              int valueStart = colonIdx + 1;
              while (valueStart < response.length() && response.charAt(valueStart) == ' ') valueStart++;
              if (valueStart >= response.length()) break;

              if (response.charAt(valueStart) == '"') {
                int valueEnd = valueStart + 1;
                while (valueEnd < response.length()) {
                  if (response.charAt(valueEnd) == '"' && response.charAt(valueEnd - 1) != '\\') {
                    String value = response.substring(valueStart + 1, valueEnd);
                    if (!value.isEmpty()) {
                      members.add(value.toLowerCase());
                      foundInPage++;
                    }
                    break;
                  }
                  valueEnd++;
                }
                searchFrom = valueEnd + 1;
              } else {
                searchFrom = valueStart + 1;
              }
            }
          }
        }

        String hasMoreStr = extractJsonField(response, "hasMoreItems");
        hasMore = "true".equals(hasMoreStr) && foundInPage > 0;
        skipCount += maxItems;
      }
    } catch (Exception e) {
      Logging.connectors.warn("CMIS GroupSync: Error getting members for " + groupId + ": " + e.getMessage());
    }
    return members;
  }

  /**
   * Detect which JSON field name is used for user/member IDs in a response.
   */
  private static String detectIdField(String json) {
    String[] candidates = {"id", "Id", "LoginName", "userId", "loginName", "name", "userName"};
    for (String field : candidates) {
      if (json.contains("\"" + field + "\"")) {
        return field;
      }
    }
    return null;
  }

  // =========================================================================
  //  OpenSearch: Groups index management
  // =========================================================================

  /**
   * Ensure the authorities index exists with the proper mapping.
   * Creates it if it doesn't exist; does nothing if it already exists.
   */
  private void ensureGroupsIndex() {
    try {
      // Check if index exists (HEAD request returns 200 if exists, 404 if not)
      String checkUrl = opensearchUrl + "/" + authoritiesIndex;
      URL url = new URL(checkUrl);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("HEAD");
      conn.setConnectTimeout(HTTP_CONNECT_TIMEOUT);
      conn.setReadTimeout(HTTP_READ_TIMEOUT);
      int status = conn.getResponseCode();
      conn.disconnect();

      if (status == 200) {
        Logging.connectors.info("CMIS GroupSync: Index '" + authoritiesIndex + "' already exists");
        return;
      }

      // Create the index with proper mapping
      // Field name "group_id" must match what ElasticSearchAuthoritiesExpander queries
      String mapping = "{"
          + "\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0},"
          + "\"mappings\":{\"properties\":{"
          + "\"group_id\":{\"type\":\"keyword\"},"
          + "\"display_name\":{\"type\":\"text\"},"
          + "\"members\":{\"type\":\"keyword\"},"
          + "\"member_count\":{\"type\":\"integer\"},"
          + "\"synced_at\":{\"type\":\"date\"}"
          + "}}}";

      String response = httpPut(opensearchUrl + "/" + authoritiesIndex, mapping);
      if (response != null && response.contains("\"acknowledged\":true")) {
        Logging.connectors.info("CMIS GroupSync: Created index '" + authoritiesIndex + "'");
      } else {
        Logging.connectors.warn("CMIS GroupSync: Index creation response: " + response);
      }
    } catch (Exception e) {
      Logging.connectors.warn("CMIS GroupSync: Error ensuring groups index: " + e.getMessage());
    }
  }

  /**
   * Index a single group→members document. Uses the acl_token as the
   * document _id for idempotent upserts.
   */
  private void indexGroupDocument(String aclToken, Set<String> members) {
    try {
      StringBuilder doc = new StringBuilder();
      doc.append("{\"group_id\":\"").append(escapeJson(aclToken)).append("\",");
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
      doc.append("\"synced_at\":\"").append(Instant.now().toString()).append("\"");
      doc.append("}");

      String encodedId = URLEncoder.encode(aclToken, "UTF-8");
      String response = httpPut(opensearchUrl + "/" + authoritiesIndex + "/_doc/" + encodedId, doc.toString());

      if (response == null) {
        Logging.connectors.warn("CMIS GroupSync: Null response indexing " + aclToken);
      }
    } catch (Exception e) {
      Logging.connectors.warn("CMIS GroupSync: Error indexing group " + aclToken + ": " + e.getMessage());
    }
  }

  // =========================================================================
  //  HTTP Helpers
  // =========================================================================

  /**
   * HTTP GET with Basic auth to Alfresco REST API.
   */
  private String httpGet(String urlStr) throws Exception {
    URL url = new URL(urlStr);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(HTTP_CONNECT_TIMEOUT);
    conn.setReadTimeout(HTTP_READ_TIMEOUT);
    conn.setRequestProperty("Authorization", basicAuthHeader);
    conn.setRequestProperty("Accept", "application/json");

    int status = conn.getResponseCode();
    if (status >= 400) {
      String errorBody = readStream(conn.getErrorStream());
      if (status == 401 || status == 403) {
        return "{\"error\":{\"statusCode\":" + status + ",\"briefSummary\":\"Access denied\"}}";
      }
      if (status == 404) {
        return "{\"error\":{\"statusCode\":404,\"briefSummary\":\"Not found\"}}";
      }
      throw new RuntimeException("HTTP " + status + ": " + errorBody);
    }

    return readStream(conn.getInputStream());
  }

  /**
   * HTTP PUT with JSON body (for OpenSearch).
   */
  private String httpPut(String urlStr, String jsonBody) throws Exception {
    URL url = new URL(urlStr);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("PUT");
    conn.setConnectTimeout(HTTP_CONNECT_TIMEOUT);
    conn.setReadTimeout(HTTP_READ_TIMEOUT);
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json");

    try (OutputStream os = conn.getOutputStream()) {
      os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
    }

    int status = conn.getResponseCode();
    if (status >= 400 && status != 404) {
      String errorBody = readStream(conn.getErrorStream());
      throw new RuntimeException("HTTP " + status + ": " + errorBody);
    }

    return readStream(conn.getInputStream());
  }

  private static String readStream(java.io.InputStream is) {
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
  //  JSON Helpers (no external libraries — connector classloader isolation)
  // =========================================================================

  private static String extractJsonField(String json, String field) {
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

  private static int findMatchingBrace(String s, int openIdx) {
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

  private static String escapeJson(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  // =========================================================================
  //  SSL Helper
  // =========================================================================

  /**
   * Trust all SSL certificates. Required for HTTPS connections to Alfresco
   * servers with self-signed or untrusted certificates.
   */
  private static void trustAllCerts() throws Exception {
    TrustManager[] trustAll = new TrustManager[] {
      new X509TrustManager() {
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        public void checkClientTrusted(X509Certificate[] certs, String t) { }
        public void checkServerTrusted(X509Certificate[] certs, String t) { }
      }
    };
    SSLContext sc = SSLContext.getInstance("TLS");
    sc.init(null, trustAll, new SecureRandom());
    HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
    HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
  }
}
