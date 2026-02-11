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

import java.util.Collections;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;

import org.apache.manifoldcf.crawler.system.Logging;

/**
 * Generic LDAP-based implementation of {@link GroupMemberResolver} that works
 * with <b>any</b> CMIS vendor (FileNet, SharePoint, Documentum, Nuxeo,
 * Alfresco, etc.) whose users and groups are managed in LDAP or Active Directory.
 *
 * <p><b>How it works:</b></p>
 * <ol>
 *   <li>For each CMIS ACE principal, {@link #isGroup(String)} searches LDAP
 *       to determine if the principal is a group (by objectClass). Results
 *       are cached — typically only ~50 unique principals per crawl.</li>
 *   <li>If a group is found, {@link #resolveMembers(String)} returns the
 *       cached member usernames (resolved during the {@code isGroup} call).</li>
 *   <li>Member resolution supports two modes:
 *       <ul>
 *         <li><b>memberOf</b> (default, best for AD): reverse query
 *             {@code (&(objectClass=person)(memberOf=groupDN))}</li>
 *         <li><b>attribute</b> (for OpenLDAP posixGroup): read
 *             {@code memberUid} attribute directly</li>
 *         <li><b>auto</b>: try memberOf first, fall back to attribute</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p><b>Principal name extraction:</b> CMIS vendors format principal IDs
 * differently. This resolver handles common formats automatically:</p>
 * <ul>
 *   <li>{@code GROUP_SiteName_SiteManager} (Alfresco) → strips {@code GROUP_} prefix</li>
 *   <li>{@code DOMAIN\GroupName} (SharePoint/AD) → strips domain prefix</li>
 *   <li>{@code cn=GroupName,ou=Groups,dc=...} (FileNet/LDAP DN) → extracts CN value</li>
 *   <li>{@code GroupName} (Nuxeo, Documentum) → used as-is</li>
 * </ul>
 *
 * <p><b>Configuration via environment variables:</b></p>
 * <table>
 *   <tr><th>Variable</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>{@code MCF_LDAP_URL}</td><td><em>required</em></td>
 *       <td>LDAP URL, e.g. {@code ldaps://ldap.example.com:636}</td></tr>
 *   <tr><td>{@code MCF_LDAP_BASE_DN}</td><td><em>required</em></td>
 *       <td>Base DN, e.g. {@code dc=example,dc=com}</td></tr>
 *   <tr><td>{@code MCF_LDAP_BIND_DN}</td><td><em>empty (anonymous)</em></td>
 *       <td>Bind DN for authentication</td></tr>
 *   <tr><td>{@code MCF_LDAP_BIND_PASSWORD}</td><td><em>empty</em></td>
 *       <td>Bind password</td></tr>
 *   <tr><td>{@code MCF_LDAP_GROUP_FILTER}</td>
 *       <td>{@code (&(|(objectClass=group)...)(cn={0}))}</td>
 *       <td>LDAP search filter for groups; {@code {0}} is replaced by the group name</td></tr>
 *   <tr><td>{@code MCF_LDAP_MEMBER_ATTRIBUTE}</td><td>{@code member}</td>
 *       <td>Group member attribute ({@code member} for AD, {@code memberUid} for posixGroup)</td></tr>
 *   <tr><td>{@code MCF_LDAP_USER_ID_ATTRIBUTE}</td><td>{@code sAMAccountName}</td>
 *       <td>User ID attribute ({@code sAMAccountName} for AD, {@code uid} for OpenLDAP)</td></tr>
 *   <tr><td>{@code MCF_LDAP_MEMBER_RESOLUTION}</td><td>{@code auto}</td>
 *       <td>Resolution mode: {@code auto}, {@code memberOf}, or {@code attribute}</td></tr>
 *   <tr><td>{@code MCF_LDAP_TRUST_ALL_CERTS}</td><td>{@code false}</td>
 *       <td>Trust all LDAPS certificates (for self-signed certs)</td></tr>
 *   <tr><td>{@code MCF_LDAP_SKIP_PRINCIPALS}</td>
 *       <td>{@code GROUP_EVERYONE,Everyone,#AUTHENTICATED-USERS}</td>
 *       <td>Comma-separated principals to skip (never expand)</td></tr>
 * </table>
 *
 * <p><b>Thread safety:</b> This class is thread-safe. Caches use
 * {@link ConcurrentHashMap}, LDAP context creation is synchronized.</p>
 *
 * @see GroupMemberResolver
 * @see GroupMemberResolverFactory
 */
public class LdapGroupMemberResolver implements GroupMemberResolver {

  // ====== Configuration ======

  private final String ldapUrl;
  private final String baseDn;
  private final String bindDn;
  private final String bindPassword;
  private final String groupSearchFilter;
  private final String memberAttribute;
  private final String userIdAttribute;
  private final String memberResolutionMode;
  private final boolean trustAllCerts;
  private final Set<String> skipPrincipals;

  // ====== Cache ======

  /** Groups found in LDAP: principalId → resolved member usernames. */
  private final Map<String, Set<String>> groupCache = new ConcurrentHashMap<>();

  /** Principals confirmed as non-groups (not found in LDAP as group). */
  private final Set<String> knownUsers = ConcurrentHashMap.newKeySet();

  // ====== State ======

  /** Lazy-initialized LDAP connection. */
  private volatile DirContext ldapContext;

  /** Flag to disable LDAP after fatal errors (server unreachable, auth failed). */
  private volatile boolean ldapAvailable = true;

  /**
   * Create a new LDAP group member resolver.
   *
   * @param ldapUrl              LDAP URL (e.g., {@code ldaps://ldap.example.com:636})
   * @param baseDn               Base DN (e.g., {@code dc=example,dc=com})
   * @param bindDn               Bind DN (empty for anonymous bind)
   * @param bindPassword         Bind password
   * @param groupSearchFilter    LDAP filter for group search; {@code {0}} is replaced
   *                             by the extracted group name
   * @param memberAttribute      Group member attribute name (e.g., {@code member})
   * @param userIdAttribute      User ID attribute name (e.g., {@code sAMAccountName})
   * @param memberResolutionMode Resolution mode: {@code auto}, {@code memberOf},
   *                             or {@code attribute}
   * @param trustAllCerts        Whether to trust all LDAPS certificates
   * @param skipPrincipals       Set of principal IDs to skip (uppercase, e.g.,
   *                             {@code GROUP_EVERYONE})
   */
  public LdapGroupMemberResolver(String ldapUrl, String baseDn,
      String bindDn, String bindPassword, String groupSearchFilter,
      String memberAttribute, String userIdAttribute,
      String memberResolutionMode, boolean trustAllCerts,
      Set<String> skipPrincipals) {
    this.ldapUrl = ldapUrl;
    this.baseDn = baseDn;
    this.bindDn = bindDn;
    this.bindPassword = bindPassword;
    this.groupSearchFilter = groupSearchFilter;
    this.memberAttribute = memberAttribute;
    this.userIdAttribute = userIdAttribute;
    this.memberResolutionMode = memberResolutionMode;
    this.trustAllCerts = trustAllCerts;
    this.skipPrincipals = skipPrincipals;
  }

  // ====== GroupMemberResolver interface ======

  /**
   * Determines if a CMIS principal is a group by looking it up in LDAP.
   *
   * <p>This is <b>vendor-agnostic</b> — it does not rely on naming conventions.
   * Instead, it searches LDAP for a group entry matching the principal's name.
   * The result is cached for the session.</p>
   */
  @Override
  public boolean isGroup(String principalId) {
    if (!ldapAvailable) return false;
    if (principalId == null || principalId.isEmpty()) return false;

    String normalized = principalId.trim();
    if (skipPrincipals.contains(normalized.toUpperCase(Locale.ROOT))) {
      return false;
    }
    if (knownUsers.contains(normalized)) return false;
    if (groupCache.containsKey(normalized)) return true;

    // Unknown principal — look it up in LDAP
    Set<String> members = lookupGroupMembers(normalized);
    if (members != null) {
      groupCache.put(normalized, members);
      return true;
    }
    knownUsers.add(normalized);
    return false;
  }

  /**
   * Returns the cached member usernames for a group principal.
   * Must be called after {@link #isGroup(String)} which triggers the LDAP lookup.
   */
  @Override
  public Set<String> resolveMembers(String groupPrincipalId) {
    if (!ldapAvailable) return Collections.emptySet();
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
    ldapAvailable = true;
    closeLdapContext();
  }

  // ====== LDAP operations ======

  /**
   * Look up a CMIS principal in LDAP to determine if it's a group and
   * resolve its members.
   *
   * @param principalId the original CMIS ACE principal ID
   * @return set of lowercase member usernames if it's a group, or {@code null}
   *         if it's not a group or LDAP lookup failed
   */
  private Set<String> lookupGroupMembers(String principalId) {
    String searchName = extractSearchName(principalId);
    if (searchName.isEmpty()) return null;

    try {
      DirContext ctx = getLdapContext();
      if (ctx == null) return null;

      // Search for a group entry matching the extracted name
      String filter = groupSearchFilter.replace("{0}", escapeLdapFilter(searchName));

      SearchControls sc = new SearchControls();
      sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
      sc.setReturningAttributes(new String[]{"dn", memberAttribute});
      sc.setCountLimit(1);

      NamingEnumeration<SearchResult> results = ctx.search(baseDn, filter, sc);

      if (!results.hasMore()) {
        // Not found as a group — it's a user
        return null;
      }

      SearchResult groupEntry = results.next();

      // Found as a group — resolve members
      Set<String> members = resolveMembersFromGroup(ctx, groupEntry);

      if (!members.isEmpty()) {
        Logging.connectors.info("CMIS: LDAP resolved group '" + principalId
            + "' (ldap: '" + searchName + "') to " + members.size()
            + " users: " + members);
      } else {
        Logging.connectors.debug("CMIS: LDAP found group '" + principalId
            + "' but it has no members");
      }

      return members;

    } catch (javax.naming.AuthenticationException e) {
      Logging.connectors.warn("CMIS: LDAP authentication failed: " + e.getMessage()
          + " — disabling LDAP group resolution");
      ldapAvailable = false;
      closeLdapContext();
      return null;
    } catch (javax.naming.CommunicationException e) {
      Logging.connectors.warn("CMIS: LDAP connection error: " + e.getMessage()
          + " — disabling LDAP group resolution");
      ldapAvailable = false;
      closeLdapContext();
      return null;
    } catch (NamingException e) {
      Logging.connectors.debug("CMIS: LDAP error looking up '" + principalId
          + "': " + e.getMessage());
      return null;
    }
  }

  /**
   * Extract a searchable group name from a CMIS principal ID.
   *
   * <p>Handles common vendor-specific formats:</p>
   * <ul>
   *   <li>{@code GROUP_Name} (Alfresco) → {@code Name}</li>
   *   <li>{@code DOMAIN\Name} (SharePoint/AD) → {@code Name}</li>
   *   <li>{@code cn=Name,ou=Groups,...} (FileNet/LDAP DN) → {@code Name}</li>
   *   <li>{@code Name} (Nuxeo, Documentum, generic) → {@code Name}</li>
   * </ul>
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

    // Extract CN from DN format: cn=Name,ou=Groups,dc=example,dc=com (FileNet/LDAP)
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

  /**
   * Resolve group members using the configured resolution mode.
   */
  private Set<String> resolveMembersFromGroup(DirContext ctx,
      SearchResult groupEntry) throws NamingException {

    String groupDN = groupEntry.getNameInNamespace();

    if ("attribute".equalsIgnoreCase(memberResolutionMode)) {
      return resolveMembersViaAttribute(ctx, groupEntry);
    }

    if ("memberOf".equalsIgnoreCase(memberResolutionMode)) {
      return resolveMembersViaMemberOf(ctx, groupDN);
    }

    // Auto mode: try memberOf first (efficient for AD), then fall back to attribute
    Set<String> members = resolveMembersViaMemberOf(ctx, groupDN);
    if (!members.isEmpty()) {
      return members;
    }

    // memberOf returned nothing — try reading member attribute directly
    // (works for OpenLDAP without memberOf overlay, posixGroup, etc.)
    return resolveMembersViaAttribute(ctx, groupEntry);
  }

  /**
   * Resolve members via reverse {@code memberOf} query (best for Active Directory).
   *
   * <p>Searches for all user objects whose {@code memberOf} attribute contains
   * the group's DN, and returns their user ID attribute values.</p>
   */
  private Set<String> resolveMembersViaMemberOf(DirContext ctx,
      String groupDN) throws NamingException {
    Set<String> members = new HashSet<>();

    SearchControls sc = new SearchControls();
    sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
    sc.setReturningAttributes(new String[]{userIdAttribute});

    // Search for persons who are members of this group
    String filter = "(&(|(objectClass=user)(objectClass=person)"
        + "(objectClass=inetOrgPerson))(memberOf="
        + escapeLdapFilter(groupDN) + "))";

    NamingEnumeration<SearchResult> results = ctx.search(baseDn, filter, sc);
    while (results.hasMore()) {
      SearchResult sr = results.next();
      Attribute attr = sr.getAttributes().get(userIdAttribute);
      if (attr != null && attr.get() != null) {
        members.add(attr.get().toString().toLowerCase(Locale.ROOT));
      }
    }
    return members;
  }

  /**
   * Resolve members by reading the group's member attribute directly.
   *
   * <p>Handles two formats:</p>
   * <ul>
   *   <li>Plain usernames (e.g., {@code memberUid} in posixGroup) → used as-is</li>
   *   <li>DNs (e.g., {@code member} in groupOfNames/AD) → looks up the user ID
   *       attribute; falls back to extracting the first RDN value</li>
   * </ul>
   */
  private Set<String> resolveMembersViaAttribute(DirContext ctx,
      SearchResult groupEntry) throws NamingException {
    Set<String> members = new HashSet<>();
    Attributes attrs = groupEntry.getAttributes();
    Attribute memberAttr = attrs.get(memberAttribute);

    if (memberAttr == null) return members;

    for (int i = 0; i < memberAttr.size(); i++) {
      String value = memberAttr.get(i).toString();

      // Check if it's a plain username (no = or comma → posixGroup memberUid)
      if (!value.contains("=") || !value.contains(",")) {
        members.add(value.toLowerCase(Locale.ROOT));
        continue;
      }

      // It's a DN — look up the user to get their ID attribute
      try {
        Attributes userAttrs = ctx.getAttributes(value,
            new String[]{userIdAttribute});
        Attribute idAttr = userAttrs.get(userIdAttribute);
        if (idAttr != null && idAttr.get() != null) {
          members.add(idAttr.get().toString().toLowerCase(Locale.ROOT));
          continue;
        }
      } catch (NamingException e) {
        // User DN might be invalid, deleted, or in a different subtree
        Logging.connectors.debug("CMIS: LDAP could not look up member DN '"
            + value + "': " + e.getMessage());
      }

      // Fallback: extract the first RDN value from the DN
      String extracted = extractRdnValue(value);
      if (extracted != null && !extracted.isEmpty()) {
        members.add(extracted.toLowerCase(Locale.ROOT));
      }
    }
    return members;
  }

  /**
   * Extract the first RDN value from an LDAP distinguished name.
   *
   * <p>Examples:</p>
   * <ul>
   *   <li>{@code CN=John Smith,OU=Users,DC=example,DC=com} → {@code John Smith}</li>
   *   <li>{@code uid=jsmith,ou=People,dc=example,dc=com} → {@code jsmith}</li>
   * </ul>
   */
  private String extractRdnValue(String dn) {
    if (dn == null || dn.isEmpty()) return null;
    int eqIdx = dn.indexOf('=');
    if (eqIdx < 0) return dn;
    int commaIdx = dn.indexOf(',', eqIdx);
    if (commaIdx < 0) return dn.substring(eqIdx + 1);
    return dn.substring(eqIdx + 1, commaIdx);
  }

  // ====== LDAP connection management ======

  /**
   * Get or create the LDAP context (lazy initialization, synchronized).
   */
  private synchronized DirContext getLdapContext() {
    if (!ldapAvailable) return null;
    if (ldapContext != null) return ldapContext;

    try {
      Hashtable<String, String> env = new Hashtable<>();
      env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
      env.put(Context.PROVIDER_URL, ldapUrl);

      if (bindDn != null && !bindDn.isEmpty()) {
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, bindDn);
        env.put(Context.SECURITY_CREDENTIALS, bindPassword != null ? bindPassword : "");
      } else {
        env.put(Context.SECURITY_AUTHENTICATION, "none");
      }

      // Connection pooling and timeouts
      env.put("com.sun.jndi.ldap.connect.pool", "true");
      env.put("com.sun.jndi.ldap.connect.timeout", "10000");
      env.put("com.sun.jndi.ldap.read.timeout", "15000");

      // Trust all certs for LDAPS with self-signed certificates
      if (trustAllCerts && ldapUrl.toLowerCase(Locale.ROOT).startsWith("ldaps")) {
        env.put("java.naming.ldap.factory.socket",
            TrustAllSSLSocketFactory.class.getName());
      }

      ldapContext = new InitialDirContext(env);

      Logging.connectors.info("CMIS: LDAP connection established to " + ldapUrl
          + " (baseDN: " + baseDn + ")");

      return ldapContext;

    } catch (NamingException e) {
      Logging.connectors.warn("CMIS: Failed to connect to LDAP at " + ldapUrl
          + ": " + e.getMessage() + " — disabling LDAP group resolution");
      ldapAvailable = false;
      return null;
    }
  }

  /**
   * Close the LDAP context if open.
   */
  private synchronized void closeLdapContext() {
    if (ldapContext != null) {
      try {
        ldapContext.close();
      } catch (NamingException e) {
        Logging.connectors.debug("CMIS: Error closing LDAP context: " + e.getMessage());
      }
      ldapContext = null;
    }
  }

  // ====== Utility ======

  /**
   * Escape special characters in an LDAP search filter value to prevent
   * LDAP injection attacks (RFC 4515).
   */
  static String escapeLdapFilter(String input) {
    if (input == null) return "";
    StringBuilder sb = new StringBuilder(input.length() + 10);
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      switch (c) {
        case '\\': sb.append("\\5c"); break;
        case '*':  sb.append("\\2a"); break;
        case '(':  sb.append("\\28"); break;
        case ')':  sb.append("\\29"); break;
        case '\0': sb.append("\\00"); break;
        default:   sb.append(c);
      }
    }
    return sb.toString();
  }
}
