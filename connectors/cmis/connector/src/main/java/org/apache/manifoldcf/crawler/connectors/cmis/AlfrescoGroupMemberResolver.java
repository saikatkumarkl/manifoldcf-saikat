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
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
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
 * Alfresco-specific implementation of {@link GroupMemberResolver}.
 *
 * <p>Resolves Alfresco group principals (prefixed with {@code GROUP_}) to
 * individual member usernames via the Alfresco REST API v1:</p>
 * <pre>
 * GET /alfresco/api/-default-/public/alfresco/versions/1/groups/{groupId}/members
 *     ?where=(memberType='PERSON')&amp;maxItems=1000
 * </pre>
 *
 * <p><b>Key behaviors:</b></p>
 * <ul>
 *   <li>{@code GROUP_EVERYONE} is never expanded (would be the entire user base)</li>
 *   <li>Uses the same host:port and credentials as the CMIS connection</li>
 *   <li>Caches resolved groups per crawl session (~10 unique groups for ~1000 docs)</li>
 *   <li>Gracefully degrades: if the first API call fails (non-Alfresco, network error,
 *       SSL error), disables resolution for the session and returns empty sets</li>
 *   <li>Supports HTTPS with self-signed certificates (trust-all for dev/proxy setups)</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> This class is thread-safe. The cache is a
 * {@link ConcurrentHashMap} and the disable flag is volatile.</p>
 *
 * @see GroupMemberResolver
 * @see GroupMemberResolverFactory
 */
public class AlfrescoGroupMemberResolver implements GroupMemberResolver {

  /** The Alfresco "everyone" group — never expanded. */
  private static final String GROUP_EVERYONE = "GROUP_EVERYONE";

  /** Regex to extract "id" values from Alfresco REST API JSON responses. */
  private static final Pattern JSON_ID_PATTERN =
      Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

  private final String protocol;
  private final String server;
  private final String port;
  private final String username;
  private final String password;

  /**
   * Cache of group principal IDs to their resolved member usernames (lowercase).
   * Populated lazily during ACL extraction. Cleared on {@link #reset()}.
   */
  private final Map<String, Set<String>> groupMembersCache = new ConcurrentHashMap<>();

  /**
   * Flag indicating whether group resolution is available. Starts as {@code true}
   * and is set to {@code false} after the first API failure (non-Alfresco server,
   * network error, SSL error). This avoids repeated failing HTTP calls.
   */
  private volatile boolean groupResolutionAvailable = true;

  /**
   * Create a new Alfresco group member resolver.
   *
   * @param protocol the connection protocol (http or https)
   * @param server   the server hostname
   * @param port     the server port
   * @param username the authentication username (same as CMIS connection)
   * @param password the authentication password (same as CMIS connection)
   */
  public AlfrescoGroupMemberResolver(String protocol, String server,
      String port, String username, String password) {
    this.protocol = protocol;
    this.server = server;
    this.port = port;
    this.username = username;
    this.password = password;
  }

  /**
   * Alfresco groups are prefixed with {@code GROUP_} (case-insensitive).
   */
  @Override
  public boolean isGroup(String principalId) {
    return principalId != null
        && principalId.toUpperCase(Locale.ROOT).startsWith("GROUP_");
  }

  /**
   * Resolve an Alfresco group to its member usernames via the Alfresco REST API.
   *
   * <p>Skips {@code GROUP_EVERYONE} (would be the entire user base).
   * Results are cached per crawl session.</p>
   */
  @Override
  public Set<String> resolveMembers(String groupPrincipalId) {
    // Skip if server doesn't support group resolution
    if (!groupResolutionAvailable) {
      return Collections.emptySet();
    }

    // Skip GROUP_EVERYONE — it means "all authenticated users" and should
    // NOT be expanded to individual users (would be the entire user base).
    if (GROUP_EVERYONE.equalsIgnoreCase(groupPrincipalId)) {
      return Collections.emptySet();
    }

    // Check cache first
    Set<String> cached = groupMembersCache.get(groupPrincipalId);
    if (cached != null) {
      return cached;
    }

    Set<String> members = new HashSet<>();
    HttpURLConnection conn = null;
    try {
      // Alfresco REST API v1: GET /groups/{groupId}/members?where=(memberType='PERSON')
      String baseUrl = protocol + "://" + server + ":" + port;
      String encodedGroupId = URLEncoder.encode(groupPrincipalId, "UTF-8");
      String apiPath = "/alfresco/api/-default-/public/alfresco/versions/1/groups/"
          + encodedGroupId
          + "/members?where=(memberType%3D%27PERSON%27)&maxItems=1000";

      URL url = new URL(baseUrl + apiPath);
      conn = (HttpURLConnection) url.openConnection();

      // For HTTPS with self-signed certificates (common in dev/proxy setups)
      if (conn instanceof HttpsURLConnection) {
        HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{
            new X509TrustManager() {
              public X509Certificate[] getAcceptedIssuers() { return null; }
              public void checkClientTrusted(X509Certificate[] certs, String authType) {}
              public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }
        }, new SecureRandom());
        httpsConn.setSSLSocketFactory(sslContext.getSocketFactory());
        httpsConn.setHostnameVerifier((hostname, sslSession) -> true);
      }

      // Basic auth using the same credentials as the CMIS connection
      String auth = username + ":" + password;
      String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes("UTF-8"));
      conn.setRequestProperty("Authorization", "Basic " + encodedAuth);
      conn.setRequestProperty("Accept", "application/json");
      conn.setConnectTimeout(10000);
      conn.setReadTimeout(10000);

      int responseCode = conn.getResponseCode();
      if (responseCode == 200) {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
          StringBuilder sb = new StringBuilder();
          String line;
          while ((line = reader.readLine()) != null) {
            sb.append(line);
          }
          members = parseGroupMembersResponse(sb.toString());
        }

        if (!members.isEmpty()) {
          Logging.connectors.info("CMIS: Resolved Alfresco group '" + groupPrincipalId
              + "' to " + members.size() + " users: " + members);
        }
      } else if (responseCode == 404) {
        Logging.connectors.debug("CMIS: Group '" + groupPrincipalId
            + "' not found via Alfresco REST API (HTTP 404) — keeping raw group token");
      } else {
        Logging.connectors.warn("CMIS: Alfresco group resolution API returned HTTP "
            + responseCode + " for '" + groupPrincipalId
            + "' — disabling group resolution for this session");
        groupResolutionAvailable = false;
      }
    } catch (javax.net.ssl.SSLException e) {
      Logging.connectors.warn("CMIS: SSL error during Alfresco group resolution for '"
          + groupPrincipalId + "': " + e.getMessage()
          + " — disabling group resolution");
      groupResolutionAvailable = false;
    } catch (java.net.ConnectException | java.net.SocketTimeoutException e) {
      Logging.connectors.warn("CMIS: Connection error during Alfresco group resolution for '"
          + groupPrincipalId + "': " + e.getMessage()
          + " — disabling group resolution");
      groupResolutionAvailable = false;
    } catch (Exception e) {
      Logging.connectors.debug("CMIS: Could not resolve Alfresco group '" + groupPrincipalId
          + "' to users: " + e.getMessage());
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }

    // Cache the result (even if empty, to avoid retrying failed groups)
    groupMembersCache.put(groupPrincipalId, members);
    return members;
  }

  @Override
  public void reset() {
    groupMembersCache.clear();
    groupResolutionAvailable = true;
  }

  /**
   * Parse the Alfresco REST API v1 JSON response for group members.
   *
   * <p>Expected format:</p>
   * <pre>{@code
   * {"list":{"entries":[
   *   {"entry":{"id":"username","displayName":"...","memberType":"PERSON"}},
   *   ...
   * ]}}
   * }</pre>
   *
   * <p>Uses simple string/regex parsing to avoid adding a JSON library dependency.
   * Only extracts entries where {@code memberType} is {@code PERSON}.</p>
   *
   * @param json the raw JSON response body
   * @return set of lowercase member usernames
   */
  private Set<String> parseGroupMembersResponse(String json) {
    Set<String> members = new HashSet<>();

    int searchFrom = 0;
    while (true) {
      int entryIdx = json.indexOf("\"entry\"", searchFrom);
      if (entryIdx == -1) break;

      int braceStart = json.indexOf('{', entryIdx + 7);
      if (braceStart == -1) break;

      int braceEnd = json.indexOf('}', braceStart + 1);
      if (braceEnd == -1) break;

      String entryBlock = json.substring(braceStart, braceEnd + 1);

      // Only process PERSON entries (skip GROUP sub-groups)
      if (entryBlock.contains("\"PERSON\"")) {
        Matcher idMatcher = JSON_ID_PATTERN.matcher(entryBlock);
        if (idMatcher.find()) {
          String memberId = idMatcher.group(1).toLowerCase(Locale.ROOT);
          members.add(memberId);
        }
      }

      searchFrom = braceEnd + 1;
    }

    return members;
  }
}
