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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.QueryResult;
import org.apache.chemistry.opencmis.client.api.Repository;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.commons.io.input.NullInputStream;
import org.apache.commons.lang.StringUtils;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.apache.manifoldcf.agents.interfaces.RepositoryDocument;
import org.apache.manifoldcf.agents.interfaces.ServiceInterruption;
import org.apache.manifoldcf.core.interfaces.ConfigParams;
import org.apache.manifoldcf.core.interfaces.IHTTPOutput;
import org.apache.manifoldcf.core.interfaces.IPasswordMapperActivity;
import org.apache.manifoldcf.core.interfaces.IPostParameters;
import org.apache.manifoldcf.core.interfaces.IThreadContext;
import org.apache.manifoldcf.core.interfaces.ManifoldCFException;
import org.apache.manifoldcf.core.interfaces.Specification;
import org.apache.manifoldcf.core.interfaces.SpecificationNode;
import org.apache.manifoldcf.crawler.connectors.BaseRepositoryConnector;
import org.apache.manifoldcf.crawler.interfaces.IExistingVersions;
import org.apache.manifoldcf.crawler.interfaces.IProcessActivity;
import org.apache.manifoldcf.crawler.interfaces.ISeedingActivity;
import org.apache.manifoldcf.crawler.system.Logging;

/**
 * This is the "repository connector" for a CMIS-compliant repository.
 *
 * @author Piergiorgio Lucidi
 */
public class CmisRepositoryConnector extends BaseRepositoryConnector {

  private static final String JOB_STARTPOINT_NODE_TYPE = "startpoint";

  protected final static String ACTIVITY_READ = "read document";
  protected static final String RELATIONSHIP_CHILD = "child";

  // Tab name properties

  private static final String CMIS_SERVER_TAB_PROPERTY = "CmisRepositoryConnector.Server";
  private static final String CMIS_QUERY_TAB_PROPERTY = "CmisRepositoryConnector.CMISQuery";


  // Template names

    /** Forward to the javascript to check the configuration parameters */
  private static final String EDIT_CONFIG_HEADER_FORWARD = "editConfiguration.js";

  /** Server tab template */
  private static final String EDIT_CONFIG_FORWARD_SERVER = "editConfiguration_Server.html";

  /** Forward to the javascript to check the specification parameters for the job */
  private static final String EDIT_SPEC_HEADER_FORWARD = "editSpecification.js";

  /** Forward to the template to edit the configuration parameters for the job */
  private static final String EDIT_SPEC_FORWARD_CMISQUERY = "editSpecification_CMISQuery.html";

  /** Forward to the HTML template to view the configuration parameters */
  private static final String VIEW_CONFIG_FORWARD = "viewConfiguration.html";

  /** Forward to the template to view the specification parameters for the job */
  private static final String VIEW_SPEC_FORWARD = "viewSpecification.html";
  
  /** The content path param used for managing content migration deletion **/
	private static final String CONTENT_PATH_PARAM = "contentPath";

  /**
   * CMIS Session handle
   */
  Session session = null;

  protected String username = null;
  protected String password = null;

  /** Endpoint protocol */
  protected String protocol = null;

  /** Endpoint server name */
  protected String server = null;

  /** Endpoint port */
  protected String port = null;

  /** Endpoint context path of the CMIS webapp */
  protected String path = null;

  protected String repositoryId = null;
  protected String binding = null;

  protected SessionFactory factory = SessionFactoryImpl.newInstance();
  protected Map<String, String> parameters = new HashMap<String, String>();

  public final static String ACTIVITY_FETCH = "fetch";

  protected static final long timeToRelease = 300000L;
  protected long lastSessionFetch = -1L;

  /** Max file size in bytes. Files exceeding this are skipped. 0 = no limit. */
  protected long maxFileSizeBytes = 0L;

  // Group membership syncer: resolves group members via vendor REST API
  // and indexes them in the manifoldcf_acl OpenSearch index during crawl.
  // At query time, the search service looks up the user's groups from this
  // index to build ACL-filtered queries.
  protected CmisGroupMembershipSyncer groupSyncer = null;

  // Track all unique user tokens (non-group ACL tokens) seen during this crawl,
  // used to populate the group_everyone membership.
  private final Set<String> allUserTokens = new HashSet<>();
    
  /**
   * Constructor
   */
  public CmisRepositoryConnector() {
    super();
  }

  /** Tell the world what model this connector uses for getDocumentIdentifiers().
  * This must return a model value as specified above.
  *@return the model type value.
  */
  @Override
  public int getConnectorModel()
  {
    return MODEL_ADD_CHANGE;
  }

  /**
   * Return the list of activities that this connector supports (i.e. writes into the log).
   * @return the list.
   */
  @Override
  public String[] getActivitiesList() {
    return new String[] { ACTIVITY_FETCH };
  }

  /** Get the bin name strings for a document identifier.  The bin name describes the queue to which the
   * document will be assigned for throttling purposes.  Throttling controls the rate at which items in a
   * given queue are fetched; it does not say anything about the overall fetch rate, which may operate on
   * multiple queues or bins.
   * For example, if you implement a web crawler, a good choice of bin name would be the server name, since
   * that is likely to correspond to a real resource that will need real throttle protection.
   *@param documentIdentifier is the document identifier.
   *@return the set of bin names.  If an empty array is returned, it is equivalent to there being no request
   * rate throttling available for this identifier.
   */
  @Override
  public String[] getBinNames(String documentIdentifier) {
    return new String[] { server };
  }

  protected class GetSessionThread extends Thread {
    protected Throwable exception = null;

    public GetSessionThread() {
      super();
      setDaemon(true);
    }

    public void run() {
      try {
        // Create a session
        parameters.clear();

        // user credentials
        parameters.put(SessionParameter.USER, username);
        parameters.put(SessionParameter.PASSWORD, password);

        String endpoint = protocol+"://"+server+":"+port+path;

        // connection settings
        if(CmisConfig.BINDING_ATOM_VALUE.equals(binding)){
          //AtomPub protocol
          parameters.put(SessionParameter.ATOMPUB_URL, endpoint);
          parameters.put(SessionParameter.BINDING_TYPE, BindingType.ATOMPUB.value());
        } else if(CmisConfig.BINDING_WS_VALUE.equals(binding)){
          //Web Services - SOAP - protocol
          parameters.put(SessionParameter.BINDING_TYPE, BindingType.WEBSERVICES.value());
          parameters.put(SessionParameter.WEBSERVICES_ACL_SERVICE, endpoint+"/ACLService?wsdl");
          parameters.put(SessionParameter.WEBSERVICES_DISCOVERY_SERVICE, endpoint+"/DiscoveryService?wsdl");
          parameters.put(SessionParameter.WEBSERVICES_MULTIFILING_SERVICE, endpoint+"/MultiFilingService?wsdl");
          parameters.put(SessionParameter.WEBSERVICES_NAVIGATION_SERVICE, endpoint+"/NavigationService?wsdl");
          parameters.put(SessionParameter.WEBSERVICES_OBJECT_SERVICE, endpoint+"/ObjectService?wsdl");
          parameters.put(SessionParameter.WEBSERVICES_POLICY_SERVICE, endpoint+"/PolicyService?wsdl");
          parameters.put(SessionParameter.WEBSERVICES_RELATIONSHIP_SERVICE, endpoint+"/RelationshipService?wsdl");
          parameters.put(SessionParameter.WEBSERVICES_REPOSITORY_SERVICE, endpoint+"/RepositoryService?wsdl");
          parameters.put(SessionParameter.WEBSERVICES_VERSIONING_SERVICE, endpoint+"/VersioningService?wsdl");
        } else if(CmisConfig.BINDING_BROWSER_VALUE.equals(binding)){
          //Browser (JSON) protocol - useful when server is behind HTTPS reverse proxy
          parameters.put(SessionParameter.BROWSER_URL, endpoint);
          parameters.put(SessionParameter.BINDING_TYPE, BindingType.BROWSER.value());
        }

        // When protocol is HTTPS, install a custom HTTP invoker that rewrites
        // http:// URLs (returned by some CMIS servers behind reverse proxies)
        // back to https://.
        if ("https".equalsIgnoreCase(protocol)) {
          parameters.put(SessionParameter.HTTP_INVOKER_CLASS,
              HttpsForceHttpInvoker.class.getName());
        }

        // create session
        if (StringUtils.isEmpty(repositoryId)) {

          // get a session from the first CMIS repository exposed by
          // the endpoint
          List<Repository> repos = null;
          try {
            repos = factory.getRepositories(parameters);
            session = repos.get(0).createSession();
          } catch (Exception e) {
            Logging.connectors.error("CMIS: Error during getting CMIS repositories. Please check the endpoint parameters: " + e.getMessage(), e);
            this.exception = e;
          }

        } else {

          // get a session from the repository specified in the
          // configuration with its own ID
          parameters.put(SessionParameter.REPOSITORY_ID, repositoryId);

          try {
            session = factory.createSession(parameters);
          } catch (Exception e) {
            Logging.connectors.error("CMIS: Error during the creation of the new session. Please check the endpoint parameters: " + e.getMessage(), e);
            this.exception = e;
          }

        }
        
        if(session != null) {
        	session.getDefaultContext().setCacheEnabled(false);
        }

      } catch (Throwable e) {
        this.exception = e;
      }
    }

    public Throwable getException() {
      return exception;
    }
  }

  protected class CheckConnectionThread extends Thread {
    protected Throwable exception = null;

    public CheckConnectionThread() {
      super();
      setDaemon(true);
    }

    public void run() {
      try {
        session.getRepositoryInfo();
      } catch (Throwable e) {
        Logging.connectors.warn("CMIS: Error checking repository: "+e.getMessage(),e);
        this.exception = e;
      }
    }

    public Throwable getException() {
      return exception;
    }

  }

  protected class DestroySessionThread extends Thread {
    protected Throwable exception = null;

    public DestroySessionThread() {
      super();
      setDaemon(true);
    }

    public void run() {
      try {
        session = null;
      } catch (Throwable e) {
        this.exception = e;
      }
    }

    public Throwable getException() {
      return exception;
    }

  }

  /**
   * Close the connection.  Call this before discarding the connection.
   */
  @Override
  public void disconnect() throws ManifoldCFException {
    if (session != null) {
      DestroySessionThread t = new DestroySessionThread();
      try {
        t.start();
        t.join();
        Throwable thr = t.getException();
        if (thr != null) {
          if (thr instanceof RemoteException)
            throw (RemoteException) thr;
          else if (thr instanceof RuntimeException)
            throw (RuntimeException) thr;
          else if (thr instanceof Error)
            throw (Error) thr;
          else
            throw new RuntimeException("Unexpected exception: " + thr.getMessage(), thr);
        }
        session = null;
        lastSessionFetch = -1L;
      } catch (InterruptedException e) {
        t.interrupt();
        throw new ManifoldCFException("Interrupted: " + e.getMessage(), e,
            ManifoldCFException.INTERRUPTED);
      } catch (RemoteException e) {
        Throwable e2 = e.getCause();
        if (e2 instanceof InterruptedException
            || e2 instanceof InterruptedIOException)
          throw new ManifoldCFException(e2.getMessage(), e2,
              ManifoldCFException.INTERRUPTED);
        session = null;
        lastSessionFetch = -1L;
        // Treat this as a transient problem
        Logging.connectors.warn(
            "CMIS: Transient remote exception closing session: "
                + e.getMessage(), e);
      }

    }

    // Sync group_everyone with all discovered user tokens before disconnecting
    if (groupSyncer != null && !allUserTokens.isEmpty()) {
      groupSyncer.syncEveryoneGroup(allUserTokens);
    }

    username = null;
    password = null;
    protocol = null;
    server = null;
    port = null;
    path = null;
    binding = null;
    repositoryId = null;
    groupSyncer = null;
    allUserTokens.clear();

  }

  /**
   * This method create a new CMIS session for a CMIS repository, if the
   * repositoryId is not provided in the configuration, the connector will
   * retrieve all the repositories exposed for this endpoint the it will start
   * to use the first one.
   * @param configParams is the set of configuration parameters, which
   * in this case describe the target appliance, basic auth configuration, etc.  (This formerly came
   * out of the ini file.)
   */
  @Override
  public void connect(ConfigParams configParams) {
    super.connect(configParams);
    username = params.getParameter(CmisConfig.USERNAME_PARAM);
    password = params.getParameter(CmisConfig.PASSWORD_PARAM);
    protocol = params.getParameter(CmisConfig.PROTOCOL_PARAM);
    server = params.getParameter(CmisConfig.SERVER_PARAM);
    port = params.getParameter(CmisConfig.PORT_PARAM);
    path = params.getParameter(CmisConfig.PATH_PARAM);

    binding = params.getParameter(CmisConfig.BINDING_PARAM);
    if (StringUtils.isNotEmpty(params.getParameter(CmisConfig.REPOSITORY_ID_PARAM)))
      repositoryId = params.getParameter(CmisConfig.REPOSITORY_ID_PARAM);

    // Max file size limit (bytes). 0 or empty = no limit.
    String maxFileSizeStr = params.getParameter(CmisConfig.MAX_FILE_SIZE_PARAM);
    if (StringUtils.isNotEmpty(maxFileSizeStr)) {
      try {
        maxFileSizeBytes = Long.parseLong(maxFileSizeStr);
      } catch (NumberFormatException e) {
        maxFileSizeBytes = 0L;
      }
    } else {
      maxFileSizeBytes = 0L;
    }
  }

  /** Test the connection.  Returns a string describing the connection integrity.
   *@return the connection's status as a displayable string.
   */
  @Override
  public String check() throws ManifoldCFException {
    try {
      checkConnection();
      return super.check();
    } catch (ServiceInterruption e) {
      return "Connection temporarily failed: " + e.getMessage();
    } catch (ManifoldCFException e) {
      return "Connection failed: " + e.getMessage();
    }
  }

  /** Set up a session */
  protected void getSession() throws ManifoldCFException, ServiceInterruption {
    if (session == null) {
      // Check for parameter validity

      if (StringUtils.isEmpty(binding))
        throw new ManifoldCFException("Parameter " + CmisConfig.BINDING_PARAM
            + " required but not set");

      if (StringUtils.isEmpty(username))
        throw new ManifoldCFException("Parameter " + CmisConfig.USERNAME_PARAM
            + " required but not set");

      if (Logging.connectors.isDebugEnabled())
        Logging.connectors.debug("CMIS: Username = '" + username + "'");

      if (StringUtils.isEmpty(password))
        throw new ManifoldCFException("Parameter " + CmisConfig.PASSWORD_PARAM
            + " required but not set");

      Logging.connectors.debug("CMIS: Password exists");

      if (StringUtils.isEmpty(protocol))
        throw new ManifoldCFException("Parameter " + CmisConfig.PROTOCOL_PARAM
            + " required but not set");

      if (StringUtils.isEmpty(server))
        throw new ManifoldCFException("Parameter " + CmisConfig.SERVER_PARAM
            + " required but not set");

      if (StringUtils.isEmpty(port))
        throw new ManifoldCFException("Parameter " + CmisConfig.PORT_PARAM
            + " required but not set");

      if (StringUtils.isEmpty(path))
        throw new ManifoldCFException("Parameter " + CmisConfig.PATH_PARAM
            + " required but not set");

      long currentTime;
      GetSessionThread t = new GetSessionThread();
      try {
        t.start();
        t.join();
        Throwable thr = t.getException();
        if (thr != null) {
          if (thr instanceof java.net.MalformedURLException)
            throw (java.net.MalformedURLException) thr;
          else if (thr instanceof NotBoundException)
            throw (NotBoundException) thr;
          else if (thr instanceof RemoteException)
            throw (RemoteException) thr;
          else if (thr instanceof CmisConnectionException)
            throw new ManifoldCFException("CMIS: Error during getting a new session: " + thr.getMessage(), thr);
          else if (thr instanceof CmisPermissionDeniedException)
            throw new ManifoldCFException("CMIS: Wrong credentials during getting a new session: " + thr.getMessage(), thr);
          else if (thr instanceof RuntimeException)
            throw (RuntimeException) thr;
          else if (thr instanceof Error)
            throw (Error) thr;
          else
            throw new RuntimeException("Unexpected exception: " + thr.getMessage(), thr);
        }
      } catch (InterruptedException e) {
        t.interrupt();
        throw new ManifoldCFException("Interrupted: " + e.getMessage(), e,
            ManifoldCFException.INTERRUPTED);
      } catch (java.net.MalformedURLException e) {
        throw new ManifoldCFException(e.getMessage(), e);
      } catch (NotBoundException e) {
        // Transient problem: Server not available at the moment.
        Logging.connectors.warn(
            "CMIS: Server not up at the moment: " + e.getMessage(), e);
        currentTime = System.currentTimeMillis();
        throw new ServiceInterruption(e.getMessage(), currentTime + 60000L);
      } catch (RemoteException e) {
        Throwable e2 = e.getCause();
        if (e2 instanceof InterruptedException
            || e2 instanceof InterruptedIOException)
          throw new ManifoldCFException(e2.getMessage(), e2,
              ManifoldCFException.INTERRUPTED);
        // Treat this as a transient problem
        Logging.connectors.warn(
            "CMIS: Transient remote exception creating session: "
                + e.getMessage(), e);
        currentTime = System.currentTimeMillis();
        throw new ServiceInterruption(e.getMessage(), currentTime + 60000L);
      }

    }

    lastSessionFetch = System.currentTimeMillis();

    // Initialize group membership syncer if not already done
    if (groupSyncer == null && protocol != null && server != null && port != null) {
      String vendorVal = params.getParameter(CmisConfig.VENDOR_PARAM);
      String gApiUrl = params.getParameter(CmisConfig.GROUP_API_URL_PARAM);
      String gMembersApiUrl = params.getParameter(CmisConfig.GROUP_MEMBERS_API_URL_PARAM);
      // Authority index name must be explicitly provided by the admin app.
      // If not set, group syncing is disabled — no auto-generated "manifold_*" names.
      String authorityIndexName = params.getParameter(CmisConfig.AUTHORITY_INDEX_NAME_PARAM);
      groupSyncer = new CmisGroupMembershipSyncer(protocol, server, port, username, password,
          vendorVal, gApiUrl, gMembersApiUrl, authorityIndexName);
    }
  }

  /**
   * Release the session, if it's time.
   */
  protected void releaseCheck() throws ManifoldCFException {
    if (lastSessionFetch == -1L)
      return;

    long currentTime = System.currentTimeMillis();
    if (currentTime >= lastSessionFetch + timeToRelease) {
      DestroySessionThread t = new DestroySessionThread();
      try {
        t.start();
        t.join();
        Throwable thr = t.getException();
        if (thr != null) {
          if (thr instanceof RemoteException)
            throw (RemoteException) thr;
          else if (thr instanceof RuntimeException)
            throw (RuntimeException) thr;
          else if (thr instanceof Error)
            throw (Error) thr;
          else
            throw new RuntimeException("Unexpected exception: " + thr.getMessage(), thr);
        }
        session = null;
        lastSessionFetch = -1L;
      } catch (InterruptedException e) {
        t.interrupt();
        throw new ManifoldCFException("Interrupted: " + e.getMessage(), e,
            ManifoldCFException.INTERRUPTED);
      } catch (RemoteException e) {
        Throwable e2 = e.getCause();
        if (e2 instanceof InterruptedException
            || e2 instanceof InterruptedIOException)
          throw new ManifoldCFException(e2.getMessage(), e2,
              ManifoldCFException.INTERRUPTED);
        session = null;
        lastSessionFetch = -1L;
        // Treat this as a transient problem
        Logging.connectors.warn(
            "CMIS: Transient remote exception closing session: "
                + e.getMessage(), e);
      }

    }
  }

  protected void checkConnection() throws ManifoldCFException,
      ServiceInterruption {
    while (true) {
      boolean noSession = (session == null);
      getSession();
      long currentTime;
      CheckConnectionThread t = new CheckConnectionThread();
      try {
        t.start();
        t.join();
        Throwable thr = t.getException();
        if (thr != null) {
          if (thr instanceof RemoteException)
            throw (RemoteException) thr;
          else if (thr instanceof CmisConnectionException)
            throw new ManifoldCFException("CMIS: Error during checking connection: " + thr.getMessage(), thr);
          else if (thr instanceof RuntimeException)
            throw (RuntimeException) thr;
          else if (thr instanceof Error)
            throw (Error) thr;
          else
            throw new RuntimeException("Unexpected exception: " + thr.getMessage(), thr);
        }
        return;
      } catch (InterruptedException e) {
        t.interrupt();
        throw new ManifoldCFException("Interrupted: " + e.getMessage(), e,
            ManifoldCFException.INTERRUPTED);
      } catch (RemoteException e) {
        Throwable e2 = e.getCause();
        if (e2 instanceof InterruptedException
            || e2 instanceof InterruptedIOException)
          throw new ManifoldCFException(e2.getMessage(), e2,
              ManifoldCFException.INTERRUPTED);
        if (noSession) {
          currentTime = System.currentTimeMillis();
          throw new ServiceInterruption(
              "Transient error connecting to filenet service: "
                  + e.getMessage(), currentTime + 60000L);
        }
        session = null;
        lastSessionFetch = -1L;
        continue;
      }
    }
  }

  /**
   * This method is periodically called for all connectors that are connected but not
   * in active use.
   */
  @Override
  public void poll() throws ManifoldCFException {
    if (lastSessionFetch == -1L)
      return;

    long currentTime = System.currentTimeMillis();
    if (currentTime >= lastSessionFetch + timeToRelease) {
      DestroySessionThread t = new DestroySessionThread();
      try {
        t.start();
        t.join();
        Throwable thr = t.getException();
        if (thr != null) {
          if (thr instanceof RemoteException)
            throw (RemoteException) thr;
          else if (thr instanceof RuntimeException)
            throw (RuntimeException) thr;
          else if (thr instanceof Error)
            throw (Error) thr;
          else
            throw new RuntimeException("Unexpected exception: " + thr.getMessage(), thr);
        }
        session = null;
        lastSessionFetch = -1L;
      } catch (InterruptedException e) {
        t.interrupt();
        throw new ManifoldCFException("Interrupted: " + e.getMessage(), e,
            ManifoldCFException.INTERRUPTED);
      } catch (RemoteException e) {
        Throwable e2 = e.getCause();
        if (e2 instanceof InterruptedException
            || e2 instanceof InterruptedIOException)
          throw new ManifoldCFException(e2.getMessage(), e2,
              ManifoldCFException.INTERRUPTED);
        session = null;
        lastSessionFetch = -1L;
        // Treat this as a transient problem
        Logging.connectors.warn(
            "CMIS: Transient remote exception closing session: "
                + e.getMessage(), e);
      }

    }
  }

  /** This method is called to assess whether to count this connector instance should
  * actually be counted as being connected.
  *@return true if the connector instance is actually connected.
  */
  @Override
  public boolean isConnected()
  {
    return session != null;
  }

  /** Queue "seed" documents.  Seed documents are the starting places for crawling activity.  Documents
  * are seeded when this method calls appropriate methods in the passed in ISeedingActivity object.
  *
  * This method can choose to find repository changes that happen only during the specified time interval.
  * The seeds recorded by this method will be viewed by the framework based on what the
  * getConnectorModel() method returns.
  *
  * It is not a big problem if the connector chooses to create more seeds than are
  * strictly necessary; it is merely a question of overall work required.
  *
  * The end time and seeding version string passed to this method may be interpreted for greatest efficiency.
  * For continuous crawling jobs, this method will
  * be called once, when the job starts, and at various periodic intervals as the job executes.
  *
  * When a job's specification is changed, the framework automatically resets the seeding version string to null.  The
  * seeding version string may also be set to null on each job run, depending on the connector model returned by
  * getConnectorModel().
  *
  * Note that it is always ok to send MORE documents rather than less to this method.
  * The connector will be connected before this method can be called.
  *@param activities is the interface this method should use to perform whatever framework actions are desired.
  *@param spec is a document specification (that comes from the job).
  *@param seedTime is the end of the time range of documents to consider, exclusive.
  *@param lastSeedVersion is the last seeding version string for this job, or null if the job has no previous seeding version string.
  *@param jobMode is an integer describing how the job is being run, whether continuous or once-only.
  *@return an updated seeding version string, to be stored with the job.
  */
  @Override
  public String addSeedDocuments(ISeedingActivity activities, Specification spec,
    String lastSeedVersion, long seedTime, int jobMode)
    throws ManifoldCFException, ServiceInterruption {

    getSession();

    String cmisQuery = StringUtils.EMPTY;
    for (int i = 0; i < spec.getChildCount(); i++)
    {
      SpecificationNode sn = spec.getChild(i);
      if (sn.getType().equals(JOB_STARTPOINT_NODE_TYPE)) {
        cmisQuery = sn.getAttributeValue(CmisConfig.CMIS_QUERY_PARAM);
        break;
      }
    }

    if (StringUtils.isEmpty(cmisQuery)) {
      // get root Documents from the CMIS Repository
      ItemIterable<CmisObject> cmisObjects = session.getRootFolder()
          .getChildren();
      for (CmisObject cmisObject : cmisObjects) {
          activities.addSeedDocument(cmisObject.getId());
      	}
    } else if (cmisQuery.contains("__AUTHORITIES_SYNC__")) {
      // Special marker: proactively sync ALL groups into the authorities index
      // without crawling any documents. Used by the dedicated authorities sync job.
      if (groupSyncer != null) {
        Logging.connectors.info("CMIS: Detected __AUTHORITIES_SYNC__ marker — running full group sync");
        groupSyncer.syncAllGroups();
      } else {
        Logging.connectors.warn("CMIS: __AUTHORITIES_SYNC__ marker found but group syncer is not enabled. "
            + "Ensure vendor, group API URL, and group members API URL are configured.");
      }
      // Return without adding any seed documents — the job completes after syncing
    } else {
      cmisQuery = CmisRepositoryConnectorUtils.getCmisQueryWithObjectId(cmisQuery);
      // Use automatic pagination (default page size ~100) instead of
      // getPage(1000000000) which causes Gateway Timeout on reverse proxies
      ItemIterable<QueryResult> results = session.query(cmisQuery, false);
      for (QueryResult result : results) {
      		String id = result.getPropertyValueById(PropertyIds.OBJECT_ID);
          activities.addSeedDocument(id);
      	}
    }

    return StringUtils.EMPTY;
  }



  /**
   * Get the maximum number of documents to amalgamate together into one batch, for this connector.
   * @return the maximum number. 0 indicates "unlimited".
   */
  @Override
  public int getMaxDocumentRequest() {
    return 1;
  }

  /**
   * Return the list of relationship types that this connector recognizes.
   *
   * @return the list.
   */
  @Override
  public String[] getRelationshipTypes() {
    return new String[] { RELATIONSHIP_CHILD };
  }

  /**
   * Read the content of a resource, replace the variable ${PARAMNAME} with the
   * value and copy it to the out.
   *
   * @param resName
   * @param out
   * @throws ManifoldCFException
   */
  private static void outputResource(String resName, IHTTPOutput out,
      Locale locale, Map<String,String> paramMap) throws ManifoldCFException {
    Messages.outputResourceWithVelocity(out,locale,resName,paramMap,true);
  }

  /** Fill in a Server tab configuration parameter map for calling a Velocity template.
  *@param newMap is the map to fill in
  *@param parameters is the current set of configuration parameters
  */
  private static void fillInServerConfigurationMap(Map<String,String> newMap, IPasswordMapperActivity mapper, ConfigParams parameters)
  {
    String username = parameters.getParameter(CmisConfig.USERNAME_PARAM);
    String password = parameters.getParameter(CmisConfig.PASSWORD_PARAM);
    String protocol = parameters.getParameter(CmisConfig.PROTOCOL_PARAM);
    String server = parameters.getParameter(CmisConfig.SERVER_PARAM);
    String port = parameters.getParameter(CmisConfig.PORT_PARAM);
    String path = parameters.getParameter(CmisConfig.PATH_PARAM);
    String repositoryId = parameters.getParameter(CmisConfig.REPOSITORY_ID_PARAM);
    String binding = parameters.getParameter(CmisConfig.BINDING_PARAM);

    if(username == null)
      username = StringUtils.EMPTY;
    if(password == null)
      password = StringUtils.EMPTY;
    else
      password = mapper.mapPasswordToKey(password);
    if(protocol == null)
      protocol = CmisConfig.PROTOCOL_DEFAULT_VALUE;
    if(server == null)
      server = CmisConfig.SERVER_DEFAULT_VALUE;
    if(port == null)
      port = CmisConfig.PORT_DEFAULT_VALUE;
    if(path == null)
      path = CmisConfig.PATH_DEFAULT_VALUE;
    if(repositoryId == null)
      repositoryId = StringUtils.EMPTY;
    if(binding == null)
      binding = CmisConfig.BINDING_ATOM_VALUE;

    String cmisVendor = parameters.getParameter(CmisConfig.VENDOR_PARAM);
    String groupApiUrl = parameters.getParameter(CmisConfig.GROUP_API_URL_PARAM);
    String groupMembersApiUrl = parameters.getParameter(CmisConfig.GROUP_MEMBERS_API_URL_PARAM);
    String groupApiTestResult = parameters.getParameter(CmisConfig.GROUP_API_TEST_RESULT_PARAM);
    String skipAclWait = parameters.getParameter(CmisConfig.SKIP_ACL_WAIT_PARAM);

    if(cmisVendor == null)
      cmisVendor = CmisConfig.VENDOR_DEFAULT_VALUE;
    if(groupApiUrl == null)
      groupApiUrl = CmisConfig.GROUP_API_URL_DEFAULT_VALUE;
    if(groupMembersApiUrl == null)
      groupMembersApiUrl = CmisConfig.GROUP_MEMBERS_API_URL_DEFAULT_VALUE;
    if(groupApiTestResult == null)
      groupApiTestResult = StringUtils.EMPTY;
    if(skipAclWait == null)
      skipAclWait = CmisConfig.SKIP_ACL_WAIT_DEFAULT_VALUE;

    newMap.put(CmisConfig.USERNAME_PARAM, username);
    newMap.put(CmisConfig.PASSWORD_PARAM, password);
    newMap.put(CmisConfig.PROTOCOL_PARAM, protocol);
    newMap.put(CmisConfig.SERVER_PARAM, server);
    newMap.put(CmisConfig.PORT_PARAM, port);
    newMap.put(CmisConfig.PATH_PARAM, path);
    newMap.put(CmisConfig.REPOSITORY_ID_PARAM, repositoryId);
    newMap.put(CmisConfig.BINDING_PARAM, binding);
    newMap.put(CmisConfig.VENDOR_PARAM, cmisVendor);
    newMap.put(CmisConfig.GROUP_API_URL_PARAM, groupApiUrl);
    newMap.put(CmisConfig.GROUP_MEMBERS_API_URL_PARAM, groupMembersApiUrl);
    newMap.put(CmisConfig.GROUP_API_TEST_RESULT_PARAM, groupApiTestResult);
    newMap.put(CmisConfig.SKIP_ACL_WAIT_PARAM, skipAclWait);
  }

  /**
   * View configuration. This method is called in the body section of the
   * connector's view configuration page. Its purpose is to present the
   * connection information to the user. The coder can presume that the HTML that
   * is output from this configuration will be within appropriate &lt;html&gt; and
   * &lt;body&gt; tags.
   *
   * @param threadContext
   *          is the local thread context.
   * @param out
   *          is the output to which any HTML should be sent.
   * @param parameters
   *          are the configuration parameters, as they currently exist, for
   *          this connection being configured.
   */
  @Override
  public void viewConfiguration(IThreadContext threadContext, IHTTPOutput out,
      Locale locale, ConfigParams parameters) throws ManifoldCFException, IOException {
    Map<String,String> paramMap = new HashMap<String,String>();

    // Fill in map from each tab
    fillInServerConfigurationMap(paramMap, out, parameters);

    outputResource(VIEW_CONFIG_FORWARD, out, locale, paramMap);
  }

  /**

   * Output the configuration header section. This method is called in the head
   * section of the connector's configuration page. Its purpose is to add the
   * required tabs to the list, and to output any javascript methods that might
   * be needed by the configuration editing HTML.
   *
   * @param threadContext
   *          is the local thread context.
   * @param out
   *          is the output to which any HTML should be sent.
   * @param parameters
   *          are the configuration parameters, as they currently exist, for
   *          this connection being configured.
   * @param tabsArray
   *          is an array of tab names. Add to this array any tab names that are
   *          specific to the connector.
   */
  @Override
  public void outputConfigurationHeader(IThreadContext threadContext,
      IHTTPOutput out, Locale locale, ConfigParams parameters, List<String> tabsArray)
      throws ManifoldCFException, IOException {
    // Add the Server tab
    tabsArray.add(Messages.getString(locale,CMIS_SERVER_TAB_PROPERTY));
    // Map the parameters
    Map<String,String> paramMap = new HashMap<String,String>();

    // Fill in the parameters from each tab
    fillInServerConfigurationMap(paramMap, out, parameters);

    // Output the Javascript - only one Velocity template for all tabs
    outputResource(EDIT_CONFIG_HEADER_FORWARD, out, locale, paramMap);
  }

  @Override
  public void outputConfigurationBody(IThreadContext threadContext,
      IHTTPOutput out, Locale locale, ConfigParams parameters, String tabName)
      throws ManifoldCFException, IOException {

    // Call the Velocity templates for each tab

    // Server tab
    Map<String,String> paramMap = new HashMap<String,String>();
    // Set the tab name
    paramMap.put("TabName", tabName);
    // Fill in the parameters
    fillInServerConfigurationMap(paramMap, out, parameters);
    outputResource(EDIT_CONFIG_FORWARD_SERVER, out, locale, paramMap);

  }

  /**
   * Process a configuration post. This method is called at the start of the
   * connector's configuration page, whenever there is a possibility that form
   * data for a connection has been posted. Its purpose is to gather form
   * information and modify the configuration parameters accordingly. The name
   * of the posted form is "editconnection".
   *
   * @param threadContext
   *          is the local thread context.
   * @param variableContext
   *          is the set of variables available from the post, including binary
   *          file post information.
   * @param parameters
   *          are the configuration parameters, as they currently exist, for
   *          this connection being configured.
   * @return null if all is well, or a string error message if there is an error
   *         that should prevent saving of the connection (and cause a
   *         redirection to an error page).
   */
  @Override
  public String processConfigurationPost(IThreadContext threadContext,
      IPostParameters variableContext, ConfigParams parameters)
      throws ManifoldCFException {

    String binding = variableContext.getParameter(CmisConfig.BINDING_PARAM);
    if (binding != null)
      parameters.setParameter(CmisConfig.BINDING_PARAM, binding);

    String username = variableContext.getParameter(CmisConfig.USERNAME_PARAM);
    if (username != null)
      parameters.setParameter(CmisConfig.USERNAME_PARAM, username);

    String password = variableContext.getParameter(CmisConfig.PASSWORD_PARAM);
    if (password != null)
      parameters.setParameter(CmisConfig.PASSWORD_PARAM, variableContext.mapKeyToPassword(password));

    String protocol = variableContext.getParameter(CmisConfig.PROTOCOL_PARAM);
    if (protocol != null) {
      parameters.setParameter(CmisConfig.PROTOCOL_PARAM, protocol);
    }

    String server = variableContext.getParameter(CmisConfig.SERVER_PARAM);
    if (server != null && !StringUtils.contains(server, '/')) {
      parameters.setParameter(CmisConfig.SERVER_PARAM, server);
    }

    String port = variableContext.getParameter(CmisConfig.PORT_PARAM);
    if (port != null){
      try {
        Integer.parseInt(port);
        parameters.setParameter(CmisConfig.PORT_PARAM, port);
      } catch (NumberFormatException e) {

      }
    }

    String path = variableContext.getParameter(CmisConfig.PATH_PARAM);
    if (path != null) {
      parameters.setParameter(CmisConfig.PATH_PARAM, path);
    }

    String repositoryId = variableContext.getParameter(CmisConfig.REPOSITORY_ID_PARAM);
    if (repositoryId != null) {
      parameters.setParameter(CmisConfig.REPOSITORY_ID_PARAM, repositoryId);
    }

    // New vendor and group API params
    String cmisVendor = variableContext.getParameter(CmisConfig.VENDOR_PARAM);
    if (cmisVendor != null) {
      parameters.setParameter(CmisConfig.VENDOR_PARAM, cmisVendor);
    }

    String groupApiUrl = variableContext.getParameter(CmisConfig.GROUP_API_URL_PARAM);
    if (groupApiUrl != null) {
      parameters.setParameter(CmisConfig.GROUP_API_URL_PARAM, groupApiUrl);
    }

    String groupMembersApiUrl = variableContext.getParameter(CmisConfig.GROUP_MEMBERS_API_URL_PARAM);
    if (groupMembersApiUrl != null) {
      parameters.setParameter(CmisConfig.GROUP_MEMBERS_API_URL_PARAM, groupMembersApiUrl);
    }

    // Skip ACL wait checkbox — unchecked checkboxes don't send a value, so absence means "false"
    String skipAclWait = variableContext.getParameter(CmisConfig.SKIP_ACL_WAIT_PARAM);
    if (skipAclWait != null) {
      parameters.setParameter(CmisConfig.SKIP_ACL_WAIT_PARAM, skipAclWait);
    } else {
      // Checkbox was present (on the Server tab) but unchecked
      // Use the binding param already read above as a proxy for "Server tab was active"
      if (binding != null) {
        // Server tab was active, so checkbox absence means false
        parameters.setParameter(CmisConfig.SKIP_ACL_WAIT_PARAM, "false");
      }
    }

    // Test Group API if requested
    String testGroupApi = variableContext.getParameter("_testGroupApi");
    if ("true".equals(testGroupApi)) {
      // All params are now saved in 'parameters' — use them for the test
      String testProtocol = parameters.getParameter(CmisConfig.PROTOCOL_PARAM);
      String testServer = parameters.getParameter(CmisConfig.SERVER_PARAM);
      String testPort = parameters.getParameter(CmisConfig.PORT_PARAM);
      String testUsername = parameters.getParameter(CmisConfig.USERNAME_PARAM);
      String testPassword = parameters.getParameter(CmisConfig.PASSWORD_PARAM);
      String testGroupApiUrl = parameters.getParameter(CmisConfig.GROUP_API_URL_PARAM);
      String testGroupMembersApiUrl = parameters.getParameter(CmisConfig.GROUP_MEMBERS_API_URL_PARAM);
      String testVendor = parameters.getParameter(CmisConfig.VENDOR_PARAM);

      String result = testGroupApiConnection(testProtocol, testServer, testPort,
          testUsername, testPassword, testGroupApiUrl, testGroupMembersApiUrl, testVendor);
      parameters.setParameter(CmisConfig.GROUP_API_TEST_RESULT_PARAM, result);
    } else {
      // Clear previous test result on normal form submissions
      parameters.setParameter(CmisConfig.GROUP_API_TEST_RESULT_PARAM, "");
    }

    return null;
  }

  /** Fill in specification Velocity parameter map for CMISQuery tab.
  */
  private static void fillInCMISQuerySpecificationMap(Map<String,String> newMap, Specification ds)
  {
    int i = 0;
    String cmisQuery = StringUtils.EMPTY;
    while (i < ds.getChildCount()) {
      SpecificationNode sn = ds.getChild(i);
      if (sn.getType().equals(JOB_STARTPOINT_NODE_TYPE)) {
        cmisQuery = sn.getAttributeValue(CmisConfig.CMIS_QUERY_PARAM);
      }
      i++;
    }
    newMap.put(CmisConfig.CMIS_QUERY_PARAM, cmisQuery);
  }

  /** View specification.
  * This method is called in the body section of a job's view page.  Its purpose is to present the document
  * specification information to the user.  The coder can presume that the HTML that is output from
  * this configuration will be within appropriate &lt;html&gt; and &lt;body&gt;tags.
  * The connector will be connected before this method can be called.
  *@param out is the output to which any HTML should be sent.
  *@param locale is the locale the output is preferred to be in.
  *@param ds is the current document specification for this job.
  *@param connectionSequenceNumber is the unique number of this connection within the job.
  */
  @Override
  public void viewSpecification(IHTTPOutput out, Locale locale, Specification ds,
    int connectionSequenceNumber)
    throws ManifoldCFException, IOException {

    Map<String,String> paramMap = new HashMap<String,String>();
    paramMap.put("SeqNum", Integer.toString(connectionSequenceNumber));

    // Fill in the map with data from all tabs
    fillInCMISQuerySpecificationMap(paramMap, ds);

    outputResource(VIEW_SPEC_FORWARD, out, locale, paramMap);
  }

  /** Process a specification post.
  * This method is called at the start of job's edit or view page, whenever there is a possibility that form
  * data for a connection has been posted.  Its purpose is to gather form information and modify the
  * document specification accordingly.  The name of the posted form is always "editjob".
  * The connector will be connected before this method can be called.
  *@param variableContext contains the post data, including binary file-upload information.
  *@param locale is the locale the output is preferred to be in.
  *@param ds is the current document specification for this job.
  *@param connectionSequenceNumber is the unique number of this connection within the job.
  *@return null if all is well, or a string error message if there is an error that should prevent saving of
  * the job (and cause a redirection to an error page).
  */
  @Override
  public String processSpecificationPost(IPostParameters variableContext, Locale locale, Specification ds,
    int connectionSequenceNumber)
    throws ManifoldCFException {
    String seqPrefix = "s"+connectionSequenceNumber+"_";

    String cmisQuery = variableContext.getParameter(seqPrefix + CmisConfig.CMIS_QUERY_PARAM);
    if (cmisQuery != null) {
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
      node.setAttribute(CmisConfig.CMIS_QUERY_PARAM, cmisQuery);
      variableContext.setParameter(CmisConfig.CMIS_QUERY_PARAM, cmisQuery);
      
      ds.addChild(ds.getChildCount(), node);
    }
    return null;
  }

  /** Output the specification body section.
  * This method is called in the body section of a job page which has selected a repository connection of the
  * current type.  Its purpose is to present the required form elements for editing.
  * The coder can presume that the HTML that is output from this configuration will be within appropriate
  *  &lt;html&gt;, &lt;body&gt;, and &lt;form&gt; tags.  The name of the form is always "editjob".
  * The connector will be connected before this method can be called.
  *@param out is the output to which any HTML should be sent.
  *@param locale is the locale the output is preferred to be in.
  *@param ds is the current document specification for this job.
  *@param connectionSequenceNumber is the unique number of this connection within the job.
  *@param actualSequenceNumber is the connection within the job that has currently been selected.
  *@param tabName is the current tab name.  (actualSequenceNumber, tabName) form a unique tuple within
  *  the job.
  */
  @Override
  public void outputSpecificationBody(IHTTPOutput out, Locale locale, Specification ds,
    int connectionSequenceNumber, int actualSequenceNumber, String tabName)
    throws ManifoldCFException, IOException {

    // Output CMISQuery tab
    Map<String,String> paramMap = new HashMap<String,String>();
    paramMap.put("TabName", tabName);
    paramMap.put("SeqNum", Integer.toString(connectionSequenceNumber));
    paramMap.put("SelectedNum", Integer.toString(actualSequenceNumber));

    fillInCMISQuerySpecificationMap(paramMap, ds);
    outputResource(EDIT_SPEC_FORWARD_CMISQUERY, out, locale, paramMap);
  }

  /** Output the specification header section.
  * This method is called in the head section of a job page which has selected a repository connection of the
  * current type.  Its purpose is to add the required tabs to the list, and to output any javascript methods
  * that might be needed by the job editing HTML.
  * The connector will be connected before this method can be called.
  *@param out is the output to which any HTML should be sent.
  *@param locale is the locale the output is preferred to be in.
  *@param ds is the current document specification for this job.
  *@param connectionSequenceNumber is the unique number of this connection within the job.
  *@param tabsArray is an array of tab names.  Add to this array any tab names that are specific to the connector.
  */
  @Override
  public void outputSpecificationHeader(IHTTPOutput out, Locale locale, Specification ds,
    int connectionSequenceNumber, List<String> tabsArray)
    throws ManifoldCFException, IOException {
    tabsArray.add(Messages.getString(locale,CMIS_QUERY_TAB_PROPERTY));

    Map<String,String> paramMap = new HashMap<String,String>();
    paramMap.put("SeqNum", Integer.toString(connectionSequenceNumber));

    // Fill in the specification header map, using data from all tabs.
    fillInCMISQuerySpecificationMap(paramMap, ds);

    outputResource(EDIT_SPEC_HEADER_FORWARD, out, locale, paramMap);
  }

  /** Process a set of documents.
  * This is the method that should cause each document to be fetched, processed, and the results either added
  * to the queue of documents for the current job, and/or entered into the incremental ingestion manager.
  * The document specification allows this class to filter what is done based on the job.
  * The connector will be connected before this method can be called.
  *@param documentIdentifiers is the set of document identifiers to process.
  *@param statuses are the currently-stored document versions for each document in the set of document identifiers
  * passed in above.
  *@param activities is the interface this method should use to queue up new document references
  * and ingest documents.
  *@param jobMode is an integer describing how the job is being run, whether continuous or once-only.
  *@param usesDefaultAuthority will be true only if the authority in use for these documents is the default one.
  */
  @Override
  public void processDocuments(String[] documentIdentifiers, IExistingVersions statuses, Specification spec,
    IProcessActivity activities, int jobMode, boolean usesDefaultAuthority)
    throws ManifoldCFException, ServiceInterruption {

    // Extract what we need from the spec
    String cmisQuery = StringUtils.EMPTY;
    for (int i = 0; i < spec.getChildCount(); i++)
    {
      SpecificationNode sn = spec.getChild(i);
      if (sn.getType().equals(JOB_STARTPOINT_NODE_TYPE)) {
        cmisQuery = sn.getAttributeValue(CmisConfig.CMIS_QUERY_PARAM);
        break;
      }
    }


    for (String documentIdentifier : documentIdentifiers) {

      if (Logging.connectors.isDebugEnabled())
        Logging.connectors.debug("CMIS: Processing document identifier '"
            + documentIdentifier + "'");

      getSession();

      // Load the object.  If this fails, it has been deleted.
      CmisObject cmisObject;
      try {
       cmisObject = session.getObject(documentIdentifier);
      } catch (CmisObjectNotFoundException e) {
        cmisObject = null;
      }

      if (cmisObject == null) {
        activities.deleteDocument(documentIdentifier);
        continue;
      }

      String versionString;

      if (cmisObject.getBaseType().getId().equals(BaseTypeId.CMIS_DOCUMENT.value())) {
        Document document = (Document) cmisObject;

        // Since documents that are not current have different node id's, we can return a constant version,
        // EXCEPT when the document is not the current one (in which case we delete)
        boolean isCurrentVersion;
        try {
          Document d = document.getObjectOfLatestVersion(false);
          isCurrentVersion = d.getId().equals(documentIdentifier);
        } catch (CmisObjectNotFoundException e) {
          isCurrentVersion = false;
        }
        if (isCurrentVersion) {
          //System.out.println(" is latest version");
          //versionString = documentIdentifier + ":" + cmisQuery;
      // take into account of the last modification date
            long lmdSeconds = document.getLastModificationDate().getTimeInMillis();
            versionString = documentIdentifier + lmdSeconds + ":" + cmisQuery;
        } else {
          //System.out.println(" is NOT latest version");
        	activities.deleteDocument(documentIdentifier);
          continue;
        }
      } else {
        //a CMIS folder will always be processed
        //System.out.println(" is folder");
        versionString = StringUtils.EMPTY;
      }

      if (versionString.length() == 0 || activities.checkDocumentNeedsReindexing(documentIdentifier,versionString)) {
        // Index this document
        String errorCode = null;
        String errorDesc = null;
        Long fileLengthLong = null;
        long startTime = System.currentTimeMillis();
        try {
          String baseTypeId = cmisObject.getBaseType().getId();

          if (baseTypeId.equals(BaseTypeId.CMIS_FOLDER.value())) {
            // adding all the children for a folder
            Folder folder = (Folder) cmisObject;
            ItemIterable<CmisObject> children = folder.getChildren();
            for (CmisObject child : children) {
              activities.addDocumentReference(child.getId(), documentIdentifier,
                  RELATIONSHIP_CHILD);
            }
          } else if(baseTypeId.equals(BaseTypeId.CMIS_DOCUMENT.value())) {
            // content ingestion

            Document document = (Document) cmisObject;

            Date createdDate = document.getCreationDate().getTime();
            Date modifiedDate = document.getLastModificationDate().getTime();
            long fileLength = document.getContentStreamLength();
            String fileName = document.getContentStreamFileName();
            String mimeType = document.getContentStreamMimeType();
            
            //documentURI
            String documentURI = getDocumentURI(cmisObject);

            // Do any filtering (which will save us work)
            if (!activities.checkURLIndexable(documentURI))
            {
              activities.noDocument(documentIdentifier,versionString);
              errorCode = IProcessActivity.EXCLUDED_URL;
              errorDesc = "Excluding due to URL ('"+documentURI+"')";
              continue;
            }

            if (!activities.checkMimeTypeIndexable(mimeType))
            {
              activities.noDocument(documentIdentifier,versionString);
              errorCode = IProcessActivity.EXCLUDED_MIMETYPE;
              errorDesc = "Excluding due to mime type ("+mimeType+")";
              continue;
            }

            // Enforce max file size limit from connector configuration
            if (maxFileSizeBytes > 0 && fileLength > maxFileSizeBytes)
            {
              activities.noDocument(documentIdentifier,versionString);
              errorCode = IProcessActivity.EXCLUDED_LENGTH;
              errorDesc = "Excluding due to max file size limit: " + fileLength + " bytes > " + maxFileSizeBytes + " bytes (" + (maxFileSizeBytes / (1024*1024)) + " MB) — file: " + fileName;
              if (Logging.connectors.isInfoEnabled())
                Logging.connectors.info("CMIS: Skipping document '" + fileName + "' (" + fileLength + " bytes) — exceeds maxFileSize (" + maxFileSizeBytes + " bytes)");
              continue;
            }

            if (!activities.checkLengthIndexable(fileLength))
            {
              activities.noDocument(documentIdentifier,versionString);
              errorCode = IProcessActivity.EXCLUDED_LENGTH;
              errorDesc = "Excluding due to length ("+fileLength+")";
              continue;
            }

            if (!activities.checkDateIndexable(modifiedDate))
            {
              activities.noDocument(documentIdentifier,versionString);
              errorCode = IProcessActivity.EXCLUDED_DATE;
              errorDesc = "Excluding due to date ("+modifiedDate+")";
              continue;
            }
            
            
            RepositoryDocument rd = new RepositoryDocument();
            rd.setFileName(fileName);
            rd.setMimeType(mimeType);
            rd.setCreatedDate(createdDate);
            rd.setModifiedDate(modifiedDate);

            // Extract CMIS ACLs and set on RepositoryDocument.
            // This is vendor-agnostic — works with any CMIS 1.0/1.1 server
            // (Alfresco, Nuxeo, SharePoint, Documentum, etc.) that supports ACLs.
            extractAndSetAcl(cmisObject, rd);

            InputStream is = null;
            try {
              if (fileLength > 0)
                is = document.getContentStream().getStream();
              else
                is = null;
            } catch (CmisObjectNotFoundException e) {
              // Document gone
            	activities.deleteDocument(documentIdentifier);
              continue;

            }

            try {
              //binary
              if(is != null) {
                rd.setBinary(is, fileLength);
              } else {
                rd.setBinary(new NullInputStream(0),0);
              }

              //modify the query in order to get the cmis:objectId field
              String modifiedQuery = CmisRepositoryConnectorUtils.getCmisQueryWithObjectId(cmisQuery);

              //filter the fields selected in the query
              CmisRepositoryConnectorUtils.addValuesOfProperties(document, rd, modifiedQuery);
              //ingestion

              try {
                activities.ingestDocumentWithException(documentIdentifier, versionString, documentURI, rd);
                fileLengthLong = new Long(fileLength);
                errorCode = "OK";
              } catch (IOException e) {
                errorCode = e.getClass().getSimpleName().toUpperCase(Locale.ROOT);
                errorDesc = e.getMessage();
                handleIOException(e, "reading file input stream");
              }
            } catch (Exception ecc) {
               ecc.printStackTrace();
              } finally {
              try {
                if(is!=null){
                  is.close();
                }
              } catch (IOException e) {
                errorCode = e.getClass().getSimpleName().toUpperCase(Locale.ROOT);
                errorDesc = e.getMessage();
                handleIOException(e, "closing file input stream");
              }
            }
          } else {
            // Unrecognized document type
            activities.noDocument(documentIdentifier,versionString);
            errorCode = "UNKNOWNTYPE";
            errorDesc = "Document type is unrecognized: '"+baseTypeId+"'";
          }
        } catch (ManifoldCFException e) {
          if (e.getErrorCode() == ManifoldCFException.INTERRUPTED)
            errorCode = null;
          throw e;
        } finally {
          if (errorCode != null)
            activities.recordActivity(new Long(startTime), ACTIVITY_READ,
              fileLengthLong, documentIdentifier, errorCode, errorDesc, null);
        }
      }
    }

  }

  /**
   * Extract CMIS ACLs from a CmisObject and set them on the RepositoryDocument.
   * 
   * <p>This is <b>vendor-agnostic</b> — it uses the standard CMIS 1.0/1.1
   * {@link org.apache.chemistry.opencmis.commons.data.Acl} API, which is
   * supported by Alfresco, Nuxeo, SharePoint, Documentum, Google Drive
   * (via CMIS bridge), and any other CMIS-compliant server.</p>
   * 
   * <p>The CMIS spec defines permissions as strings (e.g. {@code cmis:read},
   * {@code cmis:write}, {@code cmis:all}). Since different vendors may use
   * vendor-specific permission strings, we treat <b>any</b> permission as
   * "allow read" and store the principal ID as an allow token. This is the
   * safest approach — at query time, the authority connector can then decide
   * which tokens a user actually holds.</p>
   *
   * <p><b>Group-to-user resolution:</b> When a group principal is detected
   * by the active {@link GroupMemberResolver}, the resolver expands it to
   * individual member usernames. This enables ACL-based search filtering using
   * only the identity provider username (e.g., Keycloak) — no need to know
   * which content-server groups the user belongs to at query time. The raw
   * group token is also kept for backward compatibility with authority
   * connector workflows.</p>
   *
   * <p>The resolver is created by {@link GroupMemberResolverFactory} based on
   * the CMIS repository's {@code productName} — e.g., Alfresco servers get
   * {@link AlfrescoGroupMemberResolver}, unknown servers get
   * {@link NoOpGroupMemberResolver}.</p>
   *
   * <p>If the server does not support ACLs or the ACL is not available,
   * no security is set on the RepositoryDocument — which causes the output
   * connector to use its default ({@code __nosecurity__}).</p>
   *
   * @param cmisObject the CMIS object (document or folder)
   * @param rd the RepositoryDocument to set ACLs on
   */
  private void extractAndSetAcl(CmisObject cmisObject, RepositoryDocument rd) {
    try {
      // IMPORTANT: cmisObject.getAcl() returns cached ACL data from the initial
      // getObject() call. Since the default OperationContext has includeACL=false,
      // the cached ACL will always be null. We must use the CMIS binding's
      // AclService to make a dedicated ACL request for this object.
      String repositoryId = session.getRepositoryInfo().getId();
      String objectId = cmisObject.getId();

      Acl acl = session.getBinding().getAclService()
          .getAcl(repositoryId, objectId, true, null);

      if (acl == null || acl.getAces() == null || acl.getAces().isEmpty()) {
        return;
      }

      // Process document-level ACEs — store raw principals (users + groups) as-is.
      List<String> allowTokens = resolveAcesToTokens(acl);
      List<String> denyTokens = new ArrayList<>();

      // Sync group memberships to OpenSearch during crawl.
      // For each group token, resolve members via vendor REST API
      // and index in manifoldcf_acl for query-time ACL filtering.
      if (groupSyncer != null) {
        groupSyncer.syncGroupTokens(allowTokens);
        // Track user tokens for group_everyone
        for (String token : allowTokens) {
          if (!token.startsWith("group_")) {
            allUserTokens.add(token);
          }
        }
      }

      // Also extract parent folder ACLs if available
      if (cmisObject instanceof Document) {
        Document doc = (Document) cmisObject;
        List<Folder> parents = doc.getParents();
        if (parents != null && !parents.isEmpty()) {
          Folder parentFolder = parents.get(0);
          String parentId = parentFolder.getId();
          try {
            Acl parentAcl = session.getBinding().getAclService()
                .getAcl(repositoryId, parentId, true, null);
            if (parentAcl != null && parentAcl.getAces() != null) {
              List<String> parentAllowTokens = resolveAcesToTokens(parentAcl);
              // Also sync parent folder group tokens
              if (groupSyncer != null) {
                groupSyncer.syncGroupTokens(parentAllowTokens);
                for (String token : parentAllowTokens) {
                  if (!token.startsWith("group_")) {
                    allUserTokens.add(token);
                  }
                }
              }
              if (!parentAllowTokens.isEmpty()) {
                rd.setSecurity(RepositoryDocument.SECURITY_TYPE_PARENT,
                    parentAllowTokens.toArray(new String[0]),
                    new String[0]);
              }
            }
          } catch (Exception e) {
            Logging.connectors.warn("CMIS: Could not retrieve parent folder ACL: " + e.getMessage());
          }
        }
      }

      // Set document-level ACL
      if (!allowTokens.isEmpty()) {
        rd.setSecurity(RepositoryDocument.SECURITY_TYPE_DOCUMENT,
            allowTokens.toArray(new String[0]),
            denyTokens.toArray(new String[0]));
      }
    } catch (Exception e) {
      // If ACL retrieval fails (e.g., server doesn't support ACLs),
      // log a warning but don't fail the document. The output connector
      // will use __nosecurity__ as fallback.
      Logging.connectors.warn(
          "CMIS: Could not extract ACLs for object '"
          + cmisObject.getId() + "': " + e.getMessage());
    }
  }

  /**
   * Process a CMIS ACL into a list of allow tokens. Stores ALL principals
   * (both users and groups) as-is in lowercase.
   *
   * <p>Group membership resolution happens in two complementary ways:</p>
   * <ol>
   *   <li><b>At crawl time</b> — {@link CmisGroupMembershipSyncer} resolves
   *       group members via the configured vendor REST API and stores the mapping in
   *       the {@code manifoldcf_acl} OpenSearch index. This is triggered
   *       automatically from {@link #extractAndSetAcl}.</li>
   *   <li><b>At query time</b> — the search service looks up the user's
   *       groups from the {@code manifoldcf_acl} index and includes them
   *       in the OpenSearch ACL filter query.</li>
   * </ol>
   *
   * @param acl the CMIS ACL to process
   * @return deduplicated list of lowercase allow tokens (raw principals)
   */
  private List<String> resolveAcesToTokens(Acl acl) {
    Set<String> tokenSet = new HashSet<>();

    for (Ace ace : acl.getAces()) {
      String principalId = ace.getPrincipalId();
      if (principalId == null || principalId.isEmpty()) {
        continue;
      }

      List<String> permissions = ace.getPermissions();
      if (permissions == null || permissions.isEmpty()) {
        continue;
      }

      // Store ALL principals (users and groups) as-is, lowercased.
      // Group members are resolved by CmisGroupMembershipSyncer during crawl
      // and stored in the manifoldcf_acl index for query-time lookups.
      tokenSet.add(principalId.toLowerCase(Locale.ROOT));
    }

    return new ArrayList<>(tokenSet);
  }

  private String getDocumentURI(CmisObject cmisObject) throws ManifoldCFException {
  	String documentURI = StringUtils.EMPTY;
  	String currentBaseTypeId = cmisObject.getBaseTypeId().value();
  	if(StringUtils.equals(currentBaseTypeId, BaseTypeId.CMIS_DOCUMENT.value())) {
  		Document currentDocument = (Document) cmisObject;
			if(currentDocument.getParents() != null 
					&& !currentDocument.getParents().isEmpty()) {
				String path = currentDocument.getParents().get(0).getPath();
      	String name = currentDocument.getName();
      	String fullContentPath = path + CmisRepositoryConnectorUtils.SLASH + name;
      	documentURI = fullContentPath;
      	
				//Append the new parameters in the query string
      	String documentDownloadURL = CmisRepositoryConnectorUtils.getDocumentURL(currentDocument, session);
      	if(StringUtils.contains(documentDownloadURL, '?')){
      		documentURI = documentDownloadURL + "&" +CONTENT_PATH_PARAM+"=" + fullContentPath;
      	} else {
      		documentURI = documentDownloadURL + "?" +CONTENT_PATH_PARAM+"=" + fullContentPath;
      	}
			} else {
				// Unfiled document (no parent folder) — use objectId as URI
				// to avoid empty document IDs that cause HTTP 405 on PUT
				documentURI = cmisObject.getId();
			}
  	} else if(StringUtils.equals(currentBaseTypeId, BaseTypeId.CMIS_FOLDER.value())) {
  		Folder currentFolder = (Folder) cmisObject;
  		String path = currentFolder.getPath();
  		String name = currentFolder.getName();
  		String fullContentPath = path + CmisRepositoryConnectorUtils.SLASH + name;
  		documentURI = fullContentPath;
  	}
  	return documentURI;
  }
  
  protected static void handleIOException(IOException e, String context) throws ManifoldCFException, ServiceInterruption {
    if (e instanceof InterruptedIOException) {
      throw new ManifoldCFException(e.getMessage(), e,
        ManifoldCFException.INTERRUPTED);
    } else {
      Logging.connectors.warn(
        "CMIS: IOException "+context+": "
          + e.getMessage(), e);
      throw new ManifoldCFException(e.getMessage(), e);
    }
  }

  // =========================================================================
  //  Group API Test — called from processConfigurationPost when user
  //  clicks "Test Group API" button on the Server tab.
  // =========================================================================

  /**
   * Test the configured Group API by making real HTTP calls to the groups
   * list endpoint and (if a group is found) the members endpoint.
   *
   * <p>Returns a prefixed result string that the Velocity template uses
   * for color-coded display:</p>
   * <ul>
   *   <li>{@code PASS|...} — green: both APIs work, ACL data is sufficient</li>
   *   <li>{@code WARN|...} — yellow: groups API works but members API has issues</li>
   *   <li>{@code FAIL|...} — red: critical error, ACL verification not possible</li>
   * </ul>
   */
  private static String testGroupApiConnection(String protocol, String server,
      String port, String username, String password,
      String groupApiUrl, String groupMembersApiUrl, String vendor) {

    if (groupApiUrl == null || groupApiUrl.trim().isEmpty()) {
      return "FAIL|Group API URL is not configured. Please select a vendor or enter a custom URL.";
    }
    if (groupMembersApiUrl == null || groupMembersApiUrl.trim().isEmpty()) {
      return "FAIL|Group Members API URL is not configured. Please select a vendor or enter a custom URL.";
    }

    String baseUrl = protocol + "://" + server + ":" + port;
    String basicAuth = "Basic " + Base64.getEncoder()
        .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));

    // Set up SSL trust-all for HTTPS testing
    if ("https".equalsIgnoreCase(protocol)) {
      try {
        setupTestSslTrust();
      } catch (Exception e) {
        // Continue anyway — might work if cert is valid
      }
    }

    StringBuilder result = new StringBuilder();

    // --- Step 1: Call Groups List API ---
    String groupsFullUrl = baseUrl + groupApiUrl;
    String groupsResponse;
    try {
      groupsResponse = testHttpGet(groupsFullUrl, basicAuth);
    } catch (Exception e) {
      return "FAIL|Groups API error: " + e.getMessage()
          + "\nURL: " + groupsFullUrl
          + "\n\nPlease check credentials, server address, and API URL path.";
    }

    if (groupsResponse == null || groupsResponse.trim().isEmpty()) {
      return "FAIL|Groups API returned empty response.\nURL: " + groupsFullUrl;
    }

    // --- Step 2: Parse groups response to find group IDs ---
    List<String> groupIds = extractGroupIds(groupsResponse, vendor);
    if (groupIds.isEmpty()) {
      return "WARN|Groups API returned a response but no group IDs could be parsed.\n"
          + "URL: " + groupsFullUrl + "\n"
          + "Response preview: " + truncate(groupsResponse, 500)
          + "\n\nThe response format may not be compatible. Please verify the API URL returns a list of groups.";
    }

    result.append("Groups API: OK - Found ").append(groupIds.size()).append(" group(s)");
    if (groupIds.size() <= 5) {
      result.append(": ").append(groupIds);
    } else {
      result.append(" (first 5: ").append(groupIds.subList(0, 5)).append(")");
    }

    // --- Step 3: Test Members API with the first group ---
    String testGroupId = groupIds.get(0);
    String membersUrlTemplate = groupMembersApiUrl;
    String membersFullUrl;
    try {
      String encodedGroupId = URLEncoder.encode(testGroupId, "UTF-8");
      membersFullUrl = baseUrl + membersUrlTemplate
          .replace("{groupId}", encodedGroupId)
          .replace("({groupId})", "(" + encodedGroupId + ")");
    } catch (Exception e) {
      return "WARN|" + result + "\n\nCould not construct Members API URL: " + e.getMessage();
    }

    String membersResponse;
    try {
      membersResponse = testHttpGet(membersFullUrl, basicAuth);
    } catch (Exception e) {
      return "WARN|" + result
          + "\n\nMembers API error for group '" + testGroupId + "': " + e.getMessage()
          + "\nURL: " + membersFullUrl
          + "\n\nGroups API works but member resolution may not be possible.";
    }

    if (membersResponse == null || membersResponse.trim().isEmpty()) {
      return "WARN|" + result
          + "\n\nMembers API returned empty response for group '" + testGroupId + "'."
          + "\nURL: " + membersFullUrl;
    }

    // --- Step 4: Parse members response ---
    List<String> memberIds = extractMemberIds(membersResponse, vendor);
    if (memberIds.isEmpty()) {
      return "WARN|" + result
          + "\n\nMembers API returned a response for group '" + testGroupId
          + "' but no user IDs could be parsed."
          + "\nResponse preview: " + truncate(membersResponse, 500)
          + "\n\nACL data may not be sufficient for access control. "
          + "The API needs to return user identifiers within group membership.";
    }

    result.append("\nMembers API: OK - Group '").append(testGroupId)
        .append("' has ").append(memberIds.size()).append(" member(s)");
    if (memberIds.size() <= 10) {
      result.append(": ").append(memberIds);
    } else {
      result.append(" (first 10: ").append(memberIds.subList(0, 10)).append(")");
    }

    result.append("\n\n✓ ACL Check: PASSED — The API provides sufficient data for group-based access control. ")
        .append("Group memberships will be indexed in manifoldcf_acl for query-time ACL filtering.");

    return "PASS|" + result;
  }

  /**
   * Extract group IDs from a groups API response based on vendor format.
   */
  private static List<String> extractGroupIds(String json, String vendor) {
    List<String> ids = new ArrayList<>();
    if (json == null) return ids;

    if ("alfresco".equals(vendor)) {
      // Alfresco: {"list":{"entries":[{"entry":{"id":"GROUP_xxx",...}},...]}}
      extractFieldValues(json, "id", ids, 20);
    } else if ("sharepoint".equals(vendor)) {
      // SharePoint: {"value":[{"Id":1,"LoginName":"...","Title":"..."}]}
      extractFieldValues(json, "LoginName", ids, 20);
      if (ids.isEmpty()) {
        extractFieldValues(json, "Title", ids, 20);
      }
    } else if ("nuxeo".equals(vendor)) {
      // Nuxeo: {"entries":[{"id":"administrators",...},...]}
      extractFieldValues(json, "id", ids, 20);
    } else if ("filenet".equals(vendor) || "opentext".equals(vendor)) {
      // FileNet/OpenText: try common patterns
      extractFieldValues(json, "id", ids, 20);
      if (ids.isEmpty()) {
        extractFieldValues(json, "name", ids, 20);
      }
    } else {
      // Generic: try "id", "name", "groupId", "Id"
      extractFieldValues(json, "id", ids, 20);
      if (ids.isEmpty()) extractFieldValues(json, "Id", ids, 20);
      if (ids.isEmpty()) extractFieldValues(json, "name", ids, 20);
      if (ids.isEmpty()) extractFieldValues(json, "groupId", ids, 20);
    }
    return ids;
  }

  /**
   * Extract member user IDs from a group members API response based on vendor format.
   */
  private static List<String> extractMemberIds(String json, String vendor) {
    List<String> ids = new ArrayList<>();
    if (json == null) return ids;

    if ("alfresco".equals(vendor)) {
      // Alfresco: {"list":{"entries":[{"entry":{"id":"user1","memberType":"PERSON"}},...]}}
      extractFieldValues(json, "id", ids, 50);
    } else if ("sharepoint".equals(vendor)) {
      // SharePoint: {"value":[{"Id":1,"LoginName":"i:0#.w|domain\\user","Title":"User Name"}]}
      extractFieldValues(json, "LoginName", ids, 50);
    } else if ("nuxeo".equals(vendor)) {
      // Nuxeo: {"id":"groupName","members":["user1","user2",...]} or entries with id
      extractFieldValues(json, "id", ids, 50);
      if (ids.isEmpty()) {
        // Try to extract from "members" array
        extractArrayStringValues(json, "members", ids, 50);
      }
    } else {
      // Generic: try common field names
      extractFieldValues(json, "id", ids, 50);
      if (ids.isEmpty()) extractFieldValues(json, "userId", ids, 50);
      if (ids.isEmpty()) extractFieldValues(json, "Id", ids, 50);
      if (ids.isEmpty()) extractFieldValues(json, "loginName", ids, 50);
      if (ids.isEmpty()) extractFieldValues(json, "name", ids, 50);
    }
    return ids;
  }

  /**
   * Simple JSON field value extractor — finds all occurrences of
   * "fieldName":"value" in the JSON string and collects the values.
   */
  private static void extractFieldValues(String json, String fieldName, List<String> results, int maxResults) {
    String pattern = "\"" + fieldName + "\"";
    int searchFrom = 0;
    while (results.size() < maxResults) {
      int idx = json.indexOf(pattern, searchFrom);
      if (idx < 0) break;

      int colonIdx = json.indexOf(':', idx + pattern.length());
      if (colonIdx < 0) break;

      int valueStart = colonIdx + 1;
      while (valueStart < json.length() && json.charAt(valueStart) == ' ') valueStart++;
      if (valueStart >= json.length()) break;

      if (json.charAt(valueStart) == '"') {
        int valueEnd = valueStart + 1;
        while (valueEnd < json.length()) {
          if (json.charAt(valueEnd) == '"' && json.charAt(valueEnd - 1) != '\\') {
            String value = json.substring(valueStart + 1, valueEnd);
            if (!value.isEmpty()) {
              results.add(value);
            }
            break;
          }
          valueEnd++;
        }
        searchFrom = valueEnd + 1;
      } else {
        // Numeric or boolean value — skip for ID extraction
        searchFrom = valueStart + 1;
      }
    }
  }

  /**
   * Extract string values from a JSON array field: "fieldName":["val1","val2",...]
   */
  private static void extractArrayStringValues(String json, String fieldName, List<String> results, int maxResults) {
    String pattern = "\"" + fieldName + "\"";
    int idx = json.indexOf(pattern);
    if (idx < 0) return;

    int colonIdx = json.indexOf(':', idx + pattern.length());
    if (colonIdx < 0) return;

    int arrStart = json.indexOf('[', colonIdx);
    if (arrStart < 0) return;

    int arrEnd = json.indexOf(']', arrStart);
    if (arrEnd < 0) return;

    String arrContent = json.substring(arrStart + 1, arrEnd);
    int searchFrom = 0;
    while (results.size() < maxResults) {
      int qStart = arrContent.indexOf('"', searchFrom);
      if (qStart < 0) break;
      int qEnd = arrContent.indexOf('"', qStart + 1);
      if (qEnd < 0) break;
      String val = arrContent.substring(qStart + 1, qEnd);
      if (!val.isEmpty()) {
        results.add(val);
      }
      searchFrom = qEnd + 1;
    }
  }

  /**
   * HTTP GET for testing — returns the response body as a string.
   */
  private static String testHttpGet(String urlStr, String basicAuth) throws Exception {
    URL url = new URL(urlStr);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(15000);
    conn.setReadTimeout(30000);
    conn.setRequestProperty("Authorization", basicAuth);
    conn.setRequestProperty("Accept", "application/json");

    int status = conn.getResponseCode();
    if (status >= 400) {
      String errorBody = "";
      try {
        InputStream es = conn.getErrorStream();
        if (es != null) {
          BufferedReader br = new BufferedReader(new InputStreamReader(es, StandardCharsets.UTF_8));
          StringBuilder sb = new StringBuilder();
          String line;
          while ((line = br.readLine()) != null) sb.append(line);
          errorBody = sb.toString();
        }
      } catch (Exception ignored) {}
      throw new RuntimeException("HTTP " + status + " " + conn.getResponseMessage()
          + (errorBody.isEmpty() ? "" : " — " + truncate(errorBody, 200)));
    }

    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder();
    String line;
    while ((line = br.readLine()) != null) sb.append(line);
    return sb.toString();
  }

  private static String truncate(String s, int maxLen) {
    if (s == null) return "";
    return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
  }

  /**
   * Set up trust-all SSL for HTTPS test connections.
   */
  private static boolean testSslInitialized = false;
  private static void setupTestSslTrust() throws Exception {
    if (testSslInitialized) return;
    TrustManager[] trustAll = new TrustManager[] {
      new X509TrustManager() {
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        public void checkClientTrusted(X509Certificate[] certs, String t) { }
        public void checkServerTrusted(X509Certificate[] certs, String t) { }
      }
    };
    SSLContext sc = SSLContext.getInstance("TLS");
    sc.init(null, trustAll, new SecureRandom());
    HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
    HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
    testSslInitialized = true;
  }

}