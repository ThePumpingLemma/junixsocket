/**
 * junixsocket
 *
 * Copyright 2009-2019 Christian Kohlschütter
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.rmi.AlreadyBoundException;
import java.rmi.NotBoundException;
import java.rmi.registry.Registry;
import java.rmi.server.RMISocketFactory;
import java.rmi.server.RemoteObject;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class RemoteFileDescriptorTest {
  private static final String TEST_SERVICE_NAME = RemoteFileDescriptorTest.class.getName();

  private static final byte[] HELLO_WORLD = "Hello World :-)\n".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] SMILEY = ":-)\n".getBytes(StandardCharsets.US_ASCII);

  private static AFUNIXNaming NAMING;
  private static RemoteFileDescriptorTestServiceImpl TEST_SERVICE;

  @BeforeAll
  public static void setupClass() throws IOException, AlreadyBoundException {
    NAMING = AFUNIXNaming.getInstance();

    // Create registry
    final Registry registry = NAMING.createRegistry();

    // Create and bind service
    TEST_SERVICE = new RemoteFileDescriptorTestServiceImpl(NAMING.getSocketFactory());
    registry.bind(TEST_SERVICE_NAME, RemoteObject.toStub(TEST_SERVICE));
  }

  @AfterAll
  public static void tearDownClass() throws IOException, NotBoundException {
    TEST_SERVICE.close();
    NAMING.shutdownRegistry();
  }

  public void testServiceProxy() throws Exception {
    RemoteFileDescriptorTestService svc = (RemoteFileDescriptorTestService) NAMING.getRegistry()
        .lookup(TEST_SERVICE_NAME);
    assertTrue(Proxy.isProxyClass(svc.getClass()));
  }

  @Test
  public void testRemoteStdout() throws Exception {
    RemoteFileDescriptorTestService svc = (RemoteFileDescriptorTestService) NAMING.getRegistry()
        .lookup(TEST_SERVICE_NAME);

    final FileDescriptor fd = svc.stdout().getFileDescriptor();
    try (FileOutputStream fos = new FileOutputStream(fd)) {
//      fos.write(SMILEY);
      fos.flush();
    }
  }

  @Test
  public void testWriteAndReadHello() throws Exception {
    RemoteFileDescriptorTestService svc = (RemoteFileDescriptorTestService) NAMING.getRegistry()
        .lookup(TEST_SERVICE_NAME);

    try (FileOutputStream fos = svc.output().toFileOutputStream()) {
      fos.write(HELLO_WORLD);
    }
    svc.verifyContents(HELLO_WORLD);

    try (FileInputStream fin = svc.input(12).toFileInputStream()) {
      byte[] data = fin.readAllBytes();
      assertArrayEquals(SMILEY, data);
    }

    try (NaiveFileInputStreamRemote rfis = svc.naiveInputStreamRemote();
        FileInputStream fin = rfis.getRemoteFileDescriptor().toFileInputStream()) {
      assertEquals('H', rfis.read());
      assertEquals('e', fin.read());
      assertEquals('l', fin.read());
      assertEquals('l', fin.read());
      fin.close(); // it's OK to close the remote file descriptor we received via RMI
      assertEquals('o', rfis.read());
    }
  }

  @Test
  public void testFindSocketFactory() throws IOException, NotBoundException {
    RemoteFileDescriptorTestService svc = (RemoteFileDescriptorTestService) NAMING.getRegistry()
        .lookup(TEST_SERVICE_NAME);

    RMISocketFactory factory = RemoteObjectUtil.findSocketFactory(svc);
    assertNotNull(factory);
    assertEquals(NAMING.getSocketFactory(), factory);
  }

  @Test
  public void testReadWrite() throws IOException, NotBoundException {
    RemoteFileDescriptorTestService svc = (RemoteFileDescriptorTestService) NAMING.getRegistry()
        .lookup(TEST_SERVICE_NAME);

    byte[] expected = new byte[5000];
    new Random().nextBytes(expected);

    try (FileOutputStream fos = svc.output().toFileOutputStream()) {
      fos.write(expected);
    }

    byte[] actual;
    try (FileInputStream fin = svc.input().toFileInputStream()) {
      actual = fin.readAllBytes();
    }
    assertArrayEquals(expected, actual);

    try (NaiveFileInputStreamRemote fin = svc.naiveInputStreamRemote()) {
      actual = fin.readAllBytes();
    }
    assertArrayEquals(expected, actual);
  }
}
