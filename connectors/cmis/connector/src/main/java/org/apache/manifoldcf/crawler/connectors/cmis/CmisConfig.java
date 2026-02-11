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

import org.apache.commons.lang.StringUtils;

/** 
 * Parameters data for the CMIS repository connector.
*/
public class CmisConfig {
  
  /** Username */
  public static final String USERNAME_PARAM = "username";
 
  /** Password */
  public static final String PASSWORD_PARAM = "password";
  
  /** Protocol */
  public static final String PROTOCOL_PARAM = "protocol";
  
  /** Server name */
  public static final String SERVER_PARAM = "server";
  
  /** Port */
  public static final String PORT_PARAM = "port";
  
  /** Path of the context of the CMIS AtomPub api */
  public static final String PATH_PARAM = "path";
  
  /** CMIS Repository Id */
  public static final String REPOSITORY_ID_PARAM = "repositoryId";
  
  /** CMIS protocol binding */
  public static final String BINDING_PARAM = "binding";
  
  /** CMIS Query */
  public static final String CMIS_QUERY_PARAM = "cmisQuery";
  
  //default values
  public static final String USERNAME_DEFAULT_VALUE = "dummyuser";
  public static final String PASSWORD_DEFAULT_VALUE = "dummysecrect";
  public static final String PROTOCOL_DEFAULT_VALUE = "http";
  public static final String SERVER_DEFAULT_VALUE = "localhost";
  public static final String PORT_DEFAULT_VALUE = "9090";
  public static final String BINDING_DEFAULT_VALUE = "atom";
  public static final String PATH_DEFAULT_VALUE = "/chemistry-opencmis-server-inmemory/atom";
  public static final String REPOSITORY_ID_DEFAULT_VALUE = StringUtils.EMPTY;
  public static final String BINDING_ATOM_VALUE = "atom";
  public static final String BINDING_WS_VALUE = "ws";
  public static final String BINDING_BROWSER_VALUE = "browser";

  /** CMIS Vendor selection */
  public static final String VENDOR_PARAM = "cmisVendor";

  /** Group List API URL path (relative to protocol://server:port) */
  public static final String GROUP_API_URL_PARAM = "groupApiUrl";

  /** Group Members API URL path template (use {groupId} as placeholder) */
  public static final String GROUP_MEMBERS_API_URL_PARAM = "groupMembersApiUrl";

  /** Transient test result parameter (not persisted meaningfully) */
  public static final String GROUP_API_TEST_RESULT_PARAM = "groupApiTestResult";

  // Default values for new params
  public static final String VENDOR_DEFAULT_VALUE = "other";
  public static final String GROUP_API_URL_DEFAULT_VALUE = "";
  public static final String GROUP_MEMBERS_API_URL_DEFAULT_VALUE = "";
  
}
