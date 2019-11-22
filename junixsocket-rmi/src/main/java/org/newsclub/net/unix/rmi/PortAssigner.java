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

import java.io.IOException;
import java.rmi.Remote;
import java.util.stream.IntStream;

/**
 * The {@link PortAssigner} assigns and keeps track of anonymous ports. This feature is to be used
 * by {@link AFUNIXRMISocketFactory} only.
 * 
 * @author Christian Kohlschütter
 */
public interface PortAssigner extends Remote {
  /**
   * Registers a new anonymous port and returns it. When the port is not required anymore, it must
   * be returned via {@link #returnPort(int)}.
   * 
   * @return The new port.
   * @throws IOException if the operation fails.
   */
  int newPort() throws IOException;

  /**
   * Returns a previously registered port. No error is thrown if the given port has not been
   * assigned before.
   * 
   * @param port The port.
   * @throws IOException if the operation fails.
   */
  void returnPort(int port) throws IOException;

  /**
   * Returns a stream of open ports.
   * 
   * @return A sequence of open ports.
   * @throws IOException
   */
  IntStream openPorts() throws IOException;
}
