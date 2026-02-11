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
import java.util.Set;

/**
 * Default (no-op) implementation of {@link GroupMemberResolver} for CMIS
 * servers where vendor-specific group resolution is not available.
 *
 * <p>This resolver treats <b>all</b> CMIS principals as individual users —
 * no group detection or expansion is performed. All principal IDs are stored
 * as-is (lowercased) in the search index.</p>
 *
 * <p>This is used when:
 * <ul>
 *   <li>The CMIS server vendor is not recognized (not Alfresco, Nuxeo, etc.)</li>
 *   <li>The server does not expose a group membership API</li>
 *   <li>Group resolution is intentionally disabled</li>
 * </ul>
 *
 * <p>ACL-based search filtering will still work, but only for principals
 * that are directly assigned to documents — group membership will not be
 * expanded to individual users at crawl time.</p>
 *
 * @see GroupMemberResolver
 * @see GroupMemberResolverFactory
 */
public class NoOpGroupMemberResolver implements GroupMemberResolver {

  /**
   * Always returns {@code false} — without vendor-specific knowledge,
   * we cannot reliably distinguish groups from users in opaque CMIS
   * principal IDs.
   */
  @Override
  public boolean isGroup(String principalId) {
    return false;
  }

  /**
   * Always returns an empty set — no group expansion is performed.
   */
  @Override
  public Set<String> resolveMembers(String groupPrincipalId) {
    return Collections.emptySet();
  }

  /**
   * No-op — there is no state to reset.
   */
  @Override
  public void reset() {
    // nothing to do
  }
}
