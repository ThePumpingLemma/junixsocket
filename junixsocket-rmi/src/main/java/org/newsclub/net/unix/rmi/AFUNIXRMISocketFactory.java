/*
 * junixsocket
 *
 * Copyright 2009-2021 Christian Kohlschütter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.newsclub.net.unix.rmi;

import java.io.Closeable;
import java.io.Externalizable;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.NotBoundException;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.rmi.server.RMISocketFactory;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.newsclub.net.unix.AFUNIXSocketCredentials;
import org.newsclub.net.unix.rmi.ShutdownHookSupport.ShutdownHook;

/**
 * An {@link RMISocketFactory} that supports {@link AFUNIXSocket}s.
 * 
 * @author Christian Kohlschütter
 */
public class AFUNIXRMISocketFactory extends RMISocketFactory implements Externalizable, Closeable {
  static final String DEFAULT_SOCKET_FILE_PREFIX = "";
  static final String DEFAULT_SOCKET_FILE_SUFFIX = ".rmi";

  private static final long serialVersionUID = 1L;

  private RMIClientSocketFactory defaultClientFactory;
  private RMIServerSocketFactory defaultServerFactory;

  private File socketDir;
  private AFUNIXNaming naming;

  private String socketPrefix;
  private String socketSuffix;

  private AFUNIXRMIService rmiService = null;

  private final Map<HostAndPort, AFUNIXSocketCredentials> credentials = new HashMap<>();
  private final Map<Integer, AFUNIXServerSocket> openServerSockets = new HashMap<>();
  private final Set<AFUNIXSocket> openSockets = new HashSet<>();

  /**
   * Constructor required per definition.
   * 
   * @see RMISocketFactory
   */
  public AFUNIXRMISocketFactory() {
    super();
    closeUponRuntimeShutdown();
  }

  public AFUNIXRMISocketFactory(final AFUNIXNaming naming, final File socketDir)
      throws IOException {
    this(naming, socketDir, DefaultRMIClientSocketFactory.getInstance(),
        DefaultRMIServerSocketFactory.getInstance());
  }

  public AFUNIXRMISocketFactory(final AFUNIXNaming naming, final File socketDir,
      final RMIClientSocketFactory defaultClientFactory,
      final RMIServerSocketFactory defaultServerFactory) throws IOException {
    this(naming, socketDir, defaultClientFactory, defaultServerFactory, null, null);
  }

  public AFUNIXRMISocketFactory(final AFUNIXNaming naming, final File socketDir,
      final RMIClientSocketFactory defaultClientFactory,
      final RMIServerSocketFactory defaultServerFactory, final String socketPrefix,
      final String socketSuffix) throws IOException {
    super();
    this.naming = naming;
    this.socketDir = socketDir;
    this.defaultClientFactory = defaultClientFactory;
    this.defaultServerFactory = defaultServerFactory;
    this.socketPrefix = socketPrefix == null ? DEFAULT_SOCKET_FILE_PREFIX : socketPrefix;
    this.socketSuffix = socketSuffix == null ? DEFAULT_SOCKET_FILE_SUFFIX : socketSuffix;

    closeUponRuntimeShutdown();
  }

  private boolean isPlainFileSocket() {
    return (naming.getRegistryPort() == AFUNIXRMIPorts.PLAIN_FILE_SOCKET);
  }

  // only to be called from the constructor
  private void closeUponRuntimeShutdown() {
    ShutdownHookSupport.addWeakShutdownHook(new ShutdownHook() {

      @Override
      public void onRuntimeShutdown(Thread thread) {
        try {
          close();
        } catch (IOException e) {
          // ignore
        }
      }
    });
  }

  @Override
  public int hashCode() {
    return socketDir == null ? super.hashCode() : socketDir.hashCode();
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof AFUNIXRMISocketFactory)) {
      return false;
    }
    AFUNIXRMISocketFactory sf = (AFUNIXRMISocketFactory) other;
    return sf.socketDir.equals(socketDir);
  }

  @SuppressWarnings("resource")
  @Override
  public Socket createSocket(String host, int port) throws IOException {
    final RMIClientSocketFactory cf = defaultClientFactory;
    if (cf != null && port < AFUNIXRMIPorts.AF_PORT_BASE) {
      return cf.createSocket(host, port);
    }

    final AFUNIXSocketAddress addr = new AFUNIXSocketAddress(getFile(port), port);

    final AFUNIXSocket socket = AFUNIXSocket.newInstance();
    socket.connect(addr);
    AFUNIXSocketCredentials creds = socket.getPeerCredentials();

    final HostAndPort hap = new HostAndPort(host, port);
    synchronized (credentials) {
      if (credentials.put(hap, creds) != null) {
        // unexpected
      }
    }

    synchronized (openSockets) {
      openSockets.add(socket);
    }
    socket.addCloseable(new Closeable() {
      @Override
      public void close() throws IOException {
        synchronized (openSockets) {
          openSockets.remove(socket);
        }

        synchronized (credentials) {
          credentials.remove(hap);
        }
      }
    });
    return socket;
  }

  public File getSocketDir() {
    return socketDir;
  }

  File getFile(int port) {
    if (isPlainFileSocket()) {
      return getSocketDir();
    } else {
      return new File(socketDir, socketPrefix + port + socketSuffix);
    }
  }

  boolean hasSocketFile(int port) {
    return getFile(port).exists();
  }

  @Override
  public void close() throws IOException {
    synchronized (AFUNIXNaming.class) {
      credentials.clear();

      rmiService = null;
      closeServerSockets();
      closeSockets();
    }
  }

  private AFUNIXRMIService getRmiService() throws IOException {
    synchronized (AFUNIXNaming.class) {
      if (rmiService == null) {
        try {
          rmiService = naming.getRMIService();
        } catch (NotBoundException e) {
          throw (IOException) new IOException(e.getMessage()).initCause(e);
        }
      }
      return rmiService;
    }
  }

  protected int newPort() throws IOException {
    return getRmiService().newPort();
  }

  protected void returnPort(int port) throws IOException {
    getRmiService().returnPort(port);
  }

  @SuppressWarnings("resource")
  @Override
  public ServerSocket createServerSocket(int port) throws IOException {
    if (port == 0) {
      port = newPort();
      final AFUNIXSocketAddress addr = new AFUNIXSocketAddress(getFile(port), port);
      final AnonymousServerSocket ass = new AnonymousServerSocket(port);
      ass.setDeleteOnClose(true);
      ass.bind(addr);

      if (port >= AFUNIXRMIPorts.AF_PORT_BASE) {
        ass.addCloseable(new ServerSocketCloseable(ass, port));
      }
      return ass;
    }

    final RMIServerSocketFactory sf = defaultServerFactory;
    if (sf != null && port < AFUNIXRMIPorts.AF_PORT_BASE) {
      return sf.createServerSocket(port);
    }

    final AFUNIXSocketAddress addr = new AFUNIXSocketAddress(getFile(port), port);
    AFUNIXServerSocket socket = AFUNIXServerSocket.newInstance();
    socket.setDeleteOnClose(true);
    socket.setReuseAddress(true);
    socket.bind(addr);
    socket.addCloseable(new ServerSocketCloseable(socket, port));
    return socket;
  }

  private void closeServerSockets() throws IOException {
    Map<Integer, AFUNIXServerSocket> map;
    synchronized (openServerSockets) {
      map = new HashMap<>(openServerSockets);
    }
    IOException ex = null;
    for (Map.Entry<Integer, AFUNIXServerSocket> en : map.entrySet()) {
      try {
        en.getValue().close();
      } catch (ShutdownException e) {
        // ignore
      } catch (IOException e) {
        if (ex == null) {
          ex = e;
        } else {
          ex.addSuppressed(e);
        }
      }
    }
    synchronized (openServerSockets) {
      openServerSockets.clear();
    }
    if (ex != null) {
      throw ex;
    }
  }

  private void closeSockets() {
    Set<AFUNIXSocket> set;
    synchronized (openSockets) {
      set = new HashSet<>(openSockets);
    }
    for (AFUNIXSocket socket : set) {
      try {
        socket.close();
      } catch (IOException e) {
        // ignore
      }
    }
    synchronized (openSockets) {
      openSockets.clear();
    }
  }

  private final class ServerSocketCloseable implements Closeable {
    private final int port;

    @SuppressWarnings("resource")
    private ServerSocketCloseable(AFUNIXServerSocket socket, int port) {
      this.port = port;
      synchronized (openServerSockets) {
        openServerSockets.put(port, socket);
      }
    }

    @SuppressWarnings("resource")
    @Override
    public void close() throws IOException {
      synchronized (openServerSockets) {
        openServerSockets.remove(port);
      }
    }
  }

  @Override
  public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
    socketDir = new File(in.readUTF());
    int port = in.readInt();
    naming = AFUNIXNaming.getInstance(socketDir, port);

    defaultClientFactory = (RMIClientSocketFactory) in.readObject();
    defaultServerFactory = (RMIServerSocketFactory) in.readObject();

    socketPrefix = in.readUTF();
    socketSuffix = in.readUTF();
  }

  @Override
  public void writeExternal(ObjectOutput out) throws IOException {
    out.writeUTF(socketDir.getAbsolutePath());
    out.writeInt(naming.getRegistryPort());

    out.writeObject(defaultClientFactory);
    out.writeObject(defaultServerFactory);

    out.writeUTF(socketPrefix);
    out.writeUTF(socketSuffix);
  }

  private final class AnonymousServerSocket extends AFUNIXServerSocket {
    private final int returnPort;

    protected AnonymousServerSocket(int returnPort) throws IOException {
      super();
      this.returnPort = returnPort;
      setReuseAddress(true);
    }

    @Override
    public void close() throws IOException {
      super.close();
      returnPort(returnPort);
    }
  }

  @Override
  public String toString() {
    return super.toString() + //
        "[path=" + socketDir + //
        (isPlainFileSocket() ? "" : //
            ";prefix=" + socketPrefix + ";suffix=" + socketSuffix) + "]";
  }

  AFUNIXSocketCredentials peerCredentialsFor(RemotePeerInfo data) {
    synchronized (credentials) {
      return credentials.get(new HostAndPort(data.host, data.port));
    }
  }

  private static final class HostAndPort {
    final String hostname;
    final int port;

    private HostAndPort(String hostname, int port) {
      this.hostname = hostname;
      this.port = port;
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((hostname == null) ? 0 : hostname.hashCode());
      result = prime * result + port;
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof HostAndPort)) {
        return false;
      }
      HostAndPort other = (HostAndPort) obj;
      if (hostname == null) {
        if (other.hostname != null) {
          return false;
        }
      } else if (!hostname.equals(other.hostname)) {
        return false;
      }

      return port == other.port;
    }
  }

  public boolean isLocalServer(int port) {
    if (port < AFUNIXRMIPorts.AF_PORT_BASE) {
      return false;
    }
    synchronized (openServerSockets) {
      return openServerSockets.containsKey(port);
    }
  }
}
