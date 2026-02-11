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
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.manifoldcf.crawler.system.Logging;

/**
 * Generic HTTP-based implementation of {@link GroupMemberResolver} that works
 * with <b>any</b> identity provider or application that exposes group membership
 * via a REST API — including SSO providers (Okta, Azure AD/Entra ID, Auth0,
 * Keycloak, Ping Identity, OneLogin), custom application-layer group services,
 * and CMIS vendor APIs.
 *
 * <p><b>Why this resolver exists:</b> Not all organisations use LDAP/AD.
 * Modern architectures may manage groups in:</p>
 * <ul>
 *   <li><b>SSO providers</b> — Okta, Azure AD, Auth0, Keycloak, Ping</li>
 *   <li><b>Application layer</b> — the CMIS vendor's own database
 *       (e.g., FileNet security, Documentum groups, Nuxeo groups)</li>
 *   <li><b>Custom microservices</b> — internal identity/authorization services</li>
 *   <li><b>SCIM endpoints</b> — standards-based identity provisioning</li>
 * </ul>
 *
 * <p><b>How it works:</b></p>
 * <ol>
 *   <li>For each CMIS ACE principal, {@link #isGroup(String)} calls the
 *       configured REST API URL (with {@code {group}} placeholder replaced).</li>
 *   <li>If the API returns HTTP 200 with member data, the principal is a group
 *       and its members are cached.</li>
 *   <li>If the API returns HTTP 404 or an empty response, the principal is
 *       treated as a user (not a group).</li>
 *   <li>Member usernames are extracted from the JSON response using
 *       configurable field names (supports nested fields like
 *       {@code profile.login} for Okta).</li>
 * </ol>
 *
 * <h3>Configuration via environment variables</h3>
 * <table>
 *   <tr><th>Variable</th><th>Required</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>{@code MCF_HTTP_GROUP_RESOLVER_URL}</td><td>Yes</td><td>—</td>
 *       <td>URL template with {@code {group}} placeholder.<br/>
 *           Example: {@code https://dev-xxx.okta.com/api/v1/groups/{group}/users}</td></tr>
 *   <tr><td>{@code MCF_HTTP_GROUP_RESOLVER_AUTH}</td><td>No</td><td>—</td>
 *       <td>Authorization header value.<br/>
 *           Examples: {@code SSWS your-okta-api-key},
 *           {@code Bearer your-oauth-token}</td></tr>
 *   <tr><td>{@code MCF_HTTP_GROUP_RESOLVER_USERNAME_FIELD}</td><td>No</td>
 *       <td>{@code username}</td>
 *       <td>JSON field containing the username in each member object.
 *           Supports dot notation for nested fields (e.g., {@code profile.login}).
 *           Set to empty string if the response is a plain string array.</td></tr>
 *   <tr><td>{@code MCF_HTTP_GROUP_RESOLVER_MEMBERS_FIELD}</td><td>No</td>
 *       <td><em>(empty — root array)</em></td>
 *       <td>JSON field containing the members array. Leave empty if the response
 *           root is the array. Set to the field name if members are nested
 *           (e.g., {@code value} for Azure AD, {@code members} for SCIM,
 *           {@code Resources} for SCIM 2.0).</td></tr>
 *   <tr><td>{@code MCF_HTTP_GROUP_RESOLVER_SEARCH_URL}</td><td>No</td><td>—</td>
 *       <td>Optional URL template to search for a group by name (returns the
 *           group ID). Used when the members URL requires an ID, not a name.<br/>
 *           Example: {@code https://dev-xxx.okta.com/api/v1/groups?q={group}}<br/>
 *           The group ID is extracted using {@code MCF_HTTP_GROUP_RESOLVER_ID_FIELD}.</td></tr>
 *   <tr><td>{@code MCF_HTTP_GROUP_RESOLVER_ID_FIELD}</td><td>No</td>
 *       <td>{@code id}</td>
 *       <td>JSON field containing the group ID in the search response.</td></tr>
 *   <tr><td>{@code MCF_HTTP_GROUP_RESOLVER_SKIP_PRINCIPALS}</td><td>No</td>
 *       <td>{@code GROUP_EVERYONE,Everyone,#AUTHENTICATED-USERS}</td>
 *       <td>Comma-separated principals to never expand.</td></tr>
 *   <tr><td>{@code MCF_HTTP_GROUP_RESOLVER_TRUST_ALL_CERTS}</td><td>No</td>
 *       <td>{@code false}</td>
 *       <td>Trust all HTTPS certificates (for self-signed certs).</td></tr>
 *   <tr><td>{@code MCF_HTTP_GROUP_RESOLVER_CONNECT_TIMEOUT}</td><td>No</td>
 *       <td>{@code 10000}</td>
 *       <td>HTTP connection timeout in milliseconds.</td></tr>
 *   <tr><td>{@code MCF_HTTP_GROUP_RESOLVER_READ_TIMEOUT}</td><td>No</td>
 *       <td>{@code 15000}</td>
 *       <td>HTTP read timeout in milliseconds.</td></tr>
 * </table>
 *
 * <h3>Example configurations for popular providers</h3>
 *
 * <h4>Okta</h4>
 * <pre>
 * MCF_HTTP_GROUP_RESOLVER_SEARCH_URL=https://dev-xxx.okta.com/api/v1/groups?q={group}&amp;limit=1
 * MCF_HTTP_GROUP_RESOLVER_URL=https://dev-xxx.okta.com/api/v1/groups/{group}/users?limit=200
 * MCF_HTTP_GROUP_RESOLVER_AUTH=SSWS your-okta-api-key
 * MCF_HTTP_GROUP_RESOLVER_USERNAME_FIELD=profile.login
 * MCF_HTTP_GROUP_RESOLVER_MEMBERS_FIELD=
 * MCF_HTTP_GROUP_RESOLVER_ID_FIELD=id
 * </pre>
 *
 * <h4>Azure AD / Entra ID (Microsoft Graph)</h4>
 * <pre>
 * MCF_HTTP_GROUP_RESOLVER_SEARCH_URL=https://graph.microsoft.com/v1.0/groups?$filter=displayName eq '{group}'&amp;$select=id
 * MCF_HTTP_GROUP_RESOLVER_URL=https://graph.microsoft.com/v1.0/groups/{group}/members?$select=userPrincipalName
 * MCF_HTTP_GROUP_RESOLVER_AUTH=Bearer your-oauth-token
 * MCF_HTTP_GROUP_RESOLVER_USERNAME_FIELD=userPrincipalName
 * MCF_HTTP_GROUP_RESOLVER_MEMBERS_FIELD=value
 * MCF_HTTP_GROUP_RESOLVER_ID_FIELD=id
 * </pre>
 *
 * <h4>Keycloak</h4>
 * <pre>
 * MCF_HTTP_GROUP_RESOLVER_SEARCH_URL=https://keycloak.example.com/admin/realms/myrealm/groups?search={group}&amp;exact=true
 * MCF_HTTP_GROUP_RESOLVER_URL=https://keycloak.example.com/admin/realms/myrealm/groups/{group}/members
 * MCF_HTTP_GROUP_RESOLVER_AUTH=Bearer your-admin-token
 * MCF_HTTP_GROUP_RESOLVER_USERNAME_FIELD=username
 * MCF_HTTP_GROUP_RESOLVER_MEMBERS_FIELD=
 * MCF_HTTP_GROUP_RESOLVER_ID_FIELD=id
 * </pre>
 *
 * <h4>SCIM 2.0 (generic — Auth0, Ping Identity, OneLogin, etc.)</h4>
 * <pre>
 * MCF_HTTP_GROUP_RESOLVER_SEARCH_URL=https://scim.example.com/scim/v2/Groups?filter=displayName eq "{group}"
 * MCF_HTTP_GROUP_RESOLVER_URL=https://scim.example.com/scim/v2/Groups/{group}
 * MCF_HTTP_GROUP_RESOLVER_AUTH=Bearer your-scim-token
 * MCF_HTTP_GROUP_RESOLVER_USERNAME_FIELD=display
 * MCF_HTTP_GROUP_RESOLVER_MEMBERS_FIELD=members
 * MCF_HTTP_GROUP_RESOLVER_ID_FIELD=id
 * </pre>
 *
 * <h4>Custom application REST API</h4>
 * <pre>
 * MCF_HTTP_GROUP_RESOLVER_URL=https://internal-api.example.com/groups/{group}/members
 * MCF_HTTP_GROUP_RESOLVER_AUTH=Bearer your-api-token
 * MCF_HTTP_GROUP_RESOLVER_USERNAME_FIELD=username
 * MCF_HTTP_GROUP_RESOLVER_MEMBERS_FIELD=data
 * </pre>
 *
 * <p><b>Thread safety:</b> This class is thread-safe. Caches use
 * {@link ConcurrentHashMap}.</p>
 *
 * @see GroupMemberResolver
 * @see GroupMemberResolverFactory
 */
public class HttpGroupMemberResolver implements GroupMemberResolver {

  /** Regex for extracting a JSON string field value: "fieldName":"value" */
  private static final Pattern JSON_STRING_VALUE =
      Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"");

  /** Regex for extracting simple JSON string from array: "value" */
  private static final Pattern JSON_SIMPLE_STRING =
      Pattern.compile("\"([^\"]+)\"");

  // ====== Configuration ======

  private final String membersUrlTemplate;
  private final String searchUrlTemplate;   // nullable — if null, membersUrl uses group name directly
  private final String authHeader;          // nullable
  private final String usernameField;       // dot notation supported (e.g., "profile.login")
  private final String membersField;        // empty = root array
  private final String idField;             // for search response
  private final boolean trustAllCerts;
  private final int connectTimeout;
  private final int readTimeout;
  private final Set<String> skipPrincipals;

  // ====== Cache ======

  /** Groups resolved: principalId → member usernames. */
  private final Map<String, Set<String>> groupCache = new ConcurrentHashMap<>();

  /** Principals confirmed as non-groups. */
  private final Set<String> knownUsers = ConcurrentHashMap.newKeySet();

  /** Group name → ID mapping (from search URL). */
  private final Map<String, String> groupIdCache = new ConcurrentHashMap<>();

  // ====== State ======

  private volatile boolean available = true;

  /**
   * Create a new HTTP-based group member resolver.
   */
  public HttpGroupMemberResolver(String membersUrlTemplate,
      String searchUrlTemplate, String authHeader, String usernameField,
      String membersField, String idField, boolean trustAllCerts,
      int connectTimeout, int readTimeout, Set<String> skipPrincipals) {
    this.membersUrlTemplate = membersUrlTemplate;
    this.searchUrlTemplate = (searchUrlTemplate != null && !searchUrlTemplate.isEmpty())
        ? searchUrlTemplate : null;
    this.authHeader = (authHeader != null && !authHeader.isEmpty()) ? authHeader : null;
    this.usernameField = usernameField != null ? usernameField : "username";
    this.membersField = membersField != null ? membersField : "";
    this.idField = idField != null ? idField : "id";
    this.trustAllCerts = trustAllCerts;
    this.connectTimeout = connectTimeout;
    this.readTimeout = readTimeout;
    this.skipPrincipals = skipPrincipals != null ? skipPrincipals : Collections.emptySet();
  }

  // ====== GroupMemberResolver interface ======

  /**
   * Determines if a CMIS principal is a group by calling the HTTP API.
   *
   * <p>If a search URL is configured, first searches for the group by name
   * to get its ID. Then calls the members URL. If members are returned,
   * the principal is a group. Results are cached.</p>
   */
  @Override
  public boolean isGroup(String principalId) {
    if (!available) return false;
    if (principalId == null || principalId.isEmpty()) return false;

    String normalized = principalId.trim();
    if (skipPrincipals.contains(normalized.toUpperCase(Locale.ROOT))) {
      return false;
    }
    if (knownUsers.contains(normalized)) return false;
    if (groupCache.containsKey(normalized)) return true;

    // Try resolving via HTTP
    Set<String> members = lookupGroupMembers(normalized);
    if (members != null) {
      groupCache.put(normalized, members);
      return true;
    }
    knownUsers.add(normalized);
    return false;
  }

  @Override
  public Set<String> resolveMembers(String groupPrincipalId) {
    if (!available) return Collections.emptySet();
    if (groupPrincipalId == null || groupPrincipalId.isEmpty()) return Collections.emptySet();

    String normalized = groupPrincipalId.trim();
    if (skipPrincipals.contains(normalized.toUpperCase(Locale.ROOT))) {
      return Collections.emptySet();
    }

    Set<String> cached = groupCache.get(normalized);
    return cached != null ? cached : Collections.emptySet();
  }

  @Override
  public void reset() {
    groupCache.clear();
    knownUsers.clear();
    groupIdCache.clear();
    available = true;
  }

  // ====== HTTP operations ======

  /**
   * Look up a group principal via the HTTP API and return its members.
   *
   * @return set of lowercase member usernames, or {@code null} if not a group
   */
  private Set<String> lookupGroupMembers(String principalId) {
    String searchName = extractSearchName(principalId);
    if (searchName.isEmpty()) return null;

    try {
      // Step 1: If search URL is configured, resolve group name → ID
      String groupIdentifier = searchName;
      if (searchUrlTemplate != null) {
        groupIdentifier = resolveGroupId(searchName);
        if (groupIdentifier == null) {
          // Not found as a group
          return null;
        }
      }

      // Step 2: Call members URL
      String url = membersUrlTemplate.replace("{group}",
          URLEncoder.encode(groupIdentifier, "UTF-8"));
      String responseBody = httpGet(url);
      if (responseBody == null) {
        return null;  // 404 or error — not a group
      }

      // Step 3: Parse members from response
      Set<String> members = parseMembersResponse(responseBody);

      if (!members.isEmpty()) {
        Logging.connectors.info("CMIS: HTTP resolver resolved group '"
            + principalId + "' (search: '" + searchName + "') to "
            + members.size() + " users: " + members);
      }

      return members;

    } catch (Exception e) {
      Logging.connectors.debug("CMIS: HTTP resolver error for '"
          + principalId + "': " + e.getMessage());
      return null;
    }
  }

  /**
   * Search for a group by name and return its ID.
   *
   * @return the group ID, or {@code null} if not found
   */
  private String resolveGroupId(String groupName) throws Exception {
    // Check cache
    String cached = groupIdCache.get(groupName);
    if (cached != null) return cached;

    String url = searchUrlTemplate.replace("{group}",
        URLEncoder.encode(groupName, "UTF-8"));
    String responseBody = httpGet(url);
    if (responseBody == null) return null;

    // Parse the first group ID from the search response
    // The response could be an array [...] or an object with a value field
    String groupId = extractFirstId(responseBody);
    if (groupId != null) {
      groupIdCache.put(groupName, groupId);
    }
    return groupId;
  }

  /**
   * Extract the first ID value from a search response.
   *
   * <p>Handles common formats:</p>
   * <ul>
   *   <li>Array: {@code [{"id":"xxx",...}, ...]}</li>
   *   <li>Object with array field: {@code {"value":[{"id":"xxx",...}],...}}</li>
   *   <li>Object with nested Resources: {@code {"Resources":[{"id":"xxx",...}],...}}</li>
   * </ul>
   */
  private String extractFirstId(String json) {
    // Look for the first occurrence of "id_field":"value"
    Pattern idPattern = Pattern.compile(
        "\"" + Pattern.quote(idField) + "\"\\s*:\\s*\"([^\"]+)\"");
    Matcher m = idPattern.matcher(json);
    return m.find() ? m.group(1) : null;
  }

  // ====== JSON parsing ======

  /**
   * Parse members from an HTTP response body.
   *
   * <p>Supports multiple response formats:</p>
   * <ul>
   *   <li>Plain string array: {@code ["user1", "user2"]}</li>
   *   <li>Object array (root): {@code [{"username":"user1"}, ...]}</li>
   *   <li>Object array (nested): {@code {"members":[{"username":"user1"}, ...]}}</li>
   *   <li>Nested username field: {@code [{"profile":{"login":"user1"}}, ...]}</li>
   * </ul>
   */
  private Set<String> parseMembersResponse(String json) {
    Set<String> members = new HashSet<>();

    // Determine where the members array starts
    String arrayJson = json;
    if (!membersField.isEmpty()) {
      // Find the array under the specified field
      arrayJson = extractFieldArray(json, membersField);
      if (arrayJson == null) return members;
    }

    // Find the outermost array
    int arrayStart = arrayJson.indexOf('[');
    int arrayEnd = arrayJson.lastIndexOf(']');
    if (arrayStart < 0 || arrayEnd <= arrayStart) return members;
    String arrayContent = arrayJson.substring(arrayStart + 1, arrayEnd);

    // Split into elements (handles both objects and strings)
    List<String> elements = splitJsonElements(arrayContent);

    for (String element : elements) {
      String trimmed = element.trim();
      if (trimmed.isEmpty()) continue;

      String username = null;

      if (trimmed.startsWith("{")) {
        // Object element — extract username field
        if (usernameField.isEmpty()) {
          // No username field specified, skip objects
          continue;
        }
        username = extractFieldFromObject(trimmed, usernameField);
      } else if (trimmed.startsWith("\"")) {
        // Simple string element
        username = trimmed.length() > 2
            ? trimmed.substring(1, trimmed.length() - 1) : null;
      }

      if (username != null && !username.isEmpty()) {
        // Strip email domain if present (user@example.com → user)
        // Only if the username looks like an email
        // Keep the full value — caller may need it for matching
        members.add(username.toLowerCase(Locale.ROOT));
      }
    }

    return members;
  }

  /**
   * Extract an array value from a JSON object by field name.
   *
   * <p>For input {@code {"members":[...],"total":10}} and field {@code "members"},
   * returns the substring starting from {@code [} to the matching {@code ]}.</p>
   */
  private String extractFieldArray(String json, String fieldName) {
    String search = "\"" + fieldName + "\"";
    int fieldIdx = json.indexOf(search);
    if (fieldIdx < 0) return null;

    // Find the colon after the field name
    int colonIdx = json.indexOf(':', fieldIdx + search.length());
    if (colonIdx < 0) return null;

    // Find the opening bracket
    int bracketIdx = json.indexOf('[', colonIdx + 1);
    if (bracketIdx < 0) return null;

    // Find the matching closing bracket (handle nesting)
    int depth = 0;
    for (int i = bracketIdx; i < json.length(); i++) {
      char c = json.charAt(i);
      if (c == '[') depth++;
      else if (c == ']') {
        depth--;
        if (depth == 0) {
          return json.substring(bracketIdx, i + 1);
        }
      }
    }

    return null;
  }

  /**
   * Split JSON array content into individual elements, respecting nesting.
   *
   * <p>Handles both {@code {"a":"b"},{"c":"d"}} and {@code "a","b"} formats.</p>
   */
  private List<String> splitJsonElements(String arrayContent) {
    List<String> elements = new ArrayList<>();
    int depth = 0;
    int start = 0;
    boolean inString = false;
    boolean escape = false;

    for (int i = 0; i < arrayContent.length(); i++) {
      char c = arrayContent.charAt(i);

      if (escape) {
        escape = false;
        continue;
      }
      if (c == '\\') {
        escape = true;
        continue;
      }
      if (c == '"') {
        inString = !inString;
        continue;
      }
      if (inString) continue;

      if (c == '{' || c == '[') {
        depth++;
      } else if (c == '}' || c == ']') {
        depth--;
      } else if (c == ',' && depth == 0) {
        String element = arrayContent.substring(start, i).trim();
        if (!element.isEmpty()) {
          elements.add(element);
        }
        start = i + 1;
      }
    }

    // Last element
    String last = arrayContent.substring(start).trim();
    if (!last.isEmpty()) {
      elements.add(last);
    }

    return elements;
  }

  /**
   * Extract a field value from a JSON object string.
   *
   * <p>Supports dot notation for nested fields:
   * {@code "profile.login"} extracts {@code login} from nested
   * {@code {"profile":{"login":"value"}}}.</p>
   */
  private String extractFieldFromObject(String jsonObj, String fieldPath) {
    String[] parts = fieldPath.split("\\.", 2);
    String fieldName = parts[0];

    // Find "fieldName":"value" or "fieldName":{...}
    String search = "\"" + fieldName + "\"";
    int fieldIdx = jsonObj.indexOf(search);
    if (fieldIdx < 0) return null;

    int colonIdx = jsonObj.indexOf(':', fieldIdx + search.length());
    if (colonIdx < 0) return null;

    // Skip whitespace after colon
    int valueStart = colonIdx + 1;
    while (valueStart < jsonObj.length()
        && Character.isWhitespace(jsonObj.charAt(valueStart))) {
      valueStart++;
    }
    if (valueStart >= jsonObj.length()) return null;

    char firstChar = jsonObj.charAt(valueStart);

    if (firstChar == '"') {
      // String value
      int endQuote = jsonObj.indexOf('"', valueStart + 1);
      if (endQuote < 0) return null;
      String value = jsonObj.substring(valueStart + 1, endQuote);

      // If there's a remaining path (nested), this shouldn't be a string
      if (parts.length > 1) return null;
      return value;
    }

    if (firstChar == '{') {
      // Nested object — recurse if there's a remaining path
      if (parts.length < 2) return null;

      // Find matching closing brace
      int depth = 0;
      for (int i = valueStart; i < jsonObj.length(); i++) {
        if (jsonObj.charAt(i) == '{') depth++;
        else if (jsonObj.charAt(i) == '}') {
          depth--;
          if (depth == 0) {
            String nestedObj = jsonObj.substring(valueStart, i + 1);
            return extractFieldFromObject(nestedObj, parts[1]);
          }
        }
      }
    }

    return null;
  }

  // ====== Principal name extraction ======

  /**
   * Extract a searchable name from a CMIS principal ID.
   * Same logic as {@link LdapGroupMemberResolver}: strips vendor-specific
   * prefixes ({@code GROUP_}, {@code DOMAIN\}, DN format).
   */
  private String extractSearchName(String principalId) {
    String name = principalId;

    // Strip GROUP_ prefix (Alfresco convention)
    if (name.toUpperCase(Locale.ROOT).startsWith("GROUP_")) {
      name = name.substring(6);
    }

    // Strip domain prefix: DOMAIN\name (AD/SharePoint convention)
    int backslashIdx = name.indexOf('\\');
    if (backslashIdx >= 0 && backslashIdx < name.length() - 1) {
      name = name.substring(backslashIdx + 1);
    }

    // Extract CN from DN format: cn=Name,ou=Groups,dc=...
    if (name.contains(",")) {
      String upper = name.toUpperCase(Locale.ROOT);
      if (upper.startsWith("CN=") || upper.startsWith("UID=")) {
        int eqIdx = name.indexOf('=');
        int commaIdx = name.indexOf(',');
        if (commaIdx > eqIdx) {
          name = name.substring(eqIdx + 1, commaIdx);
        }
      }
    }

    return name.trim();
  }

  // ====== HTTP client ======

  /**
   * Perform an HTTP GET request.
   *
   * @param urlStr the full URL to call
   * @return the response body, or {@code null} for 404 / errors
   */
  private String httpGet(String urlStr) {
    HttpURLConnection conn = null;
    try {
      URL url = new URL(urlStr);
      conn = (HttpURLConnection) url.openConnection();

      // HTTPS: trust all certs if configured
      if (trustAllCerts && conn instanceof HttpsURLConnection) {
        HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{
            new X509TrustManager() {
              public X509Certificate[] getAcceptedIssuers() { return null; }
              public void checkClientTrusted(X509Certificate[] c, String a) {}
              public void checkServerTrusted(X509Certificate[] c, String a) {}
            }
        }, new SecureRandom());
        httpsConn.setSSLSocketFactory(sslContext.getSocketFactory());
        httpsConn.setHostnameVerifier((h, s) -> true);
      }

      // Auth header
      if (authHeader != null) {
        conn.setRequestProperty("Authorization", authHeader);
      }
      conn.setRequestProperty("Accept", "application/json");
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setConnectTimeout(connectTimeout);
      conn.setReadTimeout(readTimeout);

      int responseCode = conn.getResponseCode();

      if (responseCode == 200) {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
          StringBuilder sb = new StringBuilder();
          String line;
          while ((line = reader.readLine()) != null) {
            sb.append(line);
          }
          return sb.toString();
        }
      }

      if (responseCode == 404) {
        return null;  // Not found — principal is not a group
      }

      if (responseCode == 401 || responseCode == 403) {
        Logging.connectors.warn("CMIS: HTTP group resolver got HTTP "
            + responseCode + " from " + urlStr
            + " — check MCF_HTTP_GROUP_RESOLVER_AUTH. Disabling.");
        available = false;
        return null;
      }

      if (responseCode == 429) {
        Logging.connectors.warn("CMIS: HTTP group resolver rate-limited (HTTP 429)"
            + " — skipping group resolution for this principal");
        return null;
      }

      Logging.connectors.debug("CMIS: HTTP group resolver got HTTP "
          + responseCode + " from " + urlStr);
      return null;

    } catch (javax.net.ssl.SSLException e) {
      Logging.connectors.warn("CMIS: HTTP group resolver SSL error: "
          + e.getMessage() + " — disabling");
      available = false;
      return null;
    } catch (java.net.ConnectException | java.net.SocketTimeoutException e) {
      Logging.connectors.warn("CMIS: HTTP group resolver connection error: "
          + e.getMessage() + " — disabling");
      available = false;
      return null;
    } catch (Exception e) {
      Logging.connectors.debug("CMIS: HTTP group resolver error: " + e.getMessage());
      return null;
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }
}
