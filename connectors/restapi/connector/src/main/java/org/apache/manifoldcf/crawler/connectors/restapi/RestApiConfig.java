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

/**
 * Configuration parameters and vendor presets for the REST API Repository Connector.
 *
 * <p>Supports pre-configured vendor templates for popular REST API providers.
 * When a user selects a vendor, the API endpoint paths, authentication type,
 * pagination strategy, and field mappings are automatically populated.
 * Users can override any pre-filled value or select "Other" for custom REST APIs.</p>
 */
public class RestApiConfig {

  // =========================================================================
  //  Configuration Parameter Names (stored in ConfigParams)
  // =========================================================================

  /** REST API vendor preset */
  public static final String VENDOR_PARAM = "VENDOR";

  /** Authentication type: basic, bearer, apikey, oauth2, none */
  public static final String AUTH_TYPE_PARAM = "AUTHTYPE";

  /** Username for Basic auth */
  public static final String USERNAME_PARAM = "USERNAME";

  /** Password for Basic auth */
  public static final String PASSWORD_PARAM = "PASSWORD";

  /** Bearer token / API key value */
  public static final String API_KEY_PARAM = "APIKEY";

  /** API key header name (e.g., Authorization, X-API-Key) */
  public static final String API_KEY_HEADER_PARAM = "APIKEYHEADER";

  /** Protocol: http or https */
  public static final String PROTOCOL_PARAM = "PROTOCOL";

  /** Server hostname */
  public static final String SERVER_PARAM = "SERVER";

  /** Server port */
  public static final String PORT_PARAM = "PORT";

  /** Base path prefix for all API calls (e.g., /api/v1) */
  public static final String BASE_PATH_PARAM = "BASEPATH";

  /** Seed/listing endpoint path — returns list of document IDs/URLs */
  public static final String SEED_ENDPOINT_PARAM = "SEEDENDPOINT";

  /** Document detail endpoint — fetches single doc. Use {id} placeholder. */
  public static final String DOC_ENDPOINT_PARAM = "DOCENDPOINT";

  /** Content/download endpoint — fetches binary content. Use {id} placeholder. */
  public static final String CONTENT_ENDPOINT_PARAM = "CONTENTENDPOINT";

  /** ACL endpoint — fetches permissions for a document. Use {id} placeholder. */
  public static final String ACL_ENDPOINT_PARAM = "ACLENDPOINT";

  // ---- Pagination ----

  /** Pagination type: offset, page, cursor, link, none */
  public static final String PAGINATION_TYPE_PARAM = "PAGINATIONTYPE";

  /** Page size / limit per request */
  public static final String PAGE_SIZE_PARAM = "PAGESIZE";

  /** Query parameter name for offset (default: offset) */
  public static final String OFFSET_PARAM_NAME = "OFFSETPARAM";

  /** Query parameter name for limit (default: limit) */
  public static final String LIMIT_PARAM_NAME = "LIMITPARAM";

  /** Query parameter name for page number (default: page) */
  public static final String PAGE_PARAM_NAME = "PAGEPARAM";

  /** JSON path to the cursor/next token in response (e.g., $.nextPageToken) */
  public static final String CURSOR_FIELD_PARAM = "CURSORFIELD";

  /** Query parameter name to send cursor value (e.g., pageToken) */
  public static final String CURSOR_PARAM_NAME = "CURSORPARAM";

  // ---- Response Mapping (JSONPath expressions) ----

  /** JSONPath to the array of items in the seed response (e.g., $.data, $.results, $.value) */
  public static final String ITEMS_PATH_PARAM = "ITEMSPATH";

  /** JSONPath to the document ID within each item (e.g., $.id, $.sys.id) */
  public static final String ID_FIELD_PARAM = "IDFIELD";

  /** JSONPath to the document title/name */
  public static final String TITLE_FIELD_PARAM = "TITLEFIELD";

  /** JSONPath to the document content/body */
  public static final String CONTENT_FIELD_PARAM = "CONTENTFIELD";

  /** JSONPath to mime type in the response */
  public static final String MIMETYPE_FIELD_PARAM = "MIMETYPEFIELD";

  /** JSONPath to created date */
  public static final String CREATED_DATE_FIELD_PARAM = "CREATEDDATEFIELD";

  /** JSONPath to modified date */
  public static final String MODIFIED_DATE_FIELD_PARAM = "MODIFIEDDATEFIELD";

  /** JSONPath to file size (bytes) */
  public static final String SIZE_FIELD_PARAM = "SIZEFIELD";

  /** JSONPath to download URL within a document item */
  public static final String DOWNLOAD_URL_FIELD_PARAM = "DOWNLOADURLFIELD";

  // ---- ACL Mapping ----

  /** JSONPath to allow principals array in ACL response */
  public static final String ACL_ALLOW_FIELD_PARAM = "ACLALLOWFIELD";

  /** JSONPath to deny principals array in ACL response */
  public static final String ACL_DENY_FIELD_PARAM = "ACLDENYFIELD";

  /** JSONPath to principal ID within each ACL entry */
  public static final String ACL_PRINCIPAL_FIELD_PARAM = "ACLPRINCIPALFIELD";

  // ---- Misc ----

  /** Max file size in bytes (0 = no limit) */
  public static final String MAX_FILE_SIZE_PARAM = "MAXFILESIZE";

  /** Custom HTTP headers (key1=value1\nkey2=value2) */
  public static final String CUSTOM_HEADERS_PARAM = "CUSTOMHEADERS";

  /** Response content type: json, xml */
  public static final String RESPONSE_TYPE_PARAM = "RESPONSETYPE";

  // ---- Job Specification ----

  /** Custom query/filter for the seed endpoint */
  public static final String QUERY_PARAM = "RESTAPIQUERY";

  // =========================================================================
  //  Default Values
  // =========================================================================

  public static final String PROTOCOL_DEFAULT = "https";
  public static final String PORT_DEFAULT = "443";
  public static final String AUTH_TYPE_DEFAULT = "basic";
  public static final String PAGINATION_TYPE_DEFAULT = "offset";
  public static final String PAGE_SIZE_DEFAULT = "100";
  public static final String OFFSET_PARAM_DEFAULT = "offset";
  public static final String LIMIT_PARAM_DEFAULT = "limit";
  public static final String PAGE_PARAM_DEFAULT = "page";
  public static final String ITEMS_PATH_DEFAULT = "$.results";
  public static final String ID_FIELD_DEFAULT = "$.id";
  public static final String TITLE_FIELD_DEFAULT = "$.name";
  public static final String CONTENT_FIELD_DEFAULT = "$.content";
  public static final String MIMETYPE_FIELD_DEFAULT = "$.mimeType";
  public static final String CREATED_DATE_FIELD_DEFAULT = "$.createdDate";
  public static final String MODIFIED_DATE_FIELD_DEFAULT = "$.modifiedDate";
  public static final String SIZE_FIELD_DEFAULT = "$.size";
  public static final String RESPONSE_TYPE_DEFAULT = "json";
  public static final String VENDOR_DEFAULT = "other";
  public static final String API_KEY_HEADER_DEFAULT = "Authorization";

  // =========================================================================
  //  Vendor Presets — each vendor has a static inner class with defaults
  // =========================================================================

  /**
   * Confluence Cloud REST API v2.
   * Docs: https://developer.atlassian.com/cloud/confluence/rest/v2/
   */
  public static class ConfluencePreset {
    public static final String VENDOR_ID = "confluence";
    public static final String LABEL = "Confluence (Atlassian)";
    public static final String AUTH_TYPE = "basic";
    public static final String PORT = "443";
    public static final String BASE_PATH = "/wiki/api/v2";
    public static final String SEED_ENDPOINT = "/pages";
    public static final String DOC_ENDPOINT = "/pages/{id}?body-format=storage";
    public static final String CONTENT_ENDPOINT = "";
    public static final String ACL_ENDPOINT = "/pages/{id}/operations";
    public static final String PAGINATION_TYPE = "cursor";
    public static final String PAGE_SIZE = "100";
    public static final String CURSOR_FIELD = "$._links.next";
    public static final String CURSOR_PARAM = "cursor";
    public static final String ITEMS_PATH = "$.results";
    public static final String ID_FIELD = "$.id";
    public static final String TITLE_FIELD = "$.title";
    public static final String CONTENT_FIELD = "$.body.storage.value";
    public static final String MIMETYPE_FIELD = "";
    public static final String CREATED_DATE_FIELD = "$.createdAt";
    public static final String MODIFIED_DATE_FIELD = "$.version.createdAt";
    public static final String SIZE_FIELD = "";
    public static final String ACL_ALLOW_FIELD = "$.results";
    public static final String ACL_PRINCIPAL_FIELD = "$.principal.id";
  }

  /**
   * WordPress REST API v2.
   * Docs: https://developer.wordpress.org/rest-api/reference/
   */
  public static class WordPressPreset {
    public static final String VENDOR_ID = "wordpress";
    public static final String LABEL = "WordPress";
    public static final String AUTH_TYPE = "basic";
    public static final String PORT = "443";
    public static final String BASE_PATH = "/wp-json/wp/v2";
    public static final String SEED_ENDPOINT = "/posts";
    public static final String DOC_ENDPOINT = "/posts/{id}";
    public static final String CONTENT_ENDPOINT = "";
    public static final String ACL_ENDPOINT = "";
    public static final String PAGINATION_TYPE = "page";
    public static final String PAGE_SIZE = "100";
    public static final String CURSOR_FIELD = "";
    public static final String CURSOR_PARAM = "";
    public static final String ITEMS_PATH = "$";
    public static final String ID_FIELD = "$.id";
    public static final String TITLE_FIELD = "$.title.rendered";
    public static final String CONTENT_FIELD = "$.content.rendered";
    public static final String MIMETYPE_FIELD = "";
    public static final String CREATED_DATE_FIELD = "$.date_gmt";
    public static final String MODIFIED_DATE_FIELD = "$.modified_gmt";
    public static final String SIZE_FIELD = "";
    public static final String ACL_ALLOW_FIELD = "";
    public static final String ACL_PRINCIPAL_FIELD = "";
  }

  /**
   * Jira Cloud REST API v3.
   * Docs: https://developer.atlassian.com/cloud/jira/platform/rest/v3/
   */
  public static class JiraPreset {
    public static final String VENDOR_ID = "jira";
    public static final String LABEL = "Jira (Atlassian)";
    public static final String AUTH_TYPE = "basic";
    public static final String PORT = "443";
    public static final String BASE_PATH = "/rest/api/3";
    public static final String SEED_ENDPOINT = "/search";
    public static final String DOC_ENDPOINT = "/issue/{id}";
    public static final String CONTENT_ENDPOINT = "";
    public static final String ACL_ENDPOINT = "";
    public static final String PAGINATION_TYPE = "offset";
    public static final String PAGE_SIZE = "100";
    public static final String CURSOR_FIELD = "";
    public static final String CURSOR_PARAM = "";
    public static final String ITEMS_PATH = "$.issues";
    public static final String ID_FIELD = "$.id";
    public static final String TITLE_FIELD = "$.fields.summary";
    public static final String CONTENT_FIELD = "$.fields.description";
    public static final String MIMETYPE_FIELD = "";
    public static final String CREATED_DATE_FIELD = "$.fields.created";
    public static final String MODIFIED_DATE_FIELD = "$.fields.updated";
    public static final String SIZE_FIELD = "";
    public static final String ACL_ALLOW_FIELD = "";
    public static final String ACL_PRINCIPAL_FIELD = "";
  }

  /**
   * GitHub REST API v3.
   * Docs: https://docs.github.com/en/rest
   */
  public static class GitHubPreset {
    public static final String VENDOR_ID = "github";
    public static final String LABEL = "GitHub";
    public static final String AUTH_TYPE = "bearer";
    public static final String PORT = "443";
    public static final String BASE_PATH = "";
    public static final String SEED_ENDPOINT = "/repos/{owner}/{repo}/contents";
    public static final String DOC_ENDPOINT = "/repos/{owner}/{repo}/contents/{path}";
    public static final String CONTENT_ENDPOINT = "";
    public static final String ACL_ENDPOINT = "/repos/{owner}/{repo}/collaborators";
    public static final String PAGINATION_TYPE = "page";
    public static final String PAGE_SIZE = "100";
    public static final String CURSOR_FIELD = "";
    public static final String CURSOR_PARAM = "";
    public static final String ITEMS_PATH = "$";
    public static final String ID_FIELD = "$.sha";
    public static final String TITLE_FIELD = "$.name";
    public static final String CONTENT_FIELD = "$.content";
    public static final String MIMETYPE_FIELD = "$.type";
    public static final String CREATED_DATE_FIELD = "";
    public static final String MODIFIED_DATE_FIELD = "";
    public static final String SIZE_FIELD = "$.size";
    public static final String ACL_ALLOW_FIELD = "$";
    public static final String ACL_PRINCIPAL_FIELD = "$.login";
  }

  /**
   * SharePoint Online REST API.
   * Docs: https://learn.microsoft.com/en-us/sharepoint/dev/sp-add-ins/get-to-know-the-sharepoint-rest-service
   */
  public static class SharePointPreset {
    public static final String VENDOR_ID = "sharepoint";
    public static final String LABEL = "SharePoint Online";
    public static final String AUTH_TYPE = "bearer";
    public static final String PORT = "443";
    public static final String BASE_PATH = "/_api";
    public static final String SEED_ENDPOINT = "/web/lists/getbytitle('Documents')/items";
    public static final String DOC_ENDPOINT = "/web/lists/getbytitle('Documents')/items({id})";
    public static final String CONTENT_ENDPOINT = "/web/GetFileByServerRelativeUrl('{path}')/$value";
    public static final String ACL_ENDPOINT = "/web/lists/getbytitle('Documents')/items({id})/roleassignments";
    public static final String PAGINATION_TYPE = "link";
    public static final String PAGE_SIZE = "5000";
    public static final String CURSOR_FIELD = "$.d.__next";
    public static final String CURSOR_PARAM = "";
    public static final String ITEMS_PATH = "$.d.results";
    public static final String ID_FIELD = "$.Id";
    public static final String TITLE_FIELD = "$.Title";
    public static final String CONTENT_FIELD = "";
    public static final String MIMETYPE_FIELD = "";
    public static final String CREATED_DATE_FIELD = "$.Created";
    public static final String MODIFIED_DATE_FIELD = "$.Modified";
    public static final String SIZE_FIELD = "$.File.Length";
    public static final String ACL_ALLOW_FIELD = "$.d.results";
    public static final String ACL_PRINCIPAL_FIELD = "$.Member.LoginName";
  }

  /**
   * Notion API.
   * Docs: https://developers.notion.com/reference
   */
  public static class NotionPreset {
    public static final String VENDOR_ID = "notion";
    public static final String LABEL = "Notion";
    public static final String AUTH_TYPE = "bearer";
    public static final String PORT = "443";
    public static final String BASE_PATH = "/v1";
    public static final String SEED_ENDPOINT = "/search";
    public static final String DOC_ENDPOINT = "/pages/{id}";
    public static final String CONTENT_ENDPOINT = "/blocks/{id}/children";
    public static final String ACL_ENDPOINT = "";
    public static final String PAGINATION_TYPE = "cursor";
    public static final String PAGE_SIZE = "100";
    public static final String CURSOR_FIELD = "$.next_cursor";
    public static final String CURSOR_PARAM = "start_cursor";
    public static final String ITEMS_PATH = "$.results";
    public static final String ID_FIELD = "$.id";
    public static final String TITLE_FIELD = "$.properties.title.title[0].plain_text";
    public static final String CONTENT_FIELD = "";
    public static final String MIMETYPE_FIELD = "";
    public static final String CREATED_DATE_FIELD = "$.created_time";
    public static final String MODIFIED_DATE_FIELD = "$.last_edited_time";
    public static final String SIZE_FIELD = "";
    public static final String ACL_ALLOW_FIELD = "";
    public static final String ACL_PRINCIPAL_FIELD = "";
  }

  /**
   * Drupal JSON:API.
   * Docs: https://www.drupal.org/docs/core-modules-and-themes/core-modules/jsonapi-module
   */
  public static class DrupalPreset {
    public static final String VENDOR_ID = "drupal";
    public static final String LABEL = "Drupal";
    public static final String AUTH_TYPE = "basic";
    public static final String PORT = "443";
    public static final String BASE_PATH = "/jsonapi";
    public static final String SEED_ENDPOINT = "/node/article";
    public static final String DOC_ENDPOINT = "/node/article/{id}";
    public static final String CONTENT_ENDPOINT = "";
    public static final String ACL_ENDPOINT = "";
    public static final String PAGINATION_TYPE = "cursor";
    public static final String PAGE_SIZE = "50";
    public static final String CURSOR_FIELD = "$.links.next.href";
    public static final String CURSOR_PARAM = "";
    public static final String ITEMS_PATH = "$.data";
    public static final String ID_FIELD = "$.id";
    public static final String TITLE_FIELD = "$.attributes.title";
    public static final String CONTENT_FIELD = "$.attributes.body.value";
    public static final String MIMETYPE_FIELD = "";
    public static final String CREATED_DATE_FIELD = "$.attributes.created";
    public static final String MODIFIED_DATE_FIELD = "$.attributes.changed";
    public static final String SIZE_FIELD = "";
    public static final String ACL_ALLOW_FIELD = "";
    public static final String ACL_PRINCIPAL_FIELD = "";
  }

  /**
   * Alfresco REST API.
   * Docs: https://docs.alfresco.com/content-services/latest/develop/rest-api-guide/
   */
  public static class AlfrescoRestPreset {
    public static final String VENDOR_ID = "alfresco";
    public static final String LABEL = "Alfresco (REST API)";
    public static final String AUTH_TYPE = "basic";
    public static final String PORT = "443";
    public static final String BASE_PATH = "/alfresco/api/-default-/public/alfresco/versions/1";
    public static final String SEED_ENDPOINT = "/queries/nodes?term=*";
    public static final String DOC_ENDPOINT = "/nodes/{id}";
    public static final String CONTENT_ENDPOINT = "/nodes/{id}/content";
    public static final String ACL_ENDPOINT = "/nodes/{id}/permissions";
    public static final String PAGINATION_TYPE = "offset";
    public static final String PAGE_SIZE = "100";
    public static final String CURSOR_FIELD = "";
    public static final String CURSOR_PARAM = "";
    public static final String ITEMS_PATH = "$.list.entries";
    public static final String ID_FIELD = "$.entry.id";
    public static final String TITLE_FIELD = "$.entry.name";
    public static final String CONTENT_FIELD = "";
    public static final String MIMETYPE_FIELD = "$.entry.content.mimeType";
    public static final String CREATED_DATE_FIELD = "$.entry.createdAt";
    public static final String MODIFIED_DATE_FIELD = "$.entry.modifiedAt";
    public static final String SIZE_FIELD = "$.entry.content.sizeInBytes";
    public static final String ACL_ALLOW_FIELD = "$.list.entries";
    public static final String ACL_PRINCIPAL_FIELD = "$.entry.authorityId";
  }

  // =========================================================================
  //  Vendor IDs (for dropdown)
  // =========================================================================

  public static final String[] VENDOR_IDS = {
      ConfluencePreset.VENDOR_ID,
      JiraPreset.VENDOR_ID,
      WordPressPreset.VENDOR_ID,
      GitHubPreset.VENDOR_ID,
      SharePointPreset.VENDOR_ID,
      NotionPreset.VENDOR_ID,
      DrupalPreset.VENDOR_ID,
      AlfrescoRestPreset.VENDOR_ID,
      "other"
  };

  public static final String[] VENDOR_LABELS = {
      ConfluencePreset.LABEL,
      JiraPreset.LABEL,
      WordPressPreset.LABEL,
      GitHubPreset.LABEL,
      SharePointPreset.LABEL,
      NotionPreset.LABEL,
      DrupalPreset.LABEL,
      AlfrescoRestPreset.LABEL,
      "Other (Custom REST API)"
  };
}
