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

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * SSL socket factory that trusts all certificates. Used by JNDI for LDAPS
 * connections to servers with self-signed or untrusted certificates.
 *
 * <p>JNDI requires a public class with a static {@code getDefault()} method
 * that returns a {@link SocketFactory}. The class name is passed to JNDI via
 * the {@code java.naming.ldap.factory.socket} environment property.</p>
 *
 * <p><b>Security warning:</b> This factory disables certificate verification.
 * Use only in development, testing, or controlled network environments.
 * In production, import the LDAP server's CA certificate into the JRE trust
 * store instead.</p>
 */
public class TrustAllSSLSocketFactory extends SSLSocketFactory {

  private final SSLSocketFactory delegate;

  public TrustAllSSLSocketFactory() {
    try {
      SSLContext sc = SSLContext.getInstance("TLS");
      sc.init(null, new TrustManager[]{new X509TrustManager() {
        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
      }}, new SecureRandom());
      delegate = sc.getSocketFactory();
    } catch (Exception e) {
      throw new RuntimeException("Failed to create trust-all SSL context", e);
    }
  }

  /**
   * Required by JNDI — called via reflection to obtain the socket factory.
   */
  public static SocketFactory getDefault() {
    return new TrustAllSSLSocketFactory();
  }

  @Override
  public String[] getDefaultCipherSuites() {
    return delegate.getDefaultCipherSuites();
  }

  @Override
  public String[] getSupportedCipherSuites() {
    return delegate.getSupportedCipherSuites();
  }

  @Override
  public Socket createSocket(Socket s, String host, int port, boolean autoClose)
      throws IOException {
    return delegate.createSocket(s, host, port, autoClose);
  }

  @Override
  public Socket createSocket(String host, int port) throws IOException {
    return delegate.createSocket(host, port);
  }

  @Override
  public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
      throws IOException {
    return delegate.createSocket(host, port, localHost, localPort);
  }

  @Override
  public Socket createSocket(InetAddress host, int port) throws IOException {
    return delegate.createSocket(host, port);
  }

  @Override
  public Socket createSocket(InetAddress address, int port,
      InetAddress localAddress, int localPort) throws IOException {
    return delegate.createSocket(address, port, localAddress, localPort);
  }
}
