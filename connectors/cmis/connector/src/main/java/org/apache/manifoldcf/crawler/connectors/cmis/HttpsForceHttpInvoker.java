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

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.client.bindings.spi.http.DefaultHttpInvoker;
import org.apache.chemistry.opencmis.client.bindings.spi.http.HttpInvoker;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Output;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Response;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;

import java.math.BigInteger;
import java.util.Map;

/**
 * A custom {@link HttpInvoker} that rewrites {@code http://} URLs to
 * {@code https://} before delegating to {@link DefaultHttpInvoker}.
 *
 * <p>Many CMIS servers (Alfresco, Nuxeo, SharePoint, etc.) that sit behind
 * an HTTPS-terminating reverse proxy (nginx, Apache httpd, HAProxy, etc.)
 * still advertise internal {@code http://} URLs in their CMIS service
 * document, AtomPub feeds and JSON responses. The Apache Chemistry OpenCMIS
 * client follows these URLs verbatim, causing the reverse proxy to reject
 * the request with <em>"400 Bad Request: The plain HTTP request was sent
 * to HTTPS port"</em>.</p>
 *
 * <p>This invoker is <b>vendor-agnostic</b> &mdash; it is activated
 * automatically whenever the ManifoldCF CMIS connection is configured
 * with {@code protocol=https} (see
 * {@link CmisRepositoryConnector} &rarr;
 * {@code SessionParameter.HTTP_INVOKER_CLASS}).</p>
 */
public class HttpsForceHttpInvoker implements HttpInvoker {

  private final DefaultHttpInvoker delegate;

  public HttpsForceHttpInvoker() {
    this.delegate = new DefaultHttpInvoker();
  }

  /**
   * Rewrites an http:// URL to https://, preserving everything else.
   */
  private static UrlBuilder forceHttps(UrlBuilder url) {
    if (url == null) {
      return null;
    }
    String original = url.toString();
    if (original.startsWith("http://")) {
      return new UrlBuilder(original.replaceFirst("^http://", "https://"));
    }
    return url;
  }

  @Override
  public Response invokeGET(UrlBuilder url, BindingSession session) {
    return delegate.invokeGET(forceHttps(url), session);
  }

  @Override
  public Response invokeGET(UrlBuilder url, BindingSession session,
      BigInteger offset, BigInteger length) {
    return delegate.invokeGET(forceHttps(url), session, offset, length);
  }

  @Override
  public Response invokePOST(UrlBuilder url, String contentType,
      Output writer, BindingSession session) {
    return delegate.invokePOST(forceHttps(url), contentType, writer, session);
  }

  @Override
  public Response invokePUT(UrlBuilder url, String contentType,
      Map<String, String> headers, Output writer, BindingSession session) {
    return delegate.invokePUT(forceHttps(url), contentType, headers, writer, session);
  }

  @Override
  public Response invokeDELETE(UrlBuilder url, BindingSession session) {
    return delegate.invokeDELETE(forceHttps(url), session);
  }
}
