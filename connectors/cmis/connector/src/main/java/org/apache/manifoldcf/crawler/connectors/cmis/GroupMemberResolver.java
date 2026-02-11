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

import java.util.Set;

/**
 * Strategy interface for resolving CMIS group principals to individual
 * member usernames. Different CMIS vendors represent groups differently
 * and expose group membership via different APIs:
 *
 * <ul>
 *   <li><b>Alfresco</b> — groups prefixed with {@code GROUP_}, resolved
 *       via Alfresco REST API v1
 *       ({@code /alfresco/api/-default-/public/alfresco/versions/1/groups/{id}/members})</li>
 *   <li><b>LDAP/Active Directory</b> — universal resolution via JNDI LDAP
 *       queries. Works with <b>any</b> CMIS vendor whose users/groups are
 *       managed in LDAP/AD (FileNet, SharePoint, Documentum, Nuxeo, etc.).
 *       Configured via {@code MCF_LDAP_*} environment variables.</li>
 *   <li><b>HTTP/REST API</b> — generic resolution via any REST endpoint.
 *       Works with SSO providers (Okta, Azure AD/Entra ID, Auth0, Keycloak,
 *       Ping Identity, OneLogin), SCIM endpoints, application-layer group
 *       services, or custom microservices. Configured via
 *       {@code MCF_HTTP_GROUP_RESOLVER_*} environment variables.</li>
 *   <li><b>Others</b> — may use opaque principal IDs with no group concept</li>
 * </ul>
 *
 * <p>Implementations are created by {@link GroupMemberResolverFactory} with
 * the following priority: vendor-specific (Alfresco) → LDAP (if configured) →
 * HTTP/REST (if configured) → NoOp (no group expansion). The resolver can be
 * explicitly overridden via the {@code MCF_GROUP_RESOLVER} environment variable.</p>
 *
 * <p>Implementations must be <b>thread-safe</b> — multiple crawler threads
 * may call {@link #resolveMembers(String)} concurrently.</p>
 *
 * @see AlfrescoGroupMemberResolver
 * @see LdapGroupMemberResolver
 * @see HttpGroupMemberResolver
 * @see NoOpGroupMemberResolver
 * @see GroupMemberResolverFactory
 */
public interface GroupMemberResolver {

  /**
   * Determine if a CMIS principal ID represents a group.
   *
   * <p>This is vendor-specific. For example:
   * <ul>
   *   <li>Alfresco: principal starts with {@code GROUP_}</li>
   *   <li>Nuxeo: principal starts with {@code group:}</li>
   *   <li>Unknown: always returns {@code false} (all principals treated as users)</li>
   * </ul>
   *
   * @param principalId the CMIS ACE principal ID (never null or empty)
   * @return {@code true} if this principal represents a group
   */
  boolean isGroup(String principalId);

  /**
   * Resolve a group principal to its individual member usernames.
   *
   * <p>Returns <b>lowercase</b> usernames for case-insensitive matching
   * with identity providers (Keycloak, AD, LDAP).</p>
   *
   * <p>Returns an empty set if:
   * <ul>
   *   <li>The group represents "everyone" (expansion would be the entire user base)</li>
   *   <li>Group resolution is not supported or has been disabled</li>
   *   <li>The group was not found on the server</li>
   *   <li>Any error occurred during resolution</li>
   * </ul>
   *
   * <p>Implementations should cache results to avoid repeated API calls
   * for the same group across different documents in a crawl session.</p>
   *
   * @param groupPrincipalId the group principal ID (e.g., {@code GROUP_site_demo_SiteManager})
   * @return set of lowercase member usernames, or empty set
   */
  Set<String> resolveMembers(String groupPrincipalId);

  /**
   * Reset any cached state. Called when the CMIS session is disconnected
   * or released. Implementations should clear caches and re-enable any
   * disabled features (e.g., group resolution that was disabled due to
   * API errors).
   */
  void reset();
}
