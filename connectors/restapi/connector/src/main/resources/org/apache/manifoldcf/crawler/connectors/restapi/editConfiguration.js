<!--
 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements.  See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to You under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->

<script type="text/javascript">
<!--

// Vendor presets data — auto-populates fields when vendor is selected
var vendorPresets = {
  'confluence': {
    AUTHTYPE: 'basic', PORT: '443',
    BASEPATH: '/wiki/api/v2',
    SEEDENDPOINT: '/pages',
    DOCENDPOINT: '/pages/{id}?body-format=storage',
    CONTENTENDPOINT: '',
    ACLENDPOINT: '/pages/{id}/operations',
    PAGINATIONTYPE: 'cursor', PAGESIZE: '100',
    CURSORFIELD: '\$._links.next', CURSORPARAM: 'cursor',
    ITEMSPATH: '\$.results', IDFIELD: '\$.id',
    TITLEFIELD: '\$.title', CONTENTFIELD: '\$.body.storage.value',
    MIMETYPEFIELD: '', CREATEDDATEFIELD: '\$.createdAt',
    MODIFIEDDATEFIELD: '\$.version.createdAt', SIZEFIELD: '',
    ACLALLOWFIELD: '\$.results', ACLPRINCIPALFIELD: '\$.principal.id',
    ACLDENYFIELD: '', DOWNLOADURLFIELD: ''
  },
  'jira': {
    AUTHTYPE: 'basic', PORT: '443',
    BASEPATH: '/rest/api/3',
    SEEDENDPOINT: '/search',
    DOCENDPOINT: '/issue/{id}',
    CONTENTENDPOINT: '',
    ACLENDPOINT: '',
    PAGINATIONTYPE: 'offset', PAGESIZE: '100',
    CURSORFIELD: '', CURSORPARAM: '',
    ITEMSPATH: '\$.issues', IDFIELD: '\$.id',
    TITLEFIELD: '\$.fields.summary', CONTENTFIELD: '\$.fields.description',
    MIMETYPEFIELD: '', CREATEDDATEFIELD: '\$.fields.created',
    MODIFIEDDATEFIELD: '\$.fields.updated', SIZEFIELD: '',
    ACLALLOWFIELD: '', ACLPRINCIPALFIELD: '',
    ACLDENYFIELD: '', DOWNLOADURLFIELD: ''
  },
  'wordpress': {
    AUTHTYPE: 'basic', PORT: '443',
    BASEPATH: '/wp-json/wp/v2',
    SEEDENDPOINT: '/posts',
    DOCENDPOINT: '/posts/{id}',
    CONTENTENDPOINT: '',
    ACLENDPOINT: '',
    PAGINATIONTYPE: 'page', PAGESIZE: '100',
    CURSORFIELD: '', CURSORPARAM: '',
    ITEMSPATH: '\$', IDFIELD: '\$.id',
    TITLEFIELD: '\$.title.rendered', CONTENTFIELD: '\$.content.rendered',
    MIMETYPEFIELD: '', CREATEDDATEFIELD: '\$.date_gmt',
    MODIFIEDDATEFIELD: '\$.modified_gmt', SIZEFIELD: '',
    ACLALLOWFIELD: '', ACLPRINCIPALFIELD: '',
    ACLDENYFIELD: '', DOWNLOADURLFIELD: ''
  },
  'github': {
    AUTHTYPE: 'bearer', PORT: '443',
    BASEPATH: '',
    SEEDENDPOINT: '/repos/{owner}/{repo}/contents',
    DOCENDPOINT: '/repos/{owner}/{repo}/contents/{path}',
    CONTENTENDPOINT: '',
    ACLENDPOINT: '/repos/{owner}/{repo}/collaborators',
    PAGINATIONTYPE: 'page', PAGESIZE: '100',
    CURSORFIELD: '', CURSORPARAM: '',
    ITEMSPATH: '\$', IDFIELD: '\$.sha',
    TITLEFIELD: '\$.name', CONTENTFIELD: '\$.content',
    MIMETYPEFIELD: '\$.type', CREATEDDATEFIELD: '',
    MODIFIEDDATEFIELD: '', SIZEFIELD: '\$.size',
    ACLALLOWFIELD: '\$', ACLPRINCIPALFIELD: '\$.login',
    ACLDENYFIELD: '', DOWNLOADURLFIELD: '\$.download_url'
  },
  'sharepoint': {
    AUTHTYPE: 'bearer', PORT: '443',
    BASEPATH: '/_api',
    SEEDENDPOINT: "/web/lists/getbytitle('Documents')/items",
    DOCENDPOINT: "/web/lists/getbytitle('Documents')/items({id})",
    CONTENTENDPOINT: "/web/GetFileByServerRelativeUrl('{path}')/\$value",
    ACLENDPOINT: "/web/lists/getbytitle('Documents')/items({id})/roleassignments",
    PAGINATIONTYPE: 'link', PAGESIZE: '5000',
    CURSORFIELD: '\$.d.__next', CURSORPARAM: '',
    ITEMSPATH: '\$.d.results', IDFIELD: '\$.Id',
    TITLEFIELD: '\$.Title', CONTENTFIELD: '',
    MIMETYPEFIELD: '', CREATEDDATEFIELD: '\$.Created',
    MODIFIEDDATEFIELD: '\$.Modified', SIZEFIELD: '\$.File.Length',
    ACLALLOWFIELD: '\$.d.results', ACLPRINCIPALFIELD: '\$.Member.LoginName',
    ACLDENYFIELD: '', DOWNLOADURLFIELD: ''
  },
  'notion': {
    AUTHTYPE: 'bearer', PORT: '443',
    BASEPATH: '/v1',
    SEEDENDPOINT: '/search',
    DOCENDPOINT: '/pages/{id}',
    CONTENTENDPOINT: '/blocks/{id}/children',
    ACLENDPOINT: '',
    PAGINATIONTYPE: 'cursor', PAGESIZE: '100',
    CURSORFIELD: '\$.next_cursor', CURSORPARAM: 'start_cursor',
    ITEMSPATH: '\$.results', IDFIELD: '\$.id',
    TITLEFIELD: '\$.properties.title.title[0].plain_text', CONTENTFIELD: '',
    MIMETYPEFIELD: '', CREATEDDATEFIELD: '\$.created_time',
    MODIFIEDDATEFIELD: '\$.last_edited_time', SIZEFIELD: '',
    ACLALLOWFIELD: '', ACLPRINCIPALFIELD: '',
    ACLDENYFIELD: '', DOWNLOADURLFIELD: ''
  },
  'drupal': {
    AUTHTYPE: 'basic', PORT: '443',
    BASEPATH: '/jsonapi',
    SEEDENDPOINT: '/node/article',
    DOCENDPOINT: '/node/article/{id}',
    CONTENTENDPOINT: '',
    ACLENDPOINT: '',
    PAGINATIONTYPE: 'cursor', PAGESIZE: '50',
    CURSORFIELD: '\$.links.next.href', CURSORPARAM: '',
    ITEMSPATH: '\$.data', IDFIELD: '\$.id',
    TITLEFIELD: '\$.attributes.title', CONTENTFIELD: '\$.attributes.body.value',
    MIMETYPEFIELD: '', CREATEDDATEFIELD: '\$.attributes.created',
    MODIFIEDDATEFIELD: '\$.attributes.changed', SIZEFIELD: '',
    ACLALLOWFIELD: '', ACLPRINCIPALFIELD: '',
    ACLDENYFIELD: '', DOWNLOADURLFIELD: ''
  },
  'alfresco': {
    AUTHTYPE: 'basic', PORT: '443',
    BASEPATH: '/alfresco/api/-default-/public/alfresco/versions/1',
    SEEDENDPOINT: '/queries/nodes?term=*',
    DOCENDPOINT: '/nodes/{id}',
    CONTENTENDPOINT: '/nodes/{id}/content',
    ACLENDPOINT: '/nodes/{id}/permissions',
    PAGINATIONTYPE: 'offset', PAGESIZE: '100',
    CURSORFIELD: '', CURSORPARAM: '',
    ITEMSPATH: '\$.list.entries', IDFIELD: '\$.entry.id',
    TITLEFIELD: '\$.entry.name', CONTENTFIELD: '',
    MIMETYPEFIELD: '\$.entry.content.mimeType', CREATEDDATEFIELD: '\$.entry.createdAt',
    MODIFIEDDATEFIELD: '\$.entry.modifiedAt', SIZEFIELD: '\$.entry.content.sizeInBytes',
    ACLALLOWFIELD: '\$.list.entries', ACLPRINCIPALFIELD: '\$.entry.authorityId',
    ACLDENYFIELD: '', DOWNLOADURLFIELD: ''
  }
};

function applyVendorPreset() {
  var vendor = document.editconnection.VENDOR.value;
  var preset = vendorPresets[vendor];
  if (!preset) return; // 'other' — leave fields as-is

  var fields = [
    'AUTHTYPE', 'PORT', 'BASEPATH', 'SEEDENDPOINT', 'DOCENDPOINT',
    'CONTENTENDPOINT', 'ACLENDPOINT', 'PAGINATIONTYPE', 'PAGESIZE',
    'CURSORFIELD', 'CURSORPARAM', 'ITEMSPATH', 'IDFIELD',
    'TITLEFIELD', 'CONTENTFIELD', 'MIMETYPEFIELD', 'CREATEDDATEFIELD',
    'MODIFIEDDATEFIELD', 'SIZEFIELD', 'ACLALLOWFIELD', 'ACLPRINCIPALFIELD',
    'ACLDENYFIELD', 'DOWNLOADURLFIELD'
  ];

  for (var i = 0; i < fields.length; i++) {
    var field = fields[i];
    var elem = document.editconnection[field];
    if (elem && preset[field] !== undefined) {
      elem.value = preset[field];
    }
  }

  // Toggle auth fields visibility
  updateAuthFields();
  // Toggle pagination fields
  updatePaginationFields();
}

function updateAuthFields() {
  var authType = document.editconnection.AUTHTYPE.value;
  var basicFields = document.getElementById('basicAuthFields');
  var tokenFields = document.getElementById('tokenAuthFields');
  if (basicFields) {
    basicFields.style.display = (authType === 'basic') ? '' : 'none';
  }
  if (tokenFields) {
    tokenFields.style.display = (authType === 'bearer' || authType === 'apikey') ? '' : 'none';
  }
}

function updatePaginationFields() {
  var pagType = document.editconnection.PAGINATIONTYPE.value;
  var offsetFields = document.getElementById('offsetPaginationFields');
  var pageFields = document.getElementById('pagePaginationFields');
  var cursorFields = document.getElementById('cursorPaginationFields');

  if (offsetFields) offsetFields.style.display = (pagType === 'offset') ? '' : 'none';
  if (pageFields) pageFields.style.display = (pagType === 'page') ? '' : 'none';
  if (cursorFields) cursorFields.style.display = (pagType === 'cursor' || pagType === 'link') ? '' : 'none';
}

function checkConfig() {
  return true;
}

function checkConfigForSave() {
  return true;
}

function doTestConnection() {
  if (editconnection.SERVER.value == "") {
    alert("$Encoder.bodyJavascriptEscape($ResourceBundle.getString('RestApiRepositoryConnector.ServerNameMustNotBeNull'))");
    SelectTab("$Encoder.bodyJavascriptEscape($ResourceBundle.getString('RestApiRepositoryConnector.Server'))");
    editconnection.SERVER.focus();
    return;
  }
  if (editconnection.SEEDENDPOINT.value == "") {
    alert("$Encoder.bodyJavascriptEscape($ResourceBundle.getString('RestApiRepositoryConnector.SeedEndpointMustNotBeNull'))");
    SelectTab("$Encoder.bodyJavascriptEscape($ResourceBundle.getString('RestApiRepositoryConnector.Server'))");
    editconnection.SEEDENDPOINT.focus();
    return;
  }
  document.editconnection._testConnection.value = "true";
  postFormSetAnchor("editconnection");
}

//-->
</script>
