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
// Vendor-specific default API URL paths for group/user sync
var vendorApiDefaults = {
  "alfresco": {
    groupApiUrl: "/alfresco/api/-default-/public/alfresco/versions/1/groups",
    groupMembersApiUrl: "/alfresco/api/-default-/public/alfresco/versions/1/groups/{groupId}/members"
  },
  "sharepoint": {
    groupApiUrl: "/_api/web/sitegroups",
    groupMembersApiUrl: "/_api/web/sitegroups({groupId})/users"
  },
  "filenet": {
    groupApiUrl: "/P8CE/rest/v1/groups",
    groupMembersApiUrl: "/P8CE/rest/v1/groups/{groupId}/members"
  },
  "opentext": {
    groupApiUrl: "/dctm-rest/repositories/default/groups",
    groupMembersApiUrl: "/dctm-rest/repositories/default/groups/{groupId}/members"
  },
  "nuxeo": {
    groupApiUrl: "/nuxeo/api/v1/directory/groupDirectory",
    groupMembersApiUrl: "/nuxeo/api/v1/group/{groupId}"
  },
  "hptrim": {
    groupApiUrl: "",
    groupMembersApiUrl: ""
  },
  "other": {
    groupApiUrl: "",
    groupMembersApiUrl: ""
  }
};

function onVendorChange()
{
  var vendor = editconnection.cmisVendor.value;
  var defaults = vendorApiDefaults[vendor];
  if (defaults)
  {
    editconnection.groupApiUrl.value = defaults.groupApiUrl;
    editconnection.groupMembersApiUrl.value = defaults.groupMembersApiUrl;
  }
}

function testGroupApi()
{
  // Set the test trigger and submit form to stay on the Server tab
  document.getElementById("_testGroupApi").value = "true";
  SelectTab("$Encoder.bodyJavascriptEscape($ResourceBundle.getString('CmisRepositoryConnector.Server'))");
}

function checkConfig()
{
  return true;
}
 
function checkConfigForSave()
{
  if (editconnection.username.value == "")
  {
    alert("$Encoder.bodyJavascriptEscape($ResourceBundle.getString('CmisRepositoryConnector.TheUsernameMustNotBeNull'))");
    SelectTab("$Encoder.bodyJavascriptEscape($ResourceBundle.getString('CmisRepositoryConnector.Server'))");
    editconnection.username.focus();
    return false;
  }
  if (editconnection.password.value == "")
  {
    alert("$Encoder.bodyJavascriptEscape($ResourceBundle.getString('CmisRepositoryConnector.ThePasswordMustNotBeNull'))");
    SelectTab("$Encoder.bodyJavascriptEscape($ResourceBundle.getString('CmisRepositoryConnector.Server'))");
    editconnection.password.focus();
    return false;
  }
  if (editconnection.binding.value == "")
  {
    alert("$Encoder.bodyJavascriptEscape($ResourceBundle.getString('CmisRepositoryConnector.TheBindingMustNotBeNull'))");
    SelectTab("$Encoder.bodyJavascriptEscape($ResourceBundle.getString('CmisRepositoryConnector.Server'))");
    editconnection.binding.focus();
    return false;
  }
  if (editconnection.server.value ==""){
    alert("$Encoder.bodyJavascriptEscape($ResourceBundle.getString('CmisRepositoryConnector.ServerNameMustNotBeNull'))");
    SelectTab("$Encoder.bodyJavascriptEscape($ResourceBundle.getString('CmisRepositoryConnector.Server'))");
    editconnection.server.focus();
    return false;
  }
  if(editconnection.server.value.indexOf('/')!=-1) {
    alert("$Encoder.bodyJavascriptEscape($ResourceBundle.getString('CmisRepositoryConnector.ServerNameCantContainSlash'))");
    SelectTab("$Encoder.bodyJavascriptEscape($ResourceBundle.getString('CmisRepositoryConnector.Server'))");
    editconnection.server.focus();
    return false;
  }
  if (editconnection.port.value == "")
  {
    alert("$Encoder.bodyJavascriptEscape($ResourceBundle.getString('CmisRepositoryConnector.ThePortMustNotBeNull'))");
    SelectTab("$Encoder.bodyJavascriptEscape($ResourceBundle.getString('CmisRepositoryConnector.Server'))");
    editconnection.port.focus();
    return false;
  }
  if (!isInteger(editconnection.port.value)){
    alert("$Encoder.bodyJavascriptEscape($ResourceBundle.getString('CmisRepositoryConnector.TheServerPortMustBeValidInteger'))");
    SelectTab("$Encoder.bodyJavascriptEscape($ResourceBundle.getString('CmisRepositoryConnector.Server'))");
    editconnection.port.focus();
    return false;
  }
  if(editconnection.path.value == ""){
    alert("$Encoder.bodyJavascriptEscape($ResourceBundle.getString('CmisRepositoryConnector.PathMustNotBeNull'))");
    SelectTab("$Encoder.bodyJavascriptEscape($ResourceBundle.getString('CmisRepositoryConnector.Server'))");
    editconnection.path.focus();
    return false;
  }
  return true;
}
//-->
</script>