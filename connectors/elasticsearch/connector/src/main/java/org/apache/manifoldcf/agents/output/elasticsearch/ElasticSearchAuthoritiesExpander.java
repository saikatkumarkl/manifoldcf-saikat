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
package org.apache.manifoldcf.agents.output.elasticsearch;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;

import org.apache.manifoldcf.crawler.system.Logging;

/**
 * Expands ACL group tokens into individual usernames by querying the authorities
 * index in OpenSearch/ElasticSearch.
 *
 * <p>The authorities index (e.g. {@code manifold_{repoName}_authorities}) is expected
 * to contain documents with the following structure:</p>
 * <pre>
 * {
 *   "group_id": "GROUP_SITE_...",
 *   "display_name": "...",
 *   "members": ["user1", "user2", ...]
 * }
 * </pre>
 *
 * <p>Given a set of ACL tokens (which may include both group IDs and user IDs),
 * this class queries the authorities index for matching groups and collects all
 * member usernames. The original ACL tokens (including individual user tokens)
 * are also included in the result.</p>
 */
public class ElasticSearchAuthoritiesExpander {

  private final HttpClient client;
  private final String serverLocation;
  private final String authoritiesIndexName;

  /**
   * @param client           the shared HttpClient
   * @param serverLocation   ES/OS server URL (e.g. "http://opensearch:9200/")
   * @param authoritiesIndexName the name of the authorities index (e.g. "manifold_alfresco_authorities")
   */
  public ElasticSearchAuthoritiesExpander(HttpClient client, String serverLocation, String authoritiesIndexName) {
    this.client = client;
    this.serverLocation = serverLocation.endsWith("/") ? serverLocation : serverLocation + "/";
    this.authoritiesIndexName = authoritiesIndexName;
  }

  /**
   * Expand ACL tokens (allow list) into individual usernames by looking up groups
   * in the authorities index.
   *
   * @param aclTokens the allow_token_document values from the crawled document
   * @return a deduplicated set of usernames (all lowercased) who have access,
   *         including both direct user tokens and members of matched groups.
   *         Returns empty set if authorities index is unavailable or aclTokens is null/empty.
   */
  public Set<String> expandAclToUsers(String[] aclTokens) {
    Set<String> authorities = new HashSet<>();
    if (aclTokens == null || aclTokens.length == 0) {
      return authorities;
    }

    // Always include the original tokens — they may be individual usernames
    for (String token : aclTokens) {
      if (token != null && !token.isEmpty() && !"__nosecurity__".equals(token)) {
        authorities.add(token.toLowerCase());
      }
    }

    // Build a terms query to find all groups that match any of the ACL tokens
    // The group_id field in the authorities index stores the group identifier
    StringBuilder queryBuilder = new StringBuilder();
    queryBuilder.append("{\"size\":1000,\"_source\":[\"group_id\",\"members\"],\"query\":{\"terms\":{\"group_id\":[");
    boolean first = true;
    for (String token : aclTokens) {
      if (token != null && !token.isEmpty() && !"__nosecurity__".equals(token)) {
        if (!first) queryBuilder.append(",");
        queryBuilder.append("\"").append(jsonEscape(token.toLowerCase())).append("\"");
        first = false;
      }
    }
    queryBuilder.append("]}}}");

    if (first) {
      // No valid tokens
      return authorities;
    }

    String url = serverLocation + authoritiesIndexName + "/_search";
    try {
      HttpPost post = new HttpPost(url);
      post.setEntity(new StringEntity(queryBuilder.toString(), StandardCharsets.UTF_8));
      post.setHeader("Content-Type", "application/json");

      HttpResponse response = client.execute(post);
      int statusCode = response.getStatusLine().getStatusCode();
      HttpEntity entity = response.getEntity();

      if (statusCode == 200 && entity != null) {
        String responseBody = EntityUtils.toString(entity, StandardCharsets.UTF_8);
        // Parse the members from the response JSON
        // We do simple string parsing to avoid adding a JSON library dependency
        parseAndCollectMembers(responseBody, authorities);
      } else {
        if (entity != null) {
          EntityUtils.consume(entity);
        }
        Logging.connectors.warn("ES AuthoritiesExpander: Failed to query authorities index '" 
            + authoritiesIndexName + "', status=" + statusCode);
      }
    } catch (IOException e) {
      Logging.connectors.warn("ES AuthoritiesExpander: Error querying authorities index '" 
          + authoritiesIndexName + "': " + e.getMessage(), e);
    }

    return authorities;
  }

  /**
   * Parse the ES/OS search response and extract member arrays from each hit.
   * Uses simple string parsing to avoid JSON library dependency.
   *
   * Expected response structure:
   * {"hits":{"hits":[{"_source":{"group_id":"...","members":["user1","user2"]}}]}}
   */
  private void parseAndCollectMembers(String responseBody, Set<String> authorities) {
    // Find all "members" arrays in the response
    int searchFrom = 0;
    while (true) {
      int membersIdx = responseBody.indexOf("\"members\"", searchFrom);
      if (membersIdx == -1) break;

      // Find the opening bracket of the array
      int bracketStart = responseBody.indexOf("[", membersIdx);
      if (bracketStart == -1) break;

      // Find the closing bracket
      int bracketEnd = responseBody.indexOf("]", bracketStart);
      if (bracketEnd == -1) break;

      // Extract the array content
      String membersArray = responseBody.substring(bracketStart + 1, bracketEnd);

      // Parse individual member strings (they are JSON-escaped strings in quotes)
      int pos = 0;
      while (pos < membersArray.length()) {
        int quoteStart = membersArray.indexOf("\"", pos);
        if (quoteStart == -1) break;
        int quoteEnd = findClosingQuote(membersArray, quoteStart + 1);
        if (quoteEnd == -1) break;
        String member = membersArray.substring(quoteStart + 1, quoteEnd);
        if (!member.isEmpty()) {
          authorities.add(member.toLowerCase());
        }
        pos = quoteEnd + 1;
      }

      searchFrom = bracketEnd + 1;
    }
  }

  /** Find the closing quote, accounting for escaped quotes. */
  private int findClosingQuote(String s, int start) {
    for (int i = start; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\') {
        i++; // skip escaped char
      } else if (c == '"') {
        return i;
      }
    }
    return -1;
  }

  /** Simple JSON string escape for query building. */
  private static String jsonEscape(String value) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"' || c == '\\') {
        sb.append('\\');
      }
      sb.append(c);
    }
    return sb.toString();
  }
}
