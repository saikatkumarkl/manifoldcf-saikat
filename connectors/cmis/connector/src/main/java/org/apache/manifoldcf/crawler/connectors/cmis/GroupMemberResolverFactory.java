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

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.manifoldcf.crawler.system.Logging;

/**
 * Factory that creates the appropriate {@link GroupMemberResolver} based on
 * auto-detection, LDAP configuration, or explicit override.
 *
 * <p><b>Resolution priority (auto mode):</b></p>
 * <ol>
 *   <li><b>Vendor-specific</b> — if the CMIS repository's {@code productName}
 *       matches a known vendor (e.g., Alfresco), uses the vendor's REST API
 *       for the best group resolution experience.</li>
 *   <li><b>LDAP</b> — if {@code MCF_LDAP_URL} and {@code MCF_LDAP_BASE_DN}
 *       environment variables are set, queries LDAP/Active Directory directly.
 *       Works with any CMIS vendor whose users are in LDAP/AD.</li>
 *   <li><b>HTTP</b> — if {@code MCF_HTTP_GROUP_RESOLVER_URL} is set, calls a
 *       configurable REST API to resolve groups. Works with SSO providers
 *       (Okta, Azure AD, Auth0, Keycloak, Ping), application-layer group
 *       services, SCIM endpoints, or any custom REST API.</li>
 *   <li><b>NoOp</b> — if nothing is configured, all principals are treated
 *       as users (no group expansion).</li>
 * </ol>
 *
 * <p><b>Override via {@code MCF_GROUP_RESOLVER} environment variable:</b></p>
 * <ul>
 *   <li>{@code auto} (default) — auto-detect vendor, then LDAP, then HTTP</li>
 *   <li>{@code alfresco} — force Alfresco REST API resolver</li>
 *   <li>{@code ldap} — force LDAP resolver (requires MCF_LDAP_* config)</li>
 *   <li>{@code http} — force HTTP resolver (requires MCF_HTTP_GROUP_RESOLVER_URL)</li>
 *   <li>{@code none} — force NoOp (no group expansion)</li>
 * </ul>
 *
 * @see GroupMemberResolver
 * @see AlfrescoGroupMemberResolver
 * @see LdapGroupMemberResolver
 * @see HttpGroupMemberResolver
 * @see NoOpGroupMemberResolver
 */
public class GroupMemberResolverFactory {

  // Default LDAP group search filter — covers AD, OpenLDAP, and RFC 2256 schemas
  static final String DEFAULT_GROUP_FILTER =
      "(&(|(objectClass=group)(objectClass=groupOfNames)"
      + "(objectClass=groupOfUniqueNames)(objectClass=posixGroup))(cn={0}))";

  static final String DEFAULT_SKIP_PRINCIPALS =
      "GROUP_EVERYONE,Everyone,#AUTHENTICATED-USERS";

  private GroupMemberResolverFactory() {
    // utility class — no instances
  }

  /**
   * Create a {@link GroupMemberResolver} using auto-detection, LDAP config,
   * or explicit override.
   *
   * @param session  the active CMIS session (used to read repository info)
   * @param protocol the connection protocol (http/https)
   * @param server   the server hostname
   * @param port     the server port
   * @param username the authentication username
   * @param password the authentication password
   * @return a resolver instance (never null)
   */
  public static GroupMemberResolver create(Session session,
      String protocol, String server, String port,
      String username, String password) {

    // Check for explicit override
    String override = getEnv("MCF_GROUP_RESOLVER", "auto")
        .toLowerCase(Locale.ROOT);

    if ("none".equals(override)) {
      Logging.connectors.info(
          "CMIS: Group resolver override=none — no group expansion");
      return new NoOpGroupMemberResolver();
    }

    if ("ldap".equals(override)) {
      GroupMemberResolver ldap = createLdapResolver();
      if (ldap != null) {
        Logging.connectors.info(
            "CMIS: Group resolver override=ldap — using LDAP group resolution");
        return ldap;
      }
      Logging.connectors.warn(
          "CMIS: Group resolver override=ldap but MCF_LDAP_URL/MCF_LDAP_BASE_DN "
          + "not configured — falling back to NoOp");
      return new NoOpGroupMemberResolver();
    }

    if ("http".equals(override)) {
      GroupMemberResolver http = createHttpResolver();
      if (http != null) {
        Logging.connectors.info(
            "CMIS: Group resolver override=http — using HTTP API group resolution");
        return http;
      }
      Logging.connectors.warn(
          "CMIS: Group resolver override=http but MCF_HTTP_GROUP_RESOLVER_URL "
          + "not configured — falling back to NoOp");
      return new NoOpGroupMemberResolver();
    }

    if ("alfresco".equals(override)) {
      Logging.connectors.info(
          "CMIS: Group resolver override=alfresco — using Alfresco REST API");
      return new AlfrescoGroupMemberResolver(
          protocol, server, port, username, password);
    }

    // ===== Auto-detect mode =====

    String productName = "";
    try {
      productName = session.getRepositoryInfo().getProductName();
    } catch (Exception e) {
      Logging.connectors.debug(
          "CMIS: Could not determine repository product name: " + e.getMessage());
    }

    // 1. Vendor-specific resolver
    if (productName != null
        && productName.toLowerCase(Locale.ROOT).contains("alfresco")) {
      Logging.connectors.info(
          "CMIS: Detected Alfresco server (product: '" + productName
          + "') — enabling group resolution via Alfresco REST API");
      return new AlfrescoGroupMemberResolver(
          protocol, server, port, username, password);
    }

    // 2. LDAP resolver (if configured)
    GroupMemberResolver ldap = createLdapResolver();
    if (ldap != null) {
      Logging.connectors.info(
          "CMIS: Non-Alfresco server (product: '" + productName
          + "') — enabling group resolution via LDAP ("
          + getEnv("MCF_LDAP_URL", "") + ")");
      return ldap;
    }

    // 3. HTTP resolver (if configured — for SSO, app-layer groups, etc.)
    GroupMemberResolver http = createHttpResolver();
    if (http != null) {
      Logging.connectors.info(
          "CMIS: Non-Alfresco server (product: '" + productName
          + "'), no LDAP — enabling group resolution via HTTP API ("
          + getEnv("MCF_HTTP_GROUP_RESOLVER_URL", "") + ")");
      return http;
    }

    // 4. NoOp fallback
    Logging.connectors.info(
        "CMIS: Non-Alfresco server (product: '" + productName
        + "'), no LDAP/HTTP configured — no group expansion");
    return new NoOpGroupMemberResolver();
  }

  /**
   * Create an {@link LdapGroupMemberResolver} from {@code MCF_LDAP_*}
   * environment variables. Returns {@code null} if the required variables
   * ({@code MCF_LDAP_URL}, {@code MCF_LDAP_BASE_DN}) are not set.
   */
  private static GroupMemberResolver createLdapResolver() {
    String ldapUrl = getEnv("MCF_LDAP_URL", "");
    String baseDn  = getEnv("MCF_LDAP_BASE_DN", "");

    if (ldapUrl.isEmpty() || baseDn.isEmpty()) {
      return null;
    }

    String bindDn       = getEnv("MCF_LDAP_BIND_DN", "");
    String bindPassword = getEnv("MCF_LDAP_BIND_PASSWORD", "");
    String groupFilter  = getEnv("MCF_LDAP_GROUP_FILTER", DEFAULT_GROUP_FILTER);
    String memberAttr   = getEnv("MCF_LDAP_MEMBER_ATTRIBUTE", "member");
    String userIdAttr   = getEnv("MCF_LDAP_USER_ID_ATTRIBUTE", "sAMAccountName");
    String memberMode   = getEnv("MCF_LDAP_MEMBER_RESOLUTION", "auto");
    boolean trustAll    = "true".equalsIgnoreCase(
        getEnv("MCF_LDAP_TRUST_ALL_CERTS", "false"));

    String skipStr = getEnv("MCF_LDAP_SKIP_PRINCIPALS", DEFAULT_SKIP_PRINCIPALS);
    Set<String> skipPrincipals = new HashSet<>();
    for (String s : skipStr.split(",")) {
      String trimmed = s.trim();
      if (!trimmed.isEmpty()) {
        skipPrincipals.add(trimmed.toUpperCase(Locale.ROOT));
      }
    }

    Logging.connectors.info("CMIS: Creating LDAP group resolver — url: " + ldapUrl
        + ", baseDN: " + baseDn + ", memberMode: " + memberMode
        + ", userIdAttr: " + userIdAttr + ", memberAttr: " + memberAttr
        + ", trustAllCerts: " + trustAll
        + ", skipPrincipals: " + skipPrincipals);

    return new LdapGroupMemberResolver(ldapUrl, baseDn, bindDn, bindPassword,
        groupFilter, memberAttr, userIdAttr, memberMode, trustAll, skipPrincipals);
  }

  /**
   * Create an {@link HttpGroupMemberResolver} from {@code MCF_HTTP_GROUP_RESOLVER_*}
   * environment variables. Returns {@code null} if the required variable
   * ({@code MCF_HTTP_GROUP_RESOLVER_URL}) is not set.
   */
  private static GroupMemberResolver createHttpResolver() {
    String membersUrl = getEnv("MCF_HTTP_GROUP_RESOLVER_URL", "");
    if (membersUrl.isEmpty()) {
      return null;
    }

    String searchUrl    = getEnv("MCF_HTTP_GROUP_RESOLVER_SEARCH_URL", "");
    String authHeader   = getEnv("MCF_HTTP_GROUP_RESOLVER_AUTH", "");
    String usernameField = getEnv("MCF_HTTP_GROUP_RESOLVER_USERNAME_FIELD", "username");
    String membersField = getEnv("MCF_HTTP_GROUP_RESOLVER_MEMBERS_FIELD", "");
    String idField      = getEnv("MCF_HTTP_GROUP_RESOLVER_ID_FIELD", "id");
    boolean trustAll   = "true".equalsIgnoreCase(
        getEnv("MCF_HTTP_GROUP_RESOLVER_TRUST_ALL_CERTS", "false"));
    int connectTimeout = Integer.parseInt(
        getEnv("MCF_HTTP_GROUP_RESOLVER_CONNECT_TIMEOUT", "10000"));
    int readTimeout    = Integer.parseInt(
        getEnv("MCF_HTTP_GROUP_RESOLVER_READ_TIMEOUT", "15000"));

    String skipStr = getEnv("MCF_HTTP_GROUP_RESOLVER_SKIP_PRINCIPALS",
        DEFAULT_SKIP_PRINCIPALS);
    Set<String> skipPrincipals = new HashSet<>();
    for (String s : skipStr.split(",")) {
      String trimmed = s.trim();
      if (!trimmed.isEmpty()) {
        skipPrincipals.add(trimmed.toUpperCase(Locale.ROOT));
      }
    }

    Logging.connectors.info("CMIS: Creating HTTP group resolver — url: " + membersUrl
        + (searchUrl.isEmpty() ? "" : ", searchUrl: " + searchUrl)
        + ", usernameField: " + usernameField
        + (membersField.isEmpty() ? " (root array)" : ", membersField: " + membersField)
        + ", trustAllCerts: " + trustAll
        + ", skipPrincipals: " + skipPrincipals);

    return new HttpGroupMemberResolver(membersUrl, searchUrl, authHeader,
        usernameField, membersField, idField, trustAll,
        connectTimeout, readTimeout, skipPrincipals);
  }

  /**
   * Read an environment variable with a default value.
   */
  private static String getEnv(String name, String defaultValue) {
    String value = System.getenv(name);
    return (value != null && !value.isEmpty()) ? value : defaultValue;
  }
}
