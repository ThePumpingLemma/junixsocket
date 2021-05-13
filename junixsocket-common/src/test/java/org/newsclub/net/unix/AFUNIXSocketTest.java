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
package org.newsclub.net.unix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * Tests some otherwise uncovered methods of {@link AFUNIXSocket}.
 * 
 * @author Christian Kohlschütter
 */
public class AFUNIXSocketTest {
  @Test
  public void testMain() throws Exception {
    AFUNIXSocket.main(new String[0]);
  }

  @Test
  public void testVersion() throws Exception {
    assertNotEquals("", AFUNIXSocket.getVersion());
    // see junixsocket-rmi JunixSocketVersionTest
  }

  @Test
  public void testSupported() throws Exception {
    assertTrue(AFUNIXSocket.isSupported());
  }

  @Test
  public void testLoadedLibrary() throws Exception {
    assertNotEquals("", AFUNIXSocket.getLoadedLibrary());
  }

  @Test
  public void testCloseable() throws Exception {
    AtomicBoolean closed = new AtomicBoolean(false);
    @SuppressWarnings("resource")
    Closeable closeable = new Closeable() {

      @Override
      public void close() throws IOException {
        closed.set(true);
      }
    };

    try (AFUNIXSocket socket = AFUNIXSocket.newInstance()) {
      socket.addCloseable(null); // no-op
      socket.addCloseable(closeable);
      socket.removeCloseable(null); // no-op
      socket.removeCloseable(closeable);
    }
    assertFalse(closed.get());

    try (AFUNIXSocket socket = AFUNIXSocket.newInstance()) {
      socket.addCloseable(closeable);
    }
    assertTrue(closed.get());
  }

  @Test
  public void testSupports() throws Exception {
    for (AFUNIXSocketCapability cap : AFUNIXSocketCapability.values()) {
      AFUNIXSocket.supports(cap);
    }
  }

  @Test
  public void testConnectBadArguments() throws Exception {
    try (AFUNIXSocket socket = AFUNIXSocket.newInstance()) {
      assertThrows(IllegalArgumentException.class, () -> {
        socket.connect(null);
      });
      assertThrows(IllegalArgumentException.class, () -> {
        socket.connect(new InetSocketAddress(0), -1);
      });
      assertThrows(IllegalArgumentException.class, () -> {
        socket.connect(new InetSocketAddress(0), 0);
      });

      assertEquals("org.newsclub.net.unix.AFUNIXSocket[unconnected]", socket.toString());
    }

    try (AFUNIXSocket socket = AFUNIXSocket.newInstance()) {
      assertThrows(IllegalArgumentException.class, () -> {
        socket.connect((SocketAddress) null);
      });
      assertThrows(IllegalArgumentException.class, () -> {
        socket.connect(new InetSocketAddress("http://example.com/", 0));
      });
      assertThrows(IllegalArgumentException.class, () -> {
        socket.connect(new InetSocketAddress("", 0), -1);
      });
      assertThrows(IllegalArgumentException.class, () -> {
        // use AFUNIXSocketFactory.URIScheme
        socket.connect(new InetSocketAddress("file://", 0));
      });
      assertThrows(IllegalArgumentException.class, () -> {
        // use AFUNIXSocketFactory.URIScheme
        socket.connect(new InetSocketAddress("file://not-absolute", 0));
      });
      assertThrows(IllegalArgumentException.class, () -> {
        // use AFUNIXSocketFactory.URIScheme
        socket.connect(new InetSocketAddress("file:///", 0));
      });
    }
  }

  @Test
  public void testBindBadArguments() throws Exception {
    try (AFUNIXSocket sock = AFUNIXSocket.newInstance()) {
      assertFalse(sock.isBound());
      assertThrows(IllegalArgumentException.class, () -> {
        sock.bind(null);
      });
      assertFalse(sock.isBound());
    }
    try (AFUNIXSocket sock = AFUNIXSocket.newInstance()) {
      assertFalse(sock.isBound());
      assertThrows(IllegalArgumentException.class, () -> {
        sock.bind(new InetSocketAddress("", 0));
      });
      assertFalse(sock.isBound());
    }
  }

  @Test
  public void testReceivedFileDescriptorsUnconnected() throws Exception {
    try (AFUNIXSocket sock = AFUNIXSocket.newInstance()) {
      // We don't check socket status, so these calls are perfectly fine for unconnected sockets.
      assertNull(sock.getReceivedFileDescriptors());
      sock.clearReceivedFileDescriptors();
    }
  }

  @Test
  public void testUnconnectedSocket() throws IOException {
    try (Socket socket = AFUNIXSocket.newInstance()) {
      assertTrue(socket.getReceiveBufferSize() > 0);
      assertTrue(socket.getSendBufferSize() > 0);
    }
  }
}
