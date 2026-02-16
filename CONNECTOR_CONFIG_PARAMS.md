# ManifoldCF Connector Configuration Parameters — Exact Internal Names

This document lists the **exact `_PARAMETER_` name attribute values** (the string keys used in `ConfigParams.getParameter()` / `ConfigParams.setParameter()`) for each ManifoldCF repository connector.

> **IMPORTANT**: ManifoldCF's `ConfigParams` is **case-sensitive**. Use the exact casing shown below.

---

## 1. CMIS Repository Connector

**Config class:** `CmisConfig` (`connectors/cmis/connector/src/main/java/.../cmis/CmisConfig.java`)

| Java Constant | Exact Parameter String | Required | Type / Allowed Values | Default |
|---|---|---|---|---|
| `USERNAME_PARAM` | `username` | ✅ Yes | String | `dummyuser` |
| `PASSWORD_PARAM` | `password` | ✅ Yes | String (obfuscated) | `dummysecrect` |
| `PROTOCOL_PARAM` | `protocol` | ✅ Yes | `http`, `https` | `http` |
| `SERVER_PARAM` | `server` | ✅ Yes | String (hostname) | `localhost` |
| `PORT_PARAM` | `port` | ✅ Yes | String (numeric) | `9090` |
| `PATH_PARAM` | `path` | ✅ Yes | String (URL path) | `/chemistry-opencmis-server-inmemory/atom` |
| `BINDING_PARAM` | `binding` | ✅ Yes | `atom`, `ws`, `browser` | `atom` |
| `REPOSITORY_ID_PARAM` | `repositoryId` | Optional | String | `""` (empty = auto-detect first repo) |
| `CMIS_QUERY_PARAM` | `cmisQuery` | Optional (job spec) | CMIS QL query string | — |
| `VENDOR_PARAM` | `cmisVendor` | Optional | `alfresco`, `other` | `other` |
| `GROUP_API_URL_PARAM` | `groupApiUrl` | Optional | URL path | `""` |
| `GROUP_MEMBERS_API_URL_PARAM` | `groupMembersApiUrl` | Optional | URL path template with `{groupId}` | `""` |
| `GROUP_API_TEST_RESULT_PARAM` | `groupApiTestResult` | Transient | String (not persisted meaningfully) | — |
| `SKIP_ACL_WAIT_PARAM` | `skipAclWait` | Optional | `true`, `false` | `false` |
| `MAX_FILE_SIZE_PARAM` | `maxFileSize` | Optional | String (bytes, 0 = no limit) | `0` |

---

## 2. Amazon S3 Repository Connector

**Config class:** `AmazonS3Config` (`connectors/amazons3/connector/src/main/java/.../amazons3/AmazonS3Config.java`)

| Java Constant | Exact Parameter String | Required | Type / Allowed Values | Default |
|---|---|---|---|---|
| `AWS_ACCESS_KEY` | `aws_access_key` | ✅ Yes | String | `""` |
| `AWS_SECRET_KEY` | `aws_secret_key` | ✅ Yes | String (obfuscated) | `""` |
| `AMAZONS3_HOST` | `amazons3_host` | Optional | String (hostname) | `""` |
| `AMAZONS3_PORT` | `amazons3_port` | Optional | String (numeric) | `""` |
| `AMAZONS3_PROTOCOL` | `amazons3_protocol` | Optional | `http`, `https` | `http` |
| `AMAZONS3_PROXY_HOST` | `amazons3_proxy_host` | Optional | String | `""` |
| `AMAZONS3_PROXY_PORT` | `amazons3_proxy_port` | Optional | String | `""` |
| `AMAZONS3_PROXY_DOMAIN` | `amazons3_proxy_domain` | Optional | String | `""` |
| `AMAZONS3_PROXY_USERNAME` | `amazons3_proxy_username` | Optional | String | `""` |
| `AMAZONS3_PROXY_PASSWORD` | `amazons3_proxy_password` | Optional | String (obfuscated) | `""` |

**Job Specification nodes:** `startpoint` (with `s3buckets` attribute), `access` (with `token` attribute).

---

## 3. Confluence Repository Connector (v4/v5)

**Config class:** `ConfluenceConfiguration.Server` (`connectors/confluence/connector/src/main/java/.../confluence/ConfluenceConfiguration.java`)

| Java Constant | Exact Parameter String | Required | Type / Allowed Values | Default |
|---|---|---|---|---|
| `Server.USERNAME` | `username` | Optional | String | `""` |
| `Server.PASSWORD` | `password` | Optional | String (obfuscated) | `""` |
| `Server.PROTOCOL` | `protocol` | ✅ Yes | `http`, `https` | `http` |
| `Server.HOST` | `host` | ✅ Yes | String (hostname) | `""` |
| `Server.PORT` | `port` | ✅ Yes | String (numeric) | `8090` |
| `Server.PATH` | `path` | Optional | String (URL path) | `/confluence` |

**Job Specification nodes:** `spaces` > `space` (with `key` attribute), `pages`, `pagetype`, `process_attachments`.

---

## 4. Confluence v6 Repository Connector

**Config class:** `ConfluenceConfiguration.Server` (`connectors/confluence-v6/connector/src/main/java/.../confluence/v6/ConfluenceConfiguration.java`)

| Java Constant | Exact Parameter String | Required | Type / Allowed Values | Default |
|---|---|---|---|---|
| `Server.USERNAME` | `username` | Optional | String | `""` |
| `Server.PASSWORD` | `password` | Optional | String (obfuscated) | `""` |
| `Server.PROTOCOL` | `protocol` | ✅ Yes | `http`, `https` | `http` |
| `Server.HOST` | `host` | ✅ Yes | String (hostname) | `""` |
| `Server.PORT` | `port` | ✅ Yes | String (numeric) | `8090` |
| `Server.PATH` | `path` | Optional | String (URL path) | `/confluence` |
| `Server.SOCKET_TIMEOUT` | `socket_timeout` | Optional | String (ms) | `900000` |
| `Server.CONNECTION_TIMEOUT` | `connection_timeout` | Optional | String (ms) | `60000` |
| `Server.RETRY_NUMBER` | `retryNumber` | Optional | String (numeric) | `2` |
| `Server.RETRY_INTERVAL` | `retryInterval` | Optional | String (ms) | `20000` |
| `Server.PROXY_USERNAME` | `proxy_username` | Optional | String | `""` |
| `Server.PROXY_PASSWORD` | `proxy_password` | Optional | String (obfuscated) | `""` |
| `Server.PROXY_PROTOCOL` | `proxy_protocol` | Optional | `http`, `https` | `http` |
| `Server.PROXY_HOST` | `proxy_host` | Optional | String | `""` |
| `Server.PROXY_PORT` | `proxy_port` | Optional | String | `""` |

**Authority cache params:** `cache_lifetime`, `cache_lru_size`.

---

## 5. Email Repository Connector

**Config class:** `EmailConfig` (`connectors/email/connector/src/main/java/.../email/EmailConfig.java`)

| Java Constant | Exact Parameter String | Required | Type / Allowed Values | Default |
|---|---|---|---|---|
| `USERNAME_PARAM` | `username` | Optional | String | — |
| `PASSWORD_PARAM` | `password` | Optional | String (obfuscated) | — |
| `PROTOCOL_PARAM` | `protocol` | ✅ Yes | `IMAP`, `IMAP-SSL`, `POP3`, `POP3-SSL` | `IMAP` |
| `SERVER_PARAM` | `server` | ✅ Yes | String (hostname) | — |
| `PORT_PARAM` | `port` | Optional | String (numeric) | `""` |
| `URL_PARAM` | `url` | Optional | String (URL template) | — |
| `ATTACHMENT_URL_PARAM` | `attachmenturl` | Optional | String (URL template) | — |

**Child nodes:** `properties` (with `name` and `value` attributes for JavaMail session properties), `metadata`, `extractemail`, `filter`, `folder`.

---

## 6. Email Notification Connector

**Config class:** `EmailConfig` (`connectors/email/connector/src/main/java/.../notifications/email/EmailConfig.java`)

Same parameter names as the Email Repository Connector: `username`, `password`, `protocol`, `server`, `port`, `url`.

---

## 7. Jira Repository Connector

**Config class:** `JiraConfig` (`connectors/jira/connector/src/main/java/.../jira/JiraConfig.java`)

| Java Constant | Exact Parameter String | Required | Type / Allowed Values | Default |
|---|---|---|---|---|
| `CLIENT_ID_PARAM` | `clientid` | ✅ Yes | String (username) | `""` |
| `CLIENT_SECRET_PARAM` | `clientsecret` | ✅ Yes | String (obfuscated password) | `""` |
| `JIRA_PROTOCOL_PARAM` | `jiraprotocol` | ✅ Yes | `http`, `https` | `http` |
| `JIRA_HOST_PARAM` | `jirahost` | ✅ Yes | String (hostname) | `""` |
| `JIRA_PORT_PARAM` | `jiraport` | Optional | String (numeric) | `""` |
| `JIRA_PATH_PARAM` | `jirapath` | ✅ Yes | String (REST API path) | `/rest/api/2/` |
| `JIRA_PROXYHOST_PARAM` | `jiraproxyhost` | Optional | String | `""` |
| `JIRA_PROXYPORT_PARAM` | `jiraproxyport` | Optional | String | `""` |
| `JIRA_PROXYDOMAIN_PARAM` | `jiraproxydomain` | Optional | String | `""` |
| `JIRA_PROXYUSERNAME_PARAM` | `jiraproxyusername` | Optional | String | `""` |
| `JIRA_PROXYPASSWORD_PARAM` | `jiraproxypassword` | Optional | String (obfuscated) | `""` |
| `JIRA_QUERY_PARAM` | `jiraquery` | Optional (job spec) | JQL query string | `ORDER BY createdDate Asc` |

---

## 8. JDBC Repository Connector

**Config class:** `JDBCConstants` (`connectors/jdbc/connector/src/main/java/.../jdbc/JDBCConstants.java`)

| Java Constant | Exact Parameter String | Required | Type / Allowed Values | Default |
|---|---|---|---|---|
| `providerParameter` | `JDBC Provider` | ✅ Yes | `oracle:thin:@`, `postgresql:`, `mysql:`, `jtds:sqlserver:`, `mssql:` | — |
| `methodParameter` | `JDBC column access method` | ✅ Yes | `name`, `label` | — |
| `hostParameter` | `Host` | ✅ Yes* | String (hostname:port or connection string) | — |
| `databaseNameParameter` | `Database name` | ✅ Yes* | String | — |
| `driverStringParameter` | `Raw driver string` | Optional* | Full JDBC URL (overrides host+db) | — |
| `databaseUserName` | `User name` | ✅ Yes | String | — |
| `databasePassword` | `Password` | ✅ Yes | String (obfuscated) | — |

\* Either (`Host` + `Database name`) OR `Raw driver string` must be provided.

**Job Specification nodes:** `idquery`, `versionquery`, `dataquery`, `aclquery`, `attrquery` (with `attributename`).

---

## 9. Dropbox Repository Connector

**Config class:** `DropboxConfig` (`connectors/dropbox/connector/src/main/java/.../dropbox/DropboxConfig.java`)

| Java Constant | Exact Parameter String | Required | Type / Allowed Values | Default |
|---|---|---|---|---|
| `APP_KEY_PARAM` | `app_key` | ✅ Yes | String (Dropbox app key) | — |
| `APP_SECRET_PARAM` | `app_secret` | ✅ Yes | String (Dropbox app secret) | — |
| `KEY_PARAM` | `key` | ✅ Yes | String (OAuth access token key) | — |
| `SECRET_PARAM` | `secret` | ✅ Yes | String (OAuth access token secret) | — |
| `DROPBOX_PATH_PARAM` | `dropboxpath` | Optional (job spec) | String (folder path) | `/` |

---

## 10. Google Drive Repository Connector

**Config class:** `GoogleDriveConfig` (`connectors/googledrive/connector/src/main/java/.../googledrive/GoogleDriveConfig.java`)

| Java Constant | Exact Parameter String | Required | Type / Allowed Values | Default |
|---|---|---|---|---|
| `CLIENT_ID_PARAM` | `clientid` | ✅ Yes | String (OAuth2 Client ID) | — |
| `CLIENT_SECRET_PARAM` | `clientsecret` | ✅ Yes | String (OAuth2 Client Secret) | — |
| `REFRESH_TOKEN_PARAM` | `refreshtoken` | ✅ Yes | String (OAuth2 Refresh Token) | — |
| `GOOGLEDRIVE_QUERY_PARAM` | `googledriveQuery` | Optional (job spec) | Google Drive query string | `mimeType='application/vnd.google-apps.folder' and trashed=false` |

---

## 11. SharePoint Repository Connector

**Config class:** `SharePointConfig` (`connectors/sharepoint/connector/src/main/java/.../sharepoint/SharePointConfig.java`)

| Java Constant | Exact Parameter String | Required | Type / Allowed Values | Default |
|---|---|---|---|---|
| `PARAM_SERVERVERSION` | `serverVersion` | ✅ Yes | `2.0`, `3.0`, `4.0` (SP2007/2010/2013+) | — |
| `PARAM_SERVERPROTOCOL` | `serverProtocol` | ✅ Yes | `http`, `https` | — |
| `PARAM_SERVERNAME` | `serverName` | ✅ Yes | String (hostname) | — |
| `PARAM_SERVERPORT` | `serverPort` | Optional | String (numeric) | — |
| `PARAM_SERVERLOCATION` | `serverLocation` | Optional | String (site path) | — |
| `PARAM_SERVERUSERNAME` | `userName` | ✅ Yes | String (domain\\user format) | — |
| `PARAM_SERVERPASSWORD` | `password` | ✅ Yes | String (obfuscated) | — |
| `PARAM_SERVERKEYSTORE` | `keystore` | Optional | Certificate keystore (binary/encoded) | — |
| `PARAM_PROXYHOST` | `proxyHost` | Optional | String | — |
| `PARAM_PROXYPORT` | `proxyPort` | Optional | String | — |
| `PARAM_PROXYUSER` | `proxyUser` | Optional | String | — |
| `PARAM_PROXYPASSWORD` | `proxyPassword` | Optional | String (obfuscated) | — |
| `PARAM_PROXYDOMAIN` | `proxyDomain` | Optional | String | — |
| `PARAM_AUTHORITYTYPE` | `authorityType` | Optional | String | — |

---

## 12. Web Crawler Repository Connector

**Config class:** `WebcrawlerConfig` (`connectors/webcrawler/connector/src/main/java/.../webcrawler/WebcrawlerConfig.java`)

| Java Constant | Exact Parameter String | Required | Type / Allowed Values | Default |
|---|---|---|---|---|
| `PARAMETER_ROBOTSUSAGE` | `Robots usage` | ✅ Yes | `all`, `none`, `data` | — |
| `PARAMETER_META_ROBOTS_TAGS_USAGE` | `Meta robots tags usage` | Optional | String | — |
| `PARAMETER_EMAIL` | `Email address` | ✅ Yes | String (contact email) | — |
| `PARAMETER_PROXYHOST` | `Proxy host` | Optional | String | — |
| `PARAMETER_PROXYPORT` | `Proxy port` | Optional | String | — |
| `PARAMETER_PROXYAUTHDOMAIN` | `Proxy authentication domain` | Optional | String | — |
| `PARAMETER_PROXYAUTHUSERNAME` | `Proxy authentication user name` | Optional | String | — |
| `PARAMETER_PROXYAUTHPASSWORD` | `Proxy authentication password` | Optional | String (obfuscated) | — |
| `PARAMETER_USER_AGENT_PLATFORM` | `User-Agent platform` | Optional | String | — |

**Child nodes (complex configuration):**
- `bindesc` — Bin descriptions (`binregexp`, `caseinsensitive`)
- `maxconnections` — Max connections per bin (`binregexp`, `value`)
- `maxkbpersecond` — Bandwidth limits (`binregexp`, `value`)
- `maxfetchesperminute` — Fetch rate limits (`binregexp`, `value`)
- `accesscredential` — Auth credentials (`urlregexp`, `type` [`basic`|`ntlm`|`session`], `domain`, `username`, `password`)
- `authpage` — Auth page config (`urlregexp`, type [`form`|`link`|`redirection`|`content`], `match`, `overridetargeturl`)
- `authparameter` — Auth form params (`name`, `password`, `value`)
- `trust` — SSL trust (`urlregexp`, `truststore`, `trusteverything`)

---

## 13. File System Repository Connector

**Config class:** None — `FileConnector` has **no connection-level ConfigParams**.

All configuration is at the **job specification level**:

**Job Specification nodes:**
- `startpoint` — Root paths to crawl (attribute: `path`)
- Each startpoint can have include/exclude rules as child nodes

---

## 14. REST API Repository Connector

**Config class:** `RestApiConfig` (`connectors/restapi/connector/src/main/java/.../restapi/RestApiConfig.java`)

> **NOTE**: All parameter names are **UPPERCASE** (matching Java enum `.name()`).

### Connection Parameters

| Java Constant | Exact Parameter String | Required | Type / Allowed Values | Default |
|---|---|---|---|---|
| `VENDOR_PARAM` | `VENDOR` | Optional | `confluence`, `jira`, `wordpress`, `github`, `sharepoint`, `notion`, `drupal`, `alfresco`, `other` | `other` |
| `AUTH_TYPE_PARAM` | `AUTHTYPE` | ✅ Yes | `basic`, `bearer`, `apikey`, `oauth2`, `none` | `basic` |
| `USERNAME_PARAM` | `USERNAME` | Conditional | String (for basic auth) | — |
| `PASSWORD_PARAM` | `PASSWORD` | Conditional | String (obfuscated, for basic auth) | — |
| `API_KEY_PARAM` | `APIKEY` | Conditional | String (for bearer/apikey auth) | — |
| `API_KEY_HEADER_PARAM` | `APIKEYHEADER` | Optional | String (HTTP header name) | `Authorization` |
| `PROTOCOL_PARAM` | `PROTOCOL` | ✅ Yes | `http`, `https` | `https` |
| `SERVER_PARAM` | `SERVER` | ✅ Yes | String (hostname) | — |
| `PORT_PARAM` | `PORT` | Optional | String (numeric) | `443` |
| `BASE_PATH_PARAM` | `BASEPATH` | Optional | String (URL path prefix) | — |

### Endpoint Parameters

| Java Constant | Exact Parameter String | Required | Description | Default |
|---|---|---|---|---|
| `SEED_ENDPOINT_PARAM` | `SEEDENDPOINT` | ✅ Yes | Listing endpoint path | — |
| `DOC_ENDPOINT_PARAM` | `DOCENDPOINT` | Optional | Detail endpoint (use `{id}`) | — |
| `CONTENT_ENDPOINT_PARAM` | `CONTENTENDPOINT` | Optional | Binary download endpoint (use `{id}`) | — |
| `ACL_ENDPOINT_PARAM` | `ACLENDPOINT` | Optional | Permissions endpoint (use `{id}`) | — |

### Pagination Parameters

| Java Constant | Exact Parameter String | Required | Type / Allowed Values | Default |
|---|---|---|---|---|
| `PAGINATION_TYPE_PARAM` | `PAGINATIONTYPE` | Optional | `offset`, `page`, `cursor`, `link`, `none` | `offset` |
| `PAGE_SIZE_PARAM` | `PAGESIZE` | Optional | String (numeric) | `100` |
| `OFFSET_PARAM_NAME` | `OFFSETPARAM` | Optional | Query param name for offset | `offset` |
| `LIMIT_PARAM_NAME` | `LIMITPARAM` | Optional | Query param name for limit | `limit` |
| `PAGE_PARAM_NAME` | `PAGEPARAM` | Optional | Query param name for page number | `page` |
| `CURSOR_FIELD_PARAM` | `CURSORFIELD` | Optional | JSONPath to next cursor in response | — |
| `CURSOR_PARAM_NAME` | `CURSORPARAM` | Optional | Query param name to send cursor | — |

### Response Mapping (JSONPath)

| Java Constant | Exact Parameter String | Required | Description | Default |
|---|---|---|---|---|
| `ITEMS_PATH_PARAM` | `ITEMSPATH` | ✅ Yes | JSONPath to items array | `$.results` |
| `ID_FIELD_PARAM` | `IDFIELD` | ✅ Yes | JSONPath to document ID | `$.id` |
| `TITLE_FIELD_PARAM` | `TITLEFIELD` | Optional | JSONPath to title | `$.name` |
| `CONTENT_FIELD_PARAM` | `CONTENTFIELD` | Optional | JSONPath to content body | `$.content` |
| `MIMETYPE_FIELD_PARAM` | `MIMETYPEFIELD` | Optional | JSONPath to mime type | `$.mimeType` |
| `CREATED_DATE_FIELD_PARAM` | `CREATEDDATEFIELD` | Optional | JSONPath to created date | `$.createdDate` |
| `MODIFIED_DATE_FIELD_PARAM` | `MODIFIEDDATEFIELD` | Optional | JSONPath to modified date | `$.modifiedDate` |
| `SIZE_FIELD_PARAM` | `SIZEFIELD` | Optional | JSONPath to file size | `$.size` |
| `DOWNLOAD_URL_FIELD_PARAM` | `DOWNLOADURLFIELD` | Optional | JSONPath to download URL | — |

### ACL Mapping

| Java Constant | Exact Parameter String | Required | Description | Default |
|---|---|---|---|---|
| `ACL_ALLOW_FIELD_PARAM` | `ACLALLOWFIELD` | Optional | JSONPath to allow principals array | — |
| `ACL_DENY_FIELD_PARAM` | `ACLDENYFIELD` | Optional | JSONPath to deny principals array | — |
| `ACL_PRINCIPAL_FIELD_PARAM` | `ACLPRINCIPALFIELD` | Optional | JSONPath to principal ID | — |

### Miscellaneous

| Java Constant | Exact Parameter String | Required | Description | Default |
|---|---|---|---|---|
| `MAX_FILE_SIZE_PARAM` | `MAXFILESIZE` | Optional | Max file size in bytes (0 = no limit) | — |
| `CUSTOM_HEADERS_PARAM` | `CUSTOMHEADERS` | Optional | Custom HTTP headers (key=value, newline-separated) | — |
| `RESPONSE_TYPE_PARAM` | `RESPONSETYPE` | Optional | `json`, `xml` | `json` |
| `QUERY_PARAM` | `RESTAPIQUERY` | Optional (job spec) | Custom query/filter string | — |

**Vendor Presets** (auto-populate fields): `confluence`, `jira`, `wordpress`, `github`, `sharepoint`, `notion`, `drupal`, `alfresco`, `other`.

---

## 15. ElasticSearch Output Connector

**Config class:** `ElasticSearchConfig` / `ElasticSearchParam.ParameterEnum` (`connectors/elasticsearch/`)

> **NOTE**: Parameter names use `ParameterEnum.name()` which is **UPPERCASE**.

| Java Enum | Exact Parameter String | Required | Description | Default |
|---|---|---|---|---|
| `SERVERLOCATION` | `SERVERLOCATION` | ✅ Yes | Full URL (e.g., `http://localhost:9200/`) | `http://localhost:9200/` |
| `USERNAME` | `USERNAME` | Optional | Basic auth username | `""` |
| `PASSWORD` | `PASSWORD` | Optional | Basic auth password (obfuscated) | `""` |
| `SERVERKEYSTORE` | `SERVERKEYSTORE` | Optional | SSL certificate keystore | `""` |
| `INDEXNAME` | `INDEXNAME` | ✅ Yes | Index name | `index` |
| `INDEXTYPE` | `INDEXTYPE` | Optional | Document type (ES 6.x compat) | `_doc` |
| `USEINGESTATTACHMENT` | `USEINGESTATTACHMENT` | Optional | `true`, `false` | `false` |
| `USEMAPPERATTACHMENTS` | `USEMAPPERATTACHMENTS` | Optional | `true`, `false` | `false` |
| `PIPELINENAME` | `PIPELINENAME` | Optional | Ingest pipeline name | `""` |
| `CONTENTATTRIBUTENAME` | `CONTENTATTRIBUTENAME` | Optional | Field name for content | `content` |
| `URIATTRIBUTENAME` | `URIATTRIBUTENAME` | Optional | Field name for URI | `url` |
| `CREATEDDATEATTRIBUTENAME` | `CREATEDDATEATTRIBUTENAME` | Optional | Field name for created date | `created` |
| `MODIFIEDDATEATTRIBUTENAME` | `MODIFIEDDATEATTRIBUTENAME` | Optional | Field name for modified date | `last-modified` |
| `INDEXINGDATEATTRIBUTENAME` | `INDEXINGDATEATTRIBUTENAME` | Optional | Field name for indexing date | `indexed` |
| `MIMETYPEATTRIBUTENAME` | `MIMETYPEATTRIBUTENAME` | Optional | Field name for mime type | `mime-type` |
| `ELASTICSEARCH_SOCKET_TIMEOUT` | `ELASTICSEARCH_SOCKET_TIMEOUT` | Optional | Socket timeout (ms) | `900000` |
| `ELASTICSEARCH_CONNECTION_TIMEOUT` | `ELASTICSEARCH_CONNECTION_TIMEOUT` | Optional | Connection timeout (ms) | `60000` |
| `AUTHORITIESINDEXNAME` | `AUTHORITIESINDEXNAME` | Optional | Authorities index name (empty = disabled) | `""` |

---

## Quick Reference: Case Sensitivity Summary

| Connector | Parameter Casing | Example |
|---|---|---|
| CMIS | **lowercase** | `username`, `binding`, `protocol` |
| Amazon S3 | **lowercase** with underscores | `aws_access_key`, `amazons3_proxy_host` |
| Confluence | **lowercase** | `username`, `host`, `port` |
| Confluence v6 | **lowercase** with underscores | `username`, `socket_timeout`, `proxy_host` |
| Email | **lowercase** | `username`, `server`, `protocol` |
| Jira | **lowercase** concatenated | `clientid`, `jiraprotocol`, `jiraproxyhost` |
| JDBC | **Title Case with spaces** | `JDBC Provider`, `Host`, `Database name` |
| Dropbox | **lowercase** with underscores | `app_key`, `app_secret`, `key` |
| Google Drive | **lowercase** concatenated | `clientid`, `clientsecret`, `refreshtoken` |
| SharePoint | **camelCase** | `serverVersion`, `serverName`, `proxyHost` |
| Web Crawler | **Title Case with spaces** | `Robots usage`, `Email address`, `Proxy host` |
| File System | _No connection params_ | _(job spec only)_ |
| REST API | **UPPERCASE** | `VENDOR`, `AUTHTYPE`, `SEEDENDPOINT` |
| ElasticSearch | **UPPERCASE** | `SERVERLOCATION`, `INDEXNAME`, `USERNAME` |
