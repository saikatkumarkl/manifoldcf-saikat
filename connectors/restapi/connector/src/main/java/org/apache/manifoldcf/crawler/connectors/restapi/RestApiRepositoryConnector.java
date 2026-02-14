/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.manifoldcf.crawler.connectors.restapi;

import org.apache.commons.lang.StringUtils;
import org.apache.manifoldcf.agents.interfaces.RepositoryDocument;
import org.apache.manifoldcf.agents.interfaces.ServiceInterruption;
import org.apache.manifoldcf.core.interfaces.*;
import org.apache.manifoldcf.crawler.connectors.BaseRepositoryConnector;
import org.apache.manifoldcf.crawler.interfaces.*;
import org.apache.manifoldcf.crawler.system.Logging;

import javax.net.ssl.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * REST API Repository Connector for Apache ManifoldCF.
 *
 * <p>This connector crawls content from any REST API and indexes it into the
 * configured output (e.g., OpenSearch/Elasticsearch). It supports pre-configured
 * vendor templates for popular REST API providers (Confluence, Jira, WordPress,
 * GitHub, SharePoint, Notion, Drupal, Alfresco REST) and also allows fully
 * custom REST API configurations.</p>
 *
 * <p><b>Architecture:</b></p>
 * <ul>
 *   <li><b>Seeding:</b> Calls the seed endpoint with pagination to discover document IDs</li>
 *   <li><b>Processing:</b> For each document ID, fetches detail + content + ACL from REST API</li>
 *   <li><b>ACL:</b> Extracts allow/deny tokens from the ACL endpoint response and sets them
 *       on the RepositoryDocument for OpenSearch indexing</li>
 * </ul>
 *
 * <p>Pagination strategies: offset-based, page-based, cursor-based, link-following, or none.</p>
 *
 * <p>Response field extraction uses simple JSONPath-like expressions (e.g., {@code $.data[*].id}).</p>
 */
public class RestApiRepositoryConnector extends BaseRepositoryConnector {

  // ── Activities ──────────────────────────────────────────────────────────

  private static final String ACTIVITY_FETCH = "fetch";
  private static final String ACTIVITY_READ = "read document";

  private static final String[] activitiesList = new String[]{ACTIVITY_FETCH, ACTIVITY_READ};

  // ── Template paths ──────────────────────────────────────────────────────

  private static final String EDIT_CONFIG_HEADER_FORWARD = "editConfiguration.js";
  private static final String EDIT_CONFIG_FORWARD_SERVER = "editConfiguration_Server.html";
  private static final String VIEW_CONFIG_FORWARD = "viewConfiguration.html";
  private static final String EDIT_SPEC_HEADER_FORWARD = "editSpecification.js";
  private static final String EDIT_SPEC_FORWARD_QUERY = "editSpecification_Query.html";
  private static final String VIEW_SPEC_FORWARD = "viewSpecification.html";

  // ── Tab property keys ──────────────────────────────────────────────────

  private static final String RESTAPI_SERVER_TAB_PROPERTY = "RestApiRepositoryConnector.Server";
  private static final String RESTAPI_QUERY_TAB_PROPERTY = "RestApiRepositoryConnector.Query";

  // ── Spec node type ─────────────────────────────────────────────────────

  private static final String JOB_STARTPOINT_NODE_TYPE = "startpoint";

  // ── Session fields ─────────────────────────────────────────────────────

  protected String vendor = null;
  protected String authType = null;
  protected String username = null;
  protected String password = null;
  protected String apiKey = null;
  protected String apiKeyHeader = null;
  protected String protocol = null;
  protected String server = null;
  protected String port = null;
  protected String basePath = null;
  protected String seedEndpoint = null;
  protected String docEndpoint = null;
  protected String contentEndpoint = null;
  protected String aclEndpoint = null;
  protected String paginationType = null;
  protected String pageSize = null;
  protected String offsetParamName = null;
  protected String limitParamName = null;
  protected String pageParamName = null;
  protected String cursorField = null;
  protected String cursorParamName = null;
  protected String itemsPath = null;
  protected String idField = null;
  protected String titleField = null;
  protected String contentField = null;
  protected String mimeTypeField = null;
  protected String createdDateField = null;
  protected String modifiedDateField = null;
  protected String sizeField = null;
  protected String downloadUrlField = null;
  protected String aclAllowField = null;
  protected String aclDenyField = null;
  protected String aclPrincipalField = null;
  protected String responseType = null;
  protected String customHeaders = null;
  protected long maxFileSizeBytes = 0L;

  protected boolean sessionInitialized = false;
  protected long lastSessionFetch = -1L;
  protected static final long timeToRelease = 300000L; // 5 minutes

  // ── Connector Model ────────────────────────────────────────────────────

  @Override
  public int getConnectorModel() {
    return MODEL_ADD_CHANGE;
  }

  @Override
  public String[] getActivitiesList() {
    return activitiesList;
  }

  // ── Connect / Disconnect ───────────────────────────────────────────────

  @Override
  public void connect(ConfigParams configParams) {
    super.connect(configParams);
    readConfigParams(configParams);
  }

  @Override
  public void disconnect() throws ManifoldCFException {
    vendor = null;
    authType = null;
    username = null;
    password = null;
    apiKey = null;
    apiKeyHeader = null;
    protocol = null;
    server = null;
    port = null;
    basePath = null;
    seedEndpoint = null;
    docEndpoint = null;
    contentEndpoint = null;
    aclEndpoint = null;
    paginationType = null;
    pageSize = null;
    offsetParamName = null;
    limitParamName = null;
    pageParamName = null;
    cursorField = null;
    cursorParamName = null;
    itemsPath = null;
    idField = null;
    titleField = null;
    contentField = null;
    mimeTypeField = null;
    createdDateField = null;
    modifiedDateField = null;
    sizeField = null;
    downloadUrlField = null;
    aclAllowField = null;
    aclDenyField = null;
    aclPrincipalField = null;
    responseType = null;
    customHeaders = null;
    sessionInitialized = false;
    lastSessionFetch = -1L;
    super.disconnect();
  }

  @Override
  public String check() throws ManifoldCFException {
    try {
      ensureSession();
      // Test connection by calling the seed endpoint with limit 1
      String baseUrl = buildBaseUrl();
      String testUrl = baseUrl + seedEndpoint;
      if (testUrl.contains("?")) {
        testUrl += "&" + limitParamName + "=1";
      } else {
        testUrl += "?" + limitParamName + "=1";
      }
      String response = httpGet(testUrl);
      if (response != null) {
        return super.check();
      }
      return "Connection failed: no response from REST API";
    } catch (Exception e) {
      return "Connection failed: " + e.getMessage();
    }
  }

  @Override
  public boolean isConnected() {
    return sessionInitialized;
  }

  @Override
  public void poll() throws ManifoldCFException {
    if (lastSessionFetch == -1L)
      return;
    long currentTime = System.currentTimeMillis();
    if (currentTime >= lastSessionFetch + timeToRelease) {
      sessionInitialized = false;
      lastSessionFetch = -1L;
    }
  }

  // ── Session Management ─────────────────────────────────────────────────

  protected void ensureSession() throws ManifoldCFException {
    if (!sessionInitialized) {
      if (StringUtils.isEmpty(server))
        throw new ManifoldCFException("Parameter " + RestApiConfig.SERVER_PARAM + " required but not set");
      if (StringUtils.isEmpty(seedEndpoint))
        throw new ManifoldCFException("Parameter " + RestApiConfig.SEED_ENDPOINT_PARAM + " required but not set");

      // Validate auth
      if ("basic".equals(authType)) {
        if (StringUtils.isEmpty(username))
          throw new ManifoldCFException("Username required for Basic authentication");
        if (StringUtils.isEmpty(password))
          throw new ManifoldCFException("Password required for Basic authentication");
      } else if ("bearer".equals(authType) || "apikey".equals(authType)) {
        if (StringUtils.isEmpty(apiKey))
          throw new ManifoldCFException("API key/token required for " + authType + " authentication");
      }

      // Set up HTTPS trust if needed
      if ("https".equalsIgnoreCase(protocol)) {
        try {
          setupSslTrust();
        } catch (Exception e) {
          Logging.connectors.warn("REST API: Could not set up SSL trust: " + e.getMessage());
        }
      }

      sessionInitialized = true;
      lastSessionFetch = System.currentTimeMillis();
    }
  }

  // ── Seeding ────────────────────────────────────────────────────────────

  @Override
  public String addSeedDocuments(ISeedingActivity activities, Specification spec,
      String lastSeedVersion, long seedTime, int jobMode)
      throws ManifoldCFException, ServiceInterruption {

    ensureSession();

    // Get query from job specification
    String query = StringUtils.EMPTY;
    for (int i = 0; i < spec.getChildCount(); i++) {
      SpecificationNode sn = spec.getChild(i);
      if (sn.getType().equals(JOB_STARTPOINT_NODE_TYPE)) {
        query = sn.getAttributeValue(RestApiConfig.QUERY_PARAM);
        break;
      }
    }

    String baseUrl = buildBaseUrl();
    int pageSizeInt = parseIntSafe(pageSize, 100);

    try {
      switch (paginationType != null ? paginationType : "offset") {
        case "offset":
          seedWithOffsetPagination(activities, baseUrl, query, pageSizeInt);
          break;
        case "page":
          seedWithPagePagination(activities, baseUrl, query, pageSizeInt);
          break;
        case "cursor":
          seedWithCursorPagination(activities, baseUrl, query, pageSizeInt);
          break;
        case "link":
          seedWithLinkPagination(activities, baseUrl, query, pageSizeInt);
          break;
        case "none":
          seedWithNoPagination(activities, baseUrl, query);
          break;
        default:
          seedWithOffsetPagination(activities, baseUrl, query, pageSizeInt);
      }
    } catch (IOException e) {
      throw new ManifoldCFException("Error seeding documents: " + e.getMessage(), e);
    }

    return StringUtils.EMPTY;
  }

  private void seedWithOffsetPagination(ISeedingActivity activities, String baseUrl,
      String query, int pageSizeInt) throws ManifoldCFException, IOException {
    int offset = 0;
    while (true) {
      String url = buildSeedUrl(baseUrl, query);
      url = appendParam(url, limitParamName, String.valueOf(pageSizeInt));
      url = appendParam(url, offsetParamName, String.valueOf(offset));

      String response = httpGet(url);
      List<String> ids = extractIds(response);
      if (ids.isEmpty()) break;

      for (String id : ids) {
        activities.addSeedDocument(id);
      }
      if (ids.size() < pageSizeInt) break;
      offset += pageSizeInt;
    }
  }

  private void seedWithPagePagination(ISeedingActivity activities, String baseUrl,
      String query, int pageSizeInt) throws ManifoldCFException, IOException {
    int page = 1;
    while (true) {
      String url = buildSeedUrl(baseUrl, query);
      url = appendParam(url, pageParamName != null ? pageParamName : "page", String.valueOf(page));
      url = appendParam(url, limitParamName != null ? limitParamName : "per_page", String.valueOf(pageSizeInt));

      String response = httpGet(url);
      List<String> ids = extractIds(response);
      if (ids.isEmpty()) break;

      for (String id : ids) {
        activities.addSeedDocument(id);
      }
      if (ids.size() < pageSizeInt) break;
      page++;
    }
  }

  private void seedWithCursorPagination(ISeedingActivity activities, String baseUrl,
      String query, int pageSizeInt) throws ManifoldCFException, IOException {
    String cursor = null;
    while (true) {
      String url = buildSeedUrl(baseUrl, query);
      url = appendParam(url, limitParamName != null ? limitParamName : "limit", String.valueOf(pageSizeInt));
      if (cursor != null && cursorParamName != null) {
        url = appendParam(url, cursorParamName, cursor);
      }

      String response = httpGet(url);
      List<String> ids = extractIds(response);
      if (ids.isEmpty()) break;

      for (String id : ids) {
        activities.addSeedDocument(id);
      }

      // Extract next cursor
      String nextCursor = extractJsonValue(response, cursorField);
      if (nextCursor == null || nextCursor.isEmpty() || nextCursor.equals("null")) break;
      cursor = nextCursor;
    }
  }

  private void seedWithLinkPagination(ISeedingActivity activities, String baseUrl,
      String query, int pageSizeInt) throws ManifoldCFException, IOException {
    String url = buildSeedUrl(baseUrl, query);
    url = appendParam(url, limitParamName != null ? limitParamName : "$top", String.valueOf(pageSizeInt));

    while (url != null) {
      String response = httpGet(url);
      List<String> ids = extractIds(response);
      if (ids.isEmpty()) break;

      for (String id : ids) {
        activities.addSeedDocument(id);
      }

      // Extract next link URL from response
      String nextUrl = extractJsonValue(response, cursorField);
      if (nextUrl == null || nextUrl.isEmpty() || nextUrl.equals("null")) break;

      // If next URL is relative, prepend base
      if (nextUrl.startsWith("/")) {
        url = protocol + "://" + server + ":" + port + nextUrl;
      } else if (nextUrl.startsWith("http")) {
        url = nextUrl;
      } else {
        break;
      }
    }
  }

  private void seedWithNoPagination(ISeedingActivity activities, String baseUrl,
      String query) throws ManifoldCFException, IOException {
    String url = buildSeedUrl(baseUrl, query);
    String response = httpGet(url);
    List<String> ids = extractIds(response);
    for (String id : ids) {
      activities.addSeedDocument(id);
    }
  }

  // ── Document Processing ────────────────────────────────────────────────

  @Override
  public int getMaxDocumentRequest() {
    return 1;
  }

  @Override
  public String[] getRelationshipTypes() {
    return new String[]{};
  }

  @Override
  public void processDocuments(String[] documentIdentifiers, IExistingVersions statuses,
      Specification spec, IProcessActivity activities, int jobMode, boolean usesDefaultAuthority)
      throws ManifoldCFException, ServiceInterruption {

    ensureSession();

    for (String documentId : documentIdentifiers) {
      String errorCode = null;
      String errorDesc = null;
      Long fileLengthLong = null;
      long startTime = System.currentTimeMillis();

      try {
        Logging.connectors.debug("REST API: Processing document '" + documentId + "'");

        // Fetch document detail
        String baseUrl = buildBaseUrl();
        String docUrl = baseUrl + replaceId(docEndpoint, documentId);
        String docResponse;
        try {
          docResponse = httpGet(docUrl);
        } catch (Exception e) {
          Logging.connectors.warn("REST API: Could not fetch document '" + documentId + "': " + e.getMessage());
          activities.deleteDocument(documentId);
          continue;
        }

        if (docResponse == null || docResponse.trim().isEmpty()) {
          activities.deleteDocument(documentId);
          continue;
        }

        // Extract metadata
        String title = extractJsonValue(docResponse, titleField);
        String mimeType = extractJsonValue(docResponse, mimeTypeField);
        String createdStr = extractJsonValue(docResponse, createdDateField);
        String modifiedStr = extractJsonValue(docResponse, modifiedDateField);
        String sizeStr = extractJsonValue(docResponse, sizeField);
        String downloadUrl = extractJsonValue(docResponse, downloadUrlField);

        if (title == null || title.isEmpty()) title = documentId;
        if (mimeType == null || mimeType.isEmpty()) mimeType = "text/html";

        Date createdDate = parseDate(createdStr);
        Date modifiedDate = parseDate(modifiedStr);
        long fileLength = parseLongSafe(sizeStr, 0L);

        // Version string
        String versionString = documentId + ":" +
            (modifiedDate != null ? modifiedDate.getTime() : System.currentTimeMillis());

        if (!activities.checkDocumentNeedsReindexing(documentId, versionString)) {
          continue;
        }

        // Enforce max file size
        if (maxFileSizeBytes > 0 && fileLength > maxFileSizeBytes) {
          activities.noDocument(documentId, versionString);
          errorCode = IProcessActivity.EXCLUDED_LENGTH;
          errorDesc = "Exceeds max file size: " + fileLength + " > " + maxFileSizeBytes;
          continue;
        }

        // Fetch content
        String contentBody = null;
        InputStream contentStream = null;

        if (StringUtils.isNotEmpty(contentEndpoint)) {
          // Binary content endpoint
          String contentUrl;
          if (StringUtils.isNotEmpty(downloadUrl)) {
            contentUrl = downloadUrl.startsWith("http") ? downloadUrl :
                protocol + "://" + server + ":" + port + downloadUrl;
          } else {
            contentUrl = baseUrl + replaceId(contentEndpoint, documentId);
          }
          try {
            contentStream = httpGetStream(contentUrl);
          } catch (Exception e) {
            Logging.connectors.warn("REST API: Could not fetch content for '" + documentId + "': " + e.getMessage());
          }
        } else if (StringUtils.isNotEmpty(contentField)) {
          // Content from the doc response JSON
          contentBody = extractJsonValue(docResponse, contentField);
        }

        if (contentBody == null && contentStream == null) {
          // Use the full JSON response as content
          contentBody = docResponse;
          mimeType = "application/json";
        }

        // Build RepositoryDocument
        RepositoryDocument rd = new RepositoryDocument();
        rd.setFileName(sanitizeFileName(title));
        rd.setMimeType(mimeType);
        if (createdDate != null) rd.setCreatedDate(createdDate);
        if (modifiedDate != null) rd.setModifiedDate(modifiedDate);

        // Add metadata fields
        rd.addField("title", title);
        rd.addField("source_vendor", vendor != null ? vendor : "restapi");
        rd.addField("source_url", docUrl);

        // Extract and set ACLs
        extractAndSetAcl(documentId, baseUrl, rd);

        // Set binary content
        try {
          if (contentStream != null) {
            rd.setBinary(contentStream, fileLength > 0 ? fileLength : 0);
          } else if (contentBody != null) {
            byte[] contentBytes = contentBody.getBytes(StandardCharsets.UTF_8);
            rd.setBinary(new ByteArrayInputStream(contentBytes), contentBytes.length);
            fileLength = contentBytes.length;
          }

          // Document URI
          String documentURI = docUrl;

          activities.ingestDocumentWithException(documentId, versionString, documentURI, rd);
          fileLengthLong = fileLength;
          errorCode = "OK";
        } catch (IOException e) {
          errorCode = e.getClass().getSimpleName().toUpperCase(Locale.ROOT);
          errorDesc = e.getMessage();
        } finally {
          if (contentStream != null) {
            try {
              contentStream.close();
            } catch (IOException e) {
              // ignore
            }
          }
        }
      } catch (ManifoldCFException e) {
        if (e.getErrorCode() == ManifoldCFException.INTERRUPTED)
          errorCode = null;
        throw e;
      } catch (Exception e) {
        errorCode = "ERROR";
        errorDesc = e.getMessage();
        Logging.connectors.warn("REST API: Error processing '" + documentId + "': " + e.getMessage(), e);
      } finally {
        if (errorCode != null)
          activities.recordActivity(startTime, ACTIVITY_READ,
              fileLengthLong, documentId, errorCode, errorDesc, null);
      }
    }
  }

  // ── ACL Extraction ─────────────────────────────────────────────────────

  /**
   * Extract ACLs from the REST API and set them on the RepositoryDocument.
   * If no ACL endpoint is configured, no security is set (falls back to __nosecurity__).
   */
  private void extractAndSetAcl(String documentId, String baseUrl, RepositoryDocument rd) {
    if (StringUtils.isEmpty(aclEndpoint)) {
      return;
    }

    try {
      String aclUrl = baseUrl + replaceId(aclEndpoint, documentId);
      String aclResponse = httpGet(aclUrl);

      if (aclResponse == null || aclResponse.trim().isEmpty()) {
        return;
      }

      List<String> allowTokens = new ArrayList<>();
      List<String> denyTokens = new ArrayList<>();

      // Extract allow tokens
      if (StringUtils.isNotEmpty(aclAllowField)) {
        List<String> principals = extractJsonArray(aclResponse, aclAllowField, aclPrincipalField);
        for (String p : principals) {
          if (p != null && !p.isEmpty()) {
            allowTokens.add(p.toLowerCase(Locale.ROOT));
          }
        }
      }

      // Extract deny tokens
      if (StringUtils.isNotEmpty(aclDenyField)) {
        List<String> principals = extractJsonArray(aclResponse, aclDenyField, aclPrincipalField);
        for (String p : principals) {
          if (p != null && !p.isEmpty()) {
            denyTokens.add(p.toLowerCase(Locale.ROOT));
          }
        }
      }

      if (!allowTokens.isEmpty()) {
        rd.setSecurity(RepositoryDocument.SECURITY_TYPE_DOCUMENT,
            allowTokens.toArray(new String[0]),
            denyTokens.toArray(new String[0]));
      }
    } catch (Exception e) {
      Logging.connectors.warn("REST API: Could not extract ACLs for '" + documentId + "': " + e.getMessage());
    }
  }

  // ── Configuration UI ───────────────────────────────────────────────────

  @Override
  public void viewConfiguration(IThreadContext threadContext, IHTTPOutput out,
      Locale locale, ConfigParams parameters) throws ManifoldCFException, IOException {
    Map<String, String> paramMap = new HashMap<>();
    fillInServerConfigurationMap(paramMap, out, parameters);
    outputResource(VIEW_CONFIG_FORWARD, out, locale, paramMap);
  }

  @Override
  public void outputConfigurationHeader(IThreadContext threadContext, IHTTPOutput out,
      Locale locale, ConfigParams parameters, List<String> tabsArray)
      throws ManifoldCFException, IOException {
    tabsArray.add(Messages.getString(locale, RESTAPI_SERVER_TAB_PROPERTY));
    Map<String, String> paramMap = new HashMap<>();
    fillInServerConfigurationMap(paramMap, out, parameters);
    outputResource(EDIT_CONFIG_HEADER_FORWARD, out, locale, paramMap);
  }

  @Override
  public void outputConfigurationBody(IThreadContext threadContext, IHTTPOutput out,
      Locale locale, ConfigParams parameters, String tabName)
      throws ManifoldCFException, IOException {
    Map<String, String> paramMap = new HashMap<>();
    paramMap.put("TabName", tabName);
    fillInServerConfigurationMap(paramMap, out, parameters);
    outputResource(EDIT_CONFIG_FORWARD_SERVER, out, locale, paramMap);
  }

  @Override
  public String processConfigurationPost(IThreadContext threadContext,
      IPostParameters variableContext, ConfigParams parameters)
      throws ManifoldCFException {

    // Process all parameters from the form
    String[] paramNames = {
        RestApiConfig.VENDOR_PARAM, RestApiConfig.AUTH_TYPE_PARAM,
        RestApiConfig.USERNAME_PARAM, RestApiConfig.PROTOCOL_PARAM,
        RestApiConfig.SERVER_PARAM, RestApiConfig.PORT_PARAM,
        RestApiConfig.BASE_PATH_PARAM, RestApiConfig.SEED_ENDPOINT_PARAM,
        RestApiConfig.DOC_ENDPOINT_PARAM, RestApiConfig.CONTENT_ENDPOINT_PARAM,
        RestApiConfig.ACL_ENDPOINT_PARAM, RestApiConfig.PAGINATION_TYPE_PARAM,
        RestApiConfig.PAGE_SIZE_PARAM, RestApiConfig.OFFSET_PARAM_NAME,
        RestApiConfig.LIMIT_PARAM_NAME, RestApiConfig.PAGE_PARAM_NAME,
        RestApiConfig.CURSOR_FIELD_PARAM, RestApiConfig.CURSOR_PARAM_NAME,
        RestApiConfig.ITEMS_PATH_PARAM, RestApiConfig.ID_FIELD_PARAM,
        RestApiConfig.TITLE_FIELD_PARAM, RestApiConfig.CONTENT_FIELD_PARAM,
        RestApiConfig.MIMETYPE_FIELD_PARAM, RestApiConfig.CREATED_DATE_FIELD_PARAM,
        RestApiConfig.MODIFIED_DATE_FIELD_PARAM, RestApiConfig.SIZE_FIELD_PARAM,
        RestApiConfig.DOWNLOAD_URL_FIELD_PARAM,
        RestApiConfig.ACL_ALLOW_FIELD_PARAM, RestApiConfig.ACL_DENY_FIELD_PARAM,
        RestApiConfig.ACL_PRINCIPAL_FIELD_PARAM, RestApiConfig.MAX_FILE_SIZE_PARAM,
        RestApiConfig.CUSTOM_HEADERS_PARAM, RestApiConfig.RESPONSE_TYPE_PARAM,
        RestApiConfig.API_KEY_PARAM, RestApiConfig.API_KEY_HEADER_PARAM
    };

    for (String paramName : paramNames) {
      String value = variableContext.getParameter(paramName);
      if (value != null) {
        // Handle password/apikey specially
        if (paramName.equals(RestApiConfig.PASSWORD_PARAM)) {
          parameters.setParameter(paramName, variableContext.mapKeyToPassword(value));
        } else if (paramName.equals(RestApiConfig.API_KEY_PARAM)) {
          parameters.setParameter(paramName, variableContext.mapKeyToPassword(value));
        } else if (paramName.equals(RestApiConfig.PORT_PARAM)) {
          try {
            Integer.parseInt(value);
            parameters.setParameter(paramName, value);
          } catch (NumberFormatException e) {
            // ignore invalid port
          }
        } else {
          parameters.setParameter(paramName, value);
        }
      }
    }

    // Handle test connection
    String testConnection = variableContext.getParameter("_testConnection");
    if ("true".equals(testConnection)) {
      String result = testApiConnection(parameters);
      parameters.setParameter("TESTRESULT", result);
    } else {
      parameters.setParameter("TESTRESULT", "");
    }

    return null;
  }

  // ── Specification UI ───────────────────────────────────────────────────

  @Override
  public void viewSpecification(IHTTPOutput out, Locale locale, Specification ds,
      int connectionSequenceNumber) throws ManifoldCFException, IOException {
    Map<String, String> paramMap = new HashMap<>();
    paramMap.put("SeqNum", Integer.toString(connectionSequenceNumber));
    fillInQuerySpecificationMap(paramMap, ds);
    outputResource(VIEW_SPEC_FORWARD, out, locale, paramMap);
  }

  @Override
  public void outputSpecificationHeader(IHTTPOutput out, Locale locale, Specification ds,
      int connectionSequenceNumber, List<String> tabsArray)
      throws ManifoldCFException, IOException {
    tabsArray.add(Messages.getString(locale, RESTAPI_QUERY_TAB_PROPERTY));
    Map<String, String> paramMap = new HashMap<>();
    paramMap.put("SeqNum", Integer.toString(connectionSequenceNumber));
    fillInQuerySpecificationMap(paramMap, ds);
    outputResource(EDIT_SPEC_HEADER_FORWARD, out, locale, paramMap);
  }

  @Override
  public void outputSpecificationBody(IHTTPOutput out, Locale locale, Specification ds,
      int connectionSequenceNumber, int actualSequenceNumber, String tabName)
      throws ManifoldCFException, IOException {
    Map<String, String> paramMap = new HashMap<>();
    paramMap.put("TabName", tabName);
    paramMap.put("SeqNum", Integer.toString(connectionSequenceNumber));
    paramMap.put("SelectedNum", Integer.toString(actualSequenceNumber));
    fillInQuerySpecificationMap(paramMap, ds);
    outputResource(EDIT_SPEC_FORWARD_QUERY, out, locale, paramMap);
  }

  @Override
  public String processSpecificationPost(IPostParameters variableContext, Locale locale,
      Specification ds, int connectionSequenceNumber) throws ManifoldCFException {
    String seqPrefix = "s" + connectionSequenceNumber + "_";
    String query = variableContext.getParameter(seqPrefix + RestApiConfig.QUERY_PARAM);
    if (query != null) {
      int i = 0;
      while (i < ds.getChildCount()) {
        SpecificationNode oldNode = ds.getChild(i);
        if (oldNode.getType().equals(JOB_STARTPOINT_NODE_TYPE)) {
          ds.removeChild(i);
          break;
        }
        i++;
      }
      SpecificationNode node = new SpecificationNode(JOB_STARTPOINT_NODE_TYPE);
      node.setAttribute(RestApiConfig.QUERY_PARAM, query);
      ds.addChild(ds.getChildCount(), node);
    }
    return null;
  }

  // ── Helper: Fill in configuration maps ─────────────────────────────────

  private static void fillInServerConfigurationMap(Map<String, String> map,
      IPasswordMapperActivity mapper, ConfigParams parameters) {
    putParam(map, parameters, RestApiConfig.VENDOR_PARAM, RestApiConfig.VENDOR_DEFAULT);
    putParam(map, parameters, RestApiConfig.AUTH_TYPE_PARAM, RestApiConfig.AUTH_TYPE_DEFAULT);
    putParam(map, parameters, RestApiConfig.USERNAME_PARAM, "");
    putParam(map, parameters, RestApiConfig.PROTOCOL_PARAM, RestApiConfig.PROTOCOL_DEFAULT);
    putParam(map, parameters, RestApiConfig.SERVER_PARAM, "");
    putParam(map, parameters, RestApiConfig.PORT_PARAM, RestApiConfig.PORT_DEFAULT);
    putParam(map, parameters, RestApiConfig.BASE_PATH_PARAM, "");
    putParam(map, parameters, RestApiConfig.SEED_ENDPOINT_PARAM, "");
    putParam(map, parameters, RestApiConfig.DOC_ENDPOINT_PARAM, "");
    putParam(map, parameters, RestApiConfig.CONTENT_ENDPOINT_PARAM, "");
    putParam(map, parameters, RestApiConfig.ACL_ENDPOINT_PARAM, "");
    putParam(map, parameters, RestApiConfig.PAGINATION_TYPE_PARAM, RestApiConfig.PAGINATION_TYPE_DEFAULT);
    putParam(map, parameters, RestApiConfig.PAGE_SIZE_PARAM, RestApiConfig.PAGE_SIZE_DEFAULT);
    putParam(map, parameters, RestApiConfig.OFFSET_PARAM_NAME, RestApiConfig.OFFSET_PARAM_DEFAULT);
    putParam(map, parameters, RestApiConfig.LIMIT_PARAM_NAME, RestApiConfig.LIMIT_PARAM_DEFAULT);
    putParam(map, parameters, RestApiConfig.PAGE_PARAM_NAME, RestApiConfig.PAGE_PARAM_DEFAULT);
    putParam(map, parameters, RestApiConfig.CURSOR_FIELD_PARAM, "");
    putParam(map, parameters, RestApiConfig.CURSOR_PARAM_NAME, "");
    putParam(map, parameters, RestApiConfig.ITEMS_PATH_PARAM, RestApiConfig.ITEMS_PATH_DEFAULT);
    putParam(map, parameters, RestApiConfig.ID_FIELD_PARAM, RestApiConfig.ID_FIELD_DEFAULT);
    putParam(map, parameters, RestApiConfig.TITLE_FIELD_PARAM, RestApiConfig.TITLE_FIELD_DEFAULT);
    putParam(map, parameters, RestApiConfig.CONTENT_FIELD_PARAM, RestApiConfig.CONTENT_FIELD_DEFAULT);
    putParam(map, parameters, RestApiConfig.MIMETYPE_FIELD_PARAM, RestApiConfig.MIMETYPE_FIELD_DEFAULT);
    putParam(map, parameters, RestApiConfig.CREATED_DATE_FIELD_PARAM, RestApiConfig.CREATED_DATE_FIELD_DEFAULT);
    putParam(map, parameters, RestApiConfig.MODIFIED_DATE_FIELD_PARAM, RestApiConfig.MODIFIED_DATE_FIELD_DEFAULT);
    putParam(map, parameters, RestApiConfig.SIZE_FIELD_PARAM, RestApiConfig.SIZE_FIELD_DEFAULT);
    putParam(map, parameters, RestApiConfig.DOWNLOAD_URL_FIELD_PARAM, "");
    putParam(map, parameters, RestApiConfig.ACL_ALLOW_FIELD_PARAM, "");
    putParam(map, parameters, RestApiConfig.ACL_DENY_FIELD_PARAM, "");
    putParam(map, parameters, RestApiConfig.ACL_PRINCIPAL_FIELD_PARAM, "");
    putParam(map, parameters, RestApiConfig.MAX_FILE_SIZE_PARAM, "0");
    putParam(map, parameters, RestApiConfig.CUSTOM_HEADERS_PARAM, "");
    putParam(map, parameters, RestApiConfig.RESPONSE_TYPE_PARAM, RestApiConfig.RESPONSE_TYPE_DEFAULT);
    putParam(map, parameters, RestApiConfig.API_KEY_HEADER_PARAM, RestApiConfig.API_KEY_HEADER_DEFAULT);

    // Password/API key — mask for display
    String pwd = parameters.getParameter(RestApiConfig.PASSWORD_PARAM);
    if (pwd == null) pwd = "";
    else pwd = mapper.mapPasswordToKey(pwd);
    map.put(RestApiConfig.PASSWORD_PARAM, pwd);

    String apiKeyVal = parameters.getParameter(RestApiConfig.API_KEY_PARAM);
    if (apiKeyVal == null) apiKeyVal = "";
    else apiKeyVal = mapper.mapPasswordToKey(apiKeyVal);
    map.put(RestApiConfig.API_KEY_PARAM, apiKeyVal);

    // Test result
    String testResult = parameters.getParameter("TESTRESULT");
    map.put("TESTRESULT", testResult != null ? testResult : "");
  }

  private static void putParam(Map<String, String> map, ConfigParams params, String key, String defaultVal) {
    String val = params.getParameter(key);
    map.put(key, val != null ? val : defaultVal);
  }

  private static void fillInQuerySpecificationMap(Map<String, String> map, Specification ds) {
    String query = "";
    for (int i = 0; i < ds.getChildCount(); i++) {
      SpecificationNode sn = ds.getChild(i);
      if (sn.getType().equals(JOB_STARTPOINT_NODE_TYPE)) {
        query = sn.getAttributeValue(RestApiConfig.QUERY_PARAM);
        if (query == null) query = "";
      }
    }
    map.put(RestApiConfig.QUERY_PARAM, query);
  }

  // ── HTTP Methods ───────────────────────────────────────────────────────

  private String httpGet(String urlStr) throws IOException, ManifoldCFException {
    URL url = new URL(urlStr);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(30000);
    conn.setReadTimeout(60000);
    conn.setRequestProperty("Accept", "application/json");

    // Set auth header
    setAuthHeaders(conn);

    // Set custom headers
    if (StringUtils.isNotEmpty(customHeaders)) {
      for (String line : customHeaders.split("\n")) {
        line = line.trim();
        int eq = line.indexOf('=');
        if (eq > 0) {
          conn.setRequestProperty(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
      }
    }

    int status = conn.getResponseCode();
    if (status == 404) {
      return null;
    }
    if (status >= 400) {
      String errorBody = readErrorStream(conn);
      throw new IOException("HTTP " + status + " " + conn.getResponseMessage()
          + (errorBody.isEmpty() ? "" : " — " + errorBody));
    }

    return readStream(conn.getInputStream());
  }

  private InputStream httpGetStream(String urlStr) throws IOException, ManifoldCFException {
    URL url = new URL(urlStr);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(30000);
    conn.setReadTimeout(60000);
    setAuthHeaders(conn);

    int status = conn.getResponseCode();
    if (status >= 400) {
      String errorBody = readErrorStream(conn);
      throw new IOException("HTTP " + status + ": " + errorBody);
    }
    return conn.getInputStream();
  }

  private void setAuthHeaders(HttpURLConnection conn) {
    if ("basic".equals(authType) && username != null && password != null) {
      String auth = Base64.getEncoder()
          .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
      conn.setRequestProperty("Authorization", "Basic " + auth);
    } else if ("bearer".equals(authType) && apiKey != null) {
      conn.setRequestProperty("Authorization", "Bearer " + apiKey);
    } else if ("apikey".equals(authType) && apiKey != null) {
      String header = apiKeyHeader != null && !apiKeyHeader.isEmpty() ? apiKeyHeader : "X-API-Key";
      conn.setRequestProperty(header, apiKey);
    }
  }

  private String readStream(InputStream is) throws IOException {
    BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder();
    String line;
    while ((line = br.readLine()) != null) {
      sb.append(line);
    }
    br.close();
    return sb.toString();
  }

  private String readErrorStream(HttpURLConnection conn) {
    try {
      InputStream es = conn.getErrorStream();
      if (es != null) {
        String body = readStream(es);
        return body.length() > 500 ? body.substring(0, 500) + "..." : body;
      }
    } catch (Exception e) {
      // ignore
    }
    return "";
  }

  // ── JSON Parsing (simple, no external dependency) ──────────────────────

  /**
   * Extract a single value from JSON using a simplified JSONPath expression.
   * Supports: $.field, $.field.subfield, $.field[0].subfield
   */
  private String extractJsonValue(String json, String jsonPath) {
    if (json == null || jsonPath == null || jsonPath.isEmpty()) return null;

    // Strip leading "$." if present
    String path = jsonPath.startsWith("$.") ? jsonPath.substring(2) : jsonPath;
    if (path.isEmpty()) return json;

    String current = json;
    String[] parts = path.split("\\.");
    for (String part : parts) {
      if (current == null) return null;

      // Handle array index like field[0]
      int bracketIdx = part.indexOf('[');
      if (bracketIdx >= 0) {
        String fieldName = part.substring(0, bracketIdx);
        String idxStr = part.substring(bracketIdx + 1, part.indexOf(']'));

        if (!fieldName.isEmpty()) {
          current = findFieldValue(current, fieldName);
        }
        if (current != null) {
          int idx = Integer.parseInt(idxStr);
          current = getArrayElement(current, idx);
        }
      } else {
        current = findFieldValue(current, part);
      }
    }

    // Clean up extracted value — remove quotes
    if (current != null) {
      current = current.trim();
      if (current.startsWith("\"") && current.endsWith("\"")) {
        current = current.substring(1, current.length() - 1);
      }
    }
    return current;
  }

  /**
   * Extract IDs from the seed response using configured itemsPath and idField.
   */
  private List<String> extractIds(String json) {
    List<String> ids = new ArrayList<>();
    if (json == null || json.trim().isEmpty()) return ids;

    // Get the items array
    String itemsJson;
    if (itemsPath != null && !itemsPath.isEmpty() && !"$".equals(itemsPath)) {
      itemsJson = extractJsonValue(json, itemsPath);
    } else {
      itemsJson = json;
    }

    if (itemsJson == null) return ids;

    // If it's an array, extract each element and get the id field
    if (itemsJson.trim().startsWith("[")) {
      List<String> elements = splitJsonArray(itemsJson);
      for (String element : elements) {
        String id = null;
        if (idField != null && !idField.isEmpty()) {
          // Remove the items path prefix from idField if it starts with $
          String localIdField = idField;
          if (localIdField.startsWith("$.")) {
            localIdField = localIdField.substring(2);
          }
          id = findFieldValue(element, localIdField);
          if (id != null) {
            id = id.trim();
            if (id.startsWith("\"")) id = id.substring(1);
            if (id.endsWith("\"")) id = id.substring(0, id.length() - 1);
          }
        }
        if (id != null && !id.isEmpty()) {
          ids.add(id);
        }
      }
    }

    return ids;
  }

  /**
   * Extract an array of principal values from JSON for ACL processing.
   */
  private List<String> extractJsonArray(String json, String arrayPath, String principalPath) {
    List<String> results = new ArrayList<>();

    String arrayJson = extractJsonValue(json, arrayPath);
    if (arrayJson == null || !arrayJson.trim().startsWith("[")) {
      return results;
    }

    List<String> elements = splitJsonArray(arrayJson);
    for (String element : elements) {
      String principal = null;
      if (principalPath != null && !principalPath.isEmpty()) {
        String localPath = principalPath.startsWith("$.") ? principalPath.substring(2) : principalPath;
        principal = findFieldValue(element, localPath);
        if (principal != null) {
          principal = principal.trim();
          if (principal.startsWith("\"")) principal = principal.substring(1);
          if (principal.endsWith("\"")) principal = principal.substring(0, principal.length() - 1);
        }
      }
      if (principal != null && !principal.isEmpty()) {
        results.add(principal);
      }
    }

    return results;
  }

  /**
   * Find a field value in a JSON object string.
   * Returns the raw value (could be string, object, array, number, boolean).
   */
  private String findFieldValue(String json, String fieldName) {
    if (json == null || fieldName == null) return null;

    // Handle nested paths like "entry.id"
    int dotIdx = fieldName.indexOf('.');
    if (dotIdx >= 0) {
      String first = fieldName.substring(0, dotIdx);
      String rest = fieldName.substring(dotIdx + 1);
      String nested = findFieldValue(json, first);
      if (nested != null) {
        return findFieldValue(nested, rest);
      }
      return null;
    }

    String pattern = "\"" + fieldName + "\"";
    int idx = json.indexOf(pattern);
    if (idx < 0) return null;

    int colonIdx = json.indexOf(':', idx + pattern.length());
    if (colonIdx < 0) return null;

    int valueStart = colonIdx + 1;
    while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) valueStart++;
    if (valueStart >= json.length()) return null;

    char c = json.charAt(valueStart);
    if (c == '"') {
      // String value
      int valueEnd = valueStart + 1;
      while (valueEnd < json.length()) {
        if (json.charAt(valueEnd) == '"' && json.charAt(valueEnd - 1) != '\\') {
          return json.substring(valueStart, valueEnd + 1);
        }
        valueEnd++;
      }
    } else if (c == '{') {
      // Object value — find matching closing brace
      return extractBalanced(json, valueStart, '{', '}');
    } else if (c == '[') {
      // Array value — find matching closing bracket
      return extractBalanced(json, valueStart, '[', ']');
    } else {
      // Number, boolean, null
      int valueEnd = valueStart;
      while (valueEnd < json.length() && json.charAt(valueEnd) != ',' && json.charAt(valueEnd) != '}' && json.charAt(valueEnd) != ']') {
        valueEnd++;
      }
      return json.substring(valueStart, valueEnd).trim();
    }
    return null;
  }

  /** Extract a balanced {..} or [..] block from JSON */
  private String extractBalanced(String json, int start, char open, char close) {
    int depth = 0;
    boolean inString = false;
    for (int i = start; i < json.length(); i++) {
      char c = json.charAt(i);
      if (inString) {
        if (c == '"' && json.charAt(i - 1) != '\\') inString = false;
      } else {
        if (c == '"') inString = true;
        else if (c == open) depth++;
        else if (c == close) {
          depth--;
          if (depth == 0) return json.substring(start, i + 1);
        }
      }
    }
    return null;
  }

  /** Get element at index from a JSON array string */
  private String getArrayElement(String arrayJson, int index) {
    List<String> elements = splitJsonArray(arrayJson);
    return index < elements.size() ? elements.get(index) : null;
  }

  /** Split a JSON array into individual element strings */
  private List<String> splitJsonArray(String arrayJson) {
    List<String> elements = new ArrayList<>();
    if (arrayJson == null) return elements;

    String trimmed = arrayJson.trim();
    if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return elements;

    String inner = trimmed.substring(1, trimmed.length() - 1).trim();
    if (inner.isEmpty()) return elements;

    int depth = 0;
    boolean inString = false;
    int elementStart = 0;

    for (int i = 0; i < inner.length(); i++) {
      char c = inner.charAt(i);
      if (inString) {
        if (c == '"' && (i == 0 || inner.charAt(i - 1) != '\\')) inString = false;
      } else {
        if (c == '"') inString = true;
        else if (c == '{' || c == '[') depth++;
        else if (c == '}' || c == ']') depth--;
        else if (c == ',' && depth == 0) {
          elements.add(inner.substring(elementStart, i).trim());
          elementStart = i + 1;
        }
      }
    }
    // Add last element
    String last = inner.substring(elementStart).trim();
    if (!last.isEmpty()) {
      elements.add(last);
    }

    return elements;
  }

  // ── URL Building ───────────────────────────────────────────────────────

  private String buildBaseUrl() {
    StringBuilder sb = new StringBuilder();
    sb.append(protocol != null ? protocol : "https");
    sb.append("://").append(server);
    if (port != null && !port.isEmpty()) {
      sb.append(":").append(port);
    }
    if (basePath != null && !basePath.isEmpty()) {
      if (!basePath.startsWith("/")) sb.append("/");
      sb.append(basePath);
    }
    return sb.toString();
  }

  private String buildSeedUrl(String baseUrl, String query) {
    String url = baseUrl + seedEndpoint;
    if (StringUtils.isNotEmpty(query)) {
      url = appendParam(url, "q", query);
    }
    return url;
  }

  private String appendParam(String url, String key, String value) {
    if (key == null || key.isEmpty() || value == null) return url;
    return url + (url.contains("?") ? "&" : "?") + key + "=" + value;
  }

  private String replaceId(String template, String id) {
    if (template == null) return "";
    return template.replace("{id}", id);
  }

  // ── Config Reading ─────────────────────────────────────────────────────

  private void readConfigParams(ConfigParams configParams) {
    vendor = configParams.getParameter(RestApiConfig.VENDOR_PARAM);
    authType = configParams.getParameter(RestApiConfig.AUTH_TYPE_PARAM);
    username = configParams.getParameter(RestApiConfig.USERNAME_PARAM);
    password = configParams.getParameter(RestApiConfig.PASSWORD_PARAM);
    apiKey = configParams.getParameter(RestApiConfig.API_KEY_PARAM);
    apiKeyHeader = configParams.getParameter(RestApiConfig.API_KEY_HEADER_PARAM);
    protocol = configParams.getParameter(RestApiConfig.PROTOCOL_PARAM);
    server = configParams.getParameter(RestApiConfig.SERVER_PARAM);
    port = configParams.getParameter(RestApiConfig.PORT_PARAM);
    basePath = configParams.getParameter(RestApiConfig.BASE_PATH_PARAM);
    seedEndpoint = configParams.getParameter(RestApiConfig.SEED_ENDPOINT_PARAM);
    docEndpoint = configParams.getParameter(RestApiConfig.DOC_ENDPOINT_PARAM);
    contentEndpoint = configParams.getParameter(RestApiConfig.CONTENT_ENDPOINT_PARAM);
    aclEndpoint = configParams.getParameter(RestApiConfig.ACL_ENDPOINT_PARAM);
    paginationType = configParams.getParameter(RestApiConfig.PAGINATION_TYPE_PARAM);
    pageSize = configParams.getParameter(RestApiConfig.PAGE_SIZE_PARAM);
    offsetParamName = configParams.getParameter(RestApiConfig.OFFSET_PARAM_NAME);
    limitParamName = configParams.getParameter(RestApiConfig.LIMIT_PARAM_NAME);
    pageParamName = configParams.getParameter(RestApiConfig.PAGE_PARAM_NAME);
    cursorField = configParams.getParameter(RestApiConfig.CURSOR_FIELD_PARAM);
    cursorParamName = configParams.getParameter(RestApiConfig.CURSOR_PARAM_NAME);
    itemsPath = configParams.getParameter(RestApiConfig.ITEMS_PATH_PARAM);
    idField = configParams.getParameter(RestApiConfig.ID_FIELD_PARAM);
    titleField = configParams.getParameter(RestApiConfig.TITLE_FIELD_PARAM);
    contentField = configParams.getParameter(RestApiConfig.CONTENT_FIELD_PARAM);
    mimeTypeField = configParams.getParameter(RestApiConfig.MIMETYPE_FIELD_PARAM);
    createdDateField = configParams.getParameter(RestApiConfig.CREATED_DATE_FIELD_PARAM);
    modifiedDateField = configParams.getParameter(RestApiConfig.MODIFIED_DATE_FIELD_PARAM);
    sizeField = configParams.getParameter(RestApiConfig.SIZE_FIELD_PARAM);
    downloadUrlField = configParams.getParameter(RestApiConfig.DOWNLOAD_URL_FIELD_PARAM);
    aclAllowField = configParams.getParameter(RestApiConfig.ACL_ALLOW_FIELD_PARAM);
    aclDenyField = configParams.getParameter(RestApiConfig.ACL_DENY_FIELD_PARAM);
    aclPrincipalField = configParams.getParameter(RestApiConfig.ACL_PRINCIPAL_FIELD_PARAM);
    responseType = configParams.getParameter(RestApiConfig.RESPONSE_TYPE_PARAM);
    customHeaders = configParams.getParameter(RestApiConfig.CUSTOM_HEADERS_PARAM);

    String maxFileSizeStr = configParams.getParameter(RestApiConfig.MAX_FILE_SIZE_PARAM);
    maxFileSizeBytes = parseLongSafe(maxFileSizeStr, 0L);

    // Apply defaults
    if (protocol == null) protocol = RestApiConfig.PROTOCOL_DEFAULT;
    if (port == null) port = RestApiConfig.PORT_DEFAULT;
    if (authType == null) authType = RestApiConfig.AUTH_TYPE_DEFAULT;
    if (paginationType == null) paginationType = RestApiConfig.PAGINATION_TYPE_DEFAULT;
    if (pageSize == null) pageSize = RestApiConfig.PAGE_SIZE_DEFAULT;
    if (offsetParamName == null) offsetParamName = RestApiConfig.OFFSET_PARAM_DEFAULT;
    if (limitParamName == null) limitParamName = RestApiConfig.LIMIT_PARAM_DEFAULT;
    if (pageParamName == null) pageParamName = RestApiConfig.PAGE_PARAM_DEFAULT;
    if (itemsPath == null) itemsPath = RestApiConfig.ITEMS_PATH_DEFAULT;
    if (idField == null) idField = RestApiConfig.ID_FIELD_DEFAULT;
    if (responseType == null) responseType = RestApiConfig.RESPONSE_TYPE_DEFAULT;
  }

  // ── Utility Methods ────────────────────────────────────────────────────

  private static void outputResource(String resName, IHTTPOutput out,
      Locale locale, Map<String, String> paramMap) throws ManifoldCFException {
    Messages.outputResourceWithVelocity(out, locale, resName, paramMap, true);
  }

  private String sanitizeFileName(String name) {
    if (name == null) return "document";
    return name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
  }

  private Date parseDate(String dateStr) {
    if (dateStr == null || dateStr.isEmpty()) return null;
    String[] formats = {
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd"
    };
    for (String fmt : formats) {
      try {
        SimpleDateFormat sdf = new SimpleDateFormat(fmt);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.parse(dateStr);
      } catch (ParseException e) {
        // try next
      }
    }
    return null;
  }

  private int parseIntSafe(String s, int defaultVal) {
    try { return Integer.parseInt(s); } catch (Exception e) { return defaultVal; }
  }

  private long parseLongSafe(String s, long defaultVal) {
    try { return Long.parseLong(s); } catch (Exception e) { return defaultVal; }
  }

  // ── Test Connection ────────────────────────────────────────────────────

  /**
   * Comprehensive test of the REST API connection.
   * Tests 4 areas in sequence:
   * <ol>
   *   <li><b>Seed Endpoint</b> — Can we reach the list API and extract document IDs?</li>
   *   <li><b>Document Detail</b> — Can we fetch metadata (title, dates, etc.) for a document?</li>
   *   <li><b>Content</b> — Can we retrieve document content (from content endpoint or content field)?</li>
   *   <li><b>ACL</b> — Can we retrieve access control tokens for a document?</li>
   * </ol>
   *
   * Returns "PASS|...", "WARN|...", or "FAIL|..." with detailed multi-line results.
   */
  private String testApiConnection(ConfigParams parameters) {
    // ── Extract all config params ──
    String testProtocol = parameters.getParameter(RestApiConfig.PROTOCOL_PARAM);
    String testServer = parameters.getParameter(RestApiConfig.SERVER_PARAM);
    String testPort = parameters.getParameter(RestApiConfig.PORT_PARAM);
    String testBasePath = parameters.getParameter(RestApiConfig.BASE_PATH_PARAM);
    String testSeedEndpoint = parameters.getParameter(RestApiConfig.SEED_ENDPOINT_PARAM);
    String testDocEndpoint = parameters.getParameter(RestApiConfig.DOC_ENDPOINT_PARAM);
    String testContentEndpoint = parameters.getParameter(RestApiConfig.CONTENT_ENDPOINT_PARAM);
    String testAclEndpoint = parameters.getParameter(RestApiConfig.ACL_ENDPOINT_PARAM);
    String testItemsPath = parameters.getParameter(RestApiConfig.ITEMS_PATH_PARAM);
    String testIdField = parameters.getParameter(RestApiConfig.ID_FIELD_PARAM);
    String testTitleField = parameters.getParameter(RestApiConfig.TITLE_FIELD_PARAM);
    String testContentField = parameters.getParameter(RestApiConfig.CONTENT_FIELD_PARAM);
    String testCreatedDateField = parameters.getParameter(RestApiConfig.CREATED_DATE_FIELD_PARAM);
    String testModifiedDateField = parameters.getParameter(RestApiConfig.MODIFIED_DATE_FIELD_PARAM);
    String testSizeField = parameters.getParameter(RestApiConfig.SIZE_FIELD_PARAM);
    String testMimeTypeField = parameters.getParameter(RestApiConfig.MIMETYPE_FIELD_PARAM);
    String testDownloadUrlField = parameters.getParameter(RestApiConfig.DOWNLOAD_URL_FIELD_PARAM);
    String testAclAllowField = parameters.getParameter(RestApiConfig.ACL_ALLOW_FIELD_PARAM);
    String testAclDenyField = parameters.getParameter(RestApiConfig.ACL_DENY_FIELD_PARAM);
    String testAclPrincipalField = parameters.getParameter(RestApiConfig.ACL_PRINCIPAL_FIELD_PARAM);

    // Defaults
    if (testProtocol == null) testProtocol = "https";
    if (testPort == null) testPort = "443";
    if (testBasePath == null) testBasePath = "";
    if (testItemsPath == null) testItemsPath = RestApiConfig.ITEMS_PATH_DEFAULT;
    if (testIdField == null) testIdField = RestApiConfig.ID_FIELD_DEFAULT;
    if (testTitleField == null) testTitleField = RestApiConfig.TITLE_FIELD_DEFAULT;

    // Validate required fields
    if (testServer == null || testServer.trim().isEmpty()) {
      return "FAIL|Server hostname is not configured.";
    }
    if (testSeedEndpoint == null || testSeedEndpoint.trim().isEmpty()) {
      return "FAIL|Seed endpoint is not configured.";
    }

    String baseUrl = testProtocol + "://" + testServer + ":" + testPort + testBasePath;

    // Set up SSL
    if ("https".equalsIgnoreCase(testProtocol)) {
      try { setupSslTrust(); } catch (Exception e) { /* continue */ }
    }

    // Save & set auth context for test
    String savedAuthType = authType;
    String savedUsername = username;
    String savedPassword = password;
    String savedApiKey = apiKey;
    String savedApiKeyHeader = apiKeyHeader;
    String savedCustomHeaders = customHeaders;
    String savedItemsPath = itemsPath;
    String savedIdField = idField;
    String savedTitleField = titleField;
    String savedContentField = contentField;
    String savedCreatedDateField = createdDateField;
    String savedModifiedDateField = modifiedDateField;
    String savedSizeField = sizeField;
    String savedMimeTypeField = mimeTypeField;
    String savedDownloadUrlField = downloadUrlField;
    String savedAclAllowField = aclAllowField;
    String savedAclDenyField = aclDenyField;
    String savedAclPrincipalField = aclPrincipalField;

    try {
      authType = parameters.getParameter(RestApiConfig.AUTH_TYPE_PARAM);
      username = parameters.getParameter(RestApiConfig.USERNAME_PARAM);
      password = parameters.getParameter(RestApiConfig.PASSWORD_PARAM);
      apiKey = parameters.getParameter(RestApiConfig.API_KEY_PARAM);
      apiKeyHeader = parameters.getParameter(RestApiConfig.API_KEY_HEADER_PARAM);
      customHeaders = parameters.getParameter(RestApiConfig.CUSTOM_HEADERS_PARAM);
      itemsPath = testItemsPath;
      idField = testIdField;
      titleField = testTitleField;
      contentField = testContentField;
      createdDateField = testCreatedDateField;
      modifiedDateField = testModifiedDateField;
      sizeField = testSizeField;
      mimeTypeField = testMimeTypeField;
      downloadUrlField = testDownloadUrlField;
      aclAllowField = testAclAllowField;
      aclDenyField = testAclDenyField;
      aclPrincipalField = testAclPrincipalField;

      return runConnectionTests(baseUrl, testSeedEndpoint, testDocEndpoint,
          testContentEndpoint, testAclEndpoint);
    } finally {
      // Restore all fields
      authType = savedAuthType;
      username = savedUsername;
      password = savedPassword;
      apiKey = savedApiKey;
      apiKeyHeader = savedApiKeyHeader;
      customHeaders = savedCustomHeaders;
      itemsPath = savedItemsPath;
      idField = savedIdField;
      titleField = savedTitleField;
      contentField = savedContentField;
      createdDateField = savedCreatedDateField;
      modifiedDateField = savedModifiedDateField;
      sizeField = savedSizeField;
      mimeTypeField = savedMimeTypeField;
      downloadUrlField = savedDownloadUrlField;
      aclAllowField = savedAclAllowField;
      aclDenyField = savedAclDenyField;
      aclPrincipalField = savedAclPrincipalField;
    }
  }

  /**
   * Execute the 4-step connection test and return PASS/WARN/FAIL result.
   */
  private String runConnectionTests(String baseUrl, String testSeedEndpoint,
      String testDocEndpoint, String testContentEndpoint, String testAclEndpoint) {

    StringBuilder result = new StringBuilder();
    boolean hasWarnings = false;
    boolean hasFails = false;

    // ═══════════════════════ STEP 1: Seed Endpoint ═══════════════════════
    String seedUrl = baseUrl + testSeedEndpoint;
    String seedResponse;
    try {
      seedResponse = httpGet(seedUrl);
    } catch (Exception e) {
      return "FAIL|Step 1 — Seed Endpoint: FAILED\n"
          + "URL: " + seedUrl + "\n"
          + "Error: " + e.getMessage() + "\n\n"
          + "Please check server address, credentials, and endpoint path.";
    }

    if (seedResponse == null || seedResponse.trim().isEmpty()) {
      return "FAIL|Step 1 — Seed Endpoint: FAILED\n"
          + "URL: " + seedUrl + "\n"
          + "Error: API returned empty response (HTTP 404).\n\n"
          + "Please verify the seed endpoint URL is correct.";
    }

    List<String> ids = extractIds(seedResponse);
    result.append("Step 1 — Seed Endpoint: ");
    if (ids.isEmpty()) {
      result.append("WARNING — API responded but no document IDs could be extracted.\n");
      result.append("  URL: ").append(seedUrl).append("\n");
      result.append("  Items Path: ").append(itemsPath).append("  |  ID Field: ").append(idField).append("\n");
      result.append("  Response preview: ").append(truncateStr(seedResponse, 300)).append("\n");
      result.append("  Check the Items Path and ID Field mapping.\n");
      hasWarnings = true;
    } else {
      result.append("OK — Found ").append(ids.size()).append(" document(s)\n");
      result.append("  URL: ").append(seedUrl).append("\n");
      if (ids.size() <= 5) {
        result.append("  IDs: ").append(ids).append("\n");
      } else {
        result.append("  First 5 IDs: ").append(ids.subList(0, 5)).append("\n");
      }
    }

    // ═══════════════════════ STEP 2: Document Detail ═══════════════════════
    result.append("\n");
    if (ids.isEmpty()) {
      result.append("Step 2 — Document Detail: SKIPPED (no document IDs from Step 1)\n");
      hasWarnings = true;
    } else if (testDocEndpoint == null || testDocEndpoint.trim().isEmpty()) {
      result.append("Step 2 — Document Detail: SKIPPED (no Document Endpoint configured)\n");
      hasWarnings = true;
    } else {
      String testDocId = ids.get(0);
      String docUrl = baseUrl + replaceId(testDocEndpoint, testDocId);
      String docResponse;
      try {
        docResponse = httpGet(docUrl);
      } catch (Exception e) {
        result.append("Step 2 — Document Detail: FAILED\n");
        result.append("  URL: ").append(docUrl).append("\n");
        result.append("  Error: ").append(e.getMessage()).append("\n");
        hasFails = true;
        docResponse = null;
      }

      if (docResponse != null && !docResponse.trim().isEmpty()) {
        // Try to extract metadata fields
        String testTitle = extractJsonValue(docResponse, titleField);
        String testCreated = extractJsonValue(docResponse, createdDateField);
        String testModified = extractJsonValue(docResponse, modifiedDateField);
        String testSize = extractJsonValue(docResponse, sizeField);
        String testMime = extractJsonValue(docResponse, mimeTypeField);

        int fieldsFound = 0;
        StringBuilder metaDetails = new StringBuilder();
        if (testTitle != null && !testTitle.isEmpty()) {
          fieldsFound++;
          metaDetails.append("    title: ").append(truncateStr(testTitle, 80)).append("\n");
        }
        if (testCreated != null && !testCreated.isEmpty()) {
          fieldsFound++;
          metaDetails.append("    created: ").append(testCreated).append("\n");
        }
        if (testModified != null && !testModified.isEmpty()) {
          fieldsFound++;
          metaDetails.append("    modified: ").append(testModified).append("\n");
        }
        if (testMime != null && !testMime.isEmpty()) {
          fieldsFound++;
          metaDetails.append("    mimeType: ").append(testMime).append("\n");
        }
        if (testSize != null && !testSize.isEmpty()) {
          fieldsFound++;
          metaDetails.append("    size: ").append(testSize).append("\n");
        }

        result.append("Step 2 — Document Detail: ");
        if (fieldsFound > 0) {
          result.append("OK — ").append(fieldsFound).append(" metadata field(s) extracted\n");
          result.append("  URL: ").append(docUrl).append("\n");
          result.append("  Document ID: ").append(testDocId).append("\n");
          result.append(metaDetails);
        } else {
          result.append("WARNING — API responded but no metadata fields could be extracted\n");
          result.append("  URL: ").append(docUrl).append("\n");
          result.append("  Response preview: ").append(truncateStr(docResponse, 300)).append("\n");
          result.append("  Check the Title/Date/Size field mappings.\n");
          hasWarnings = true;
        }

        // ═════════════ STEP 3: Content ═════════════
        result.append("\n");
        if (testContentEndpoint != null && !testContentEndpoint.trim().isEmpty()) {
          // Binary content endpoint configured
          String contentUrl;
          String testDownloadUrl = extractJsonValue(docResponse, downloadUrlField);
          if (testDownloadUrl != null && !testDownloadUrl.isEmpty()) {
            contentUrl = testDownloadUrl.startsWith("http") ? testDownloadUrl :
                protocol + "://" + server + ":" + port + testDownloadUrl;
          } else {
            contentUrl = baseUrl + replaceId(testContentEndpoint, testDocId);
          }
          try {
            InputStream is = httpGetStream(contentUrl);
            byte[] buf = new byte[1024];
            int totalRead = 0;
            int bytesRead;
            while ((bytesRead = is.read(buf)) != -1) {
              totalRead += bytesRead;
              if (totalRead > 4096) break; // Read up to 4KB for test
            }
            is.close();

            result.append("Step 3 — Content: OK — Retrieved ").append(totalRead).append("+ bytes\n");
            result.append("  URL: ").append(contentUrl).append("\n");
          } catch (Exception e) {
            result.append("Step 3 — Content: WARNING — Could not fetch content\n");
            result.append("  URL: ").append(contentUrl).append("\n");
            result.append("  Error: ").append(e.getMessage()).append("\n");
            hasWarnings = true;
          }
        } else if (contentField != null && !contentField.isEmpty()) {
          // Content from JSON field
          String testContent = extractJsonValue(docResponse, contentField);
          if (testContent != null && !testContent.isEmpty()) {
            result.append("Step 3 — Content: OK — Extracted from field '")
                .append(contentField).append("' (").append(testContent.length()).append(" chars)\n");
          } else {
            result.append("Step 3 — Content: WARNING — Content field '")
                .append(contentField).append("' returned empty\n");
            result.append("  Will fall back to full JSON response as content during crawl.\n");
            hasWarnings = true;
          }
        } else {
          result.append("Step 3 — Content: OK — No content endpoint/field configured.\n");
          result.append("  Will use full JSON response as content during crawl.\n");
        }

        // ═════════════ STEP 4: ACL ═════════════
        result.append("\n");
        if (testAclEndpoint != null && !testAclEndpoint.trim().isEmpty()) {
          String aclUrl = baseUrl + replaceId(testAclEndpoint, testDocId);
          try {
            String aclResponse = httpGet(aclUrl);
            if (aclResponse != null && !aclResponse.trim().isEmpty()) {
              List<String> allowTokens = new ArrayList<>();
              List<String> denyTokens = new ArrayList<>();

              if (aclAllowField != null && !aclAllowField.isEmpty()) {
                allowTokens = extractJsonArray(aclResponse, aclAllowField, aclPrincipalField);
              }
              if (aclDenyField != null && !aclDenyField.isEmpty()) {
                denyTokens = extractJsonArray(aclResponse, aclDenyField, aclPrincipalField);
              }

              if (!allowTokens.isEmpty() || !denyTokens.isEmpty()) {
                result.append("Step 4 — ACL: OK\n");
                result.append("  URL: ").append(aclUrl).append("\n");
                if (!allowTokens.isEmpty()) {
                  result.append("  Allow tokens (").append(allowTokens.size()).append("): ");
                  if (allowTokens.size() <= 5) {
                    result.append(allowTokens);
                  } else {
                    result.append(allowTokens.subList(0, 5)).append(" ...");
                  }
                  result.append("\n");
                }
                if (!denyTokens.isEmpty()) {
                  result.append("  Deny tokens (").append(denyTokens.size()).append("): ");
                  if (denyTokens.size() <= 5) {
                    result.append(denyTokens);
                  } else {
                    result.append(denyTokens.subList(0, 5)).append(" ...");
                  }
                  result.append("\n");
                }
              } else {
                result.append("Step 4 — ACL: WARNING — API responded but no ACL tokens could be extracted\n");
                result.append("  URL: ").append(aclUrl).append("\n");
                result.append("  Response preview: ").append(truncateStr(aclResponse, 300)).append("\n");
                result.append("  Check Allow Field, Deny Field, and Principal Field mappings.\n");
                hasWarnings = true;
              }
            } else {
              result.append("Step 4 — ACL: WARNING — ACL endpoint returned empty response\n");
              result.append("  URL: ").append(aclUrl).append("\n");
              hasWarnings = true;
            }
          } catch (Exception e) {
            result.append("Step 4 — ACL: WARNING — Could not reach ACL endpoint\n");
            result.append("  URL: ").append(aclUrl).append("\n");
            result.append("  Error: ").append(e.getMessage()).append("\n");
            result.append("  Documents will be indexed without ACL (public access).\n");
            hasWarnings = true;
          }
        } else {
          result.append("Step 4 — ACL: SKIPPED — No ACL endpoint configured.\n");
          result.append("  Documents will be indexed without access control (__nosecurity__).\n");
          hasWarnings = true;
        }

      } else if (docResponse == null) {
        // Already handled above in the FAILED branch
        result.append("\nStep 3 — Content: SKIPPED (Document Detail failed)\n");
        result.append("\nStep 4 — ACL: SKIPPED (Document Detail failed)\n");
      }
    }

    // ═══════════════════════ Summary ═══════════════════════
    result.append("\n");
    if (hasFails) {
      result.insert(0, "");
      return "FAIL|" + result;
    } else if (hasWarnings) {
      result.append("Some checks had warnings. You can still save this connection.\n");
      result.append("Review the warnings above and adjust field mappings if needed.");
      return "WARN|" + result;
    } else {
      result.append("All checks passed. Connection is ready to use.");
      return "PASS|" + result;
    }
  }

  /** Truncate a string for display in test results */
  private static String truncateStr(String s, int maxLen) {
    if (s == null) return "";
    if (s.length() <= maxLen) return s;
    return s.substring(0, maxLen) + "...";
  }

  // ── SSL ────────────────────────────────────────────────────────────────

  private static boolean sslInitialized = false;

  private static synchronized void setupSslTrust() throws Exception {
    if (sslInitialized) return;
    TrustManager[] trustAll = new TrustManager[]{
        new X509TrustManager() {
          public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
          public void checkClientTrusted(X509Certificate[] certs, String t) {}
          public void checkServerTrusted(X509Certificate[] certs, String t) {}
        }
    };
    SSLContext sc = SSLContext.getInstance("TLS");
    sc.init(null, trustAll, new SecureRandom());
    HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
    HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
    sslInitialized = true;
  }
}
