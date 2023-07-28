/*
 * junixsocket
 *
 * Copyright 2009-2023 Christian Kohlschütter
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
package org.newsclub.net.unix.darwin.system;

import java.io.FileDescriptor;
import java.net.SocketException;

import org.newsclub.net.unix.AFSYSTEMSocketAddress;
import org.newsclub.net.unix.AFSocketImpl;

final class AFSYSTEMSocketImpl extends AFSocketImpl<AFSYSTEMSocketAddress> {
  AFSYSTEMSocketImpl(FileDescriptor fdObj) {
    super(AFSYSTEMSelectorProvider.AF_SYSTEM, fdObj);
  }

  @Override
  public Object getOption(int optID) throws SocketException {
    return getOptionLenient(optID);
  }

  @Override
  public void setOption(int optID, Object value) throws SocketException {
    setOptionLenient(optID, value);
  }
}
