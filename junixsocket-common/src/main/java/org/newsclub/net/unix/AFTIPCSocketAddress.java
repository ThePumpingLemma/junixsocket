/*
 * junixsocket
 *
 * Copyright 2009-2022 Christian Kohlschütter
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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * An {@link AFSocketAddress} for TIPC sockets.
 *
 * The TIPC socket API provides three different address types:
 * <ul>
 * <li><em>Service Address.</em>
 * <p>
 * This address type consists of a 32-bit service type identifier and a 32-bit service instance
 * identifier.
 * </p>
 * <p>
 * The type identifier is typically determined and hard-coded by the user application programmer,
 * but its value may have to be coordinated with other applications which might be present in the
 * same cluster. The instance identifier is often calculated by the program, based on application
 * specific criteria.
 * </p>
 * <p>
 * Typical invocation: {@link #ofService(int, int)}.
 * </p>
 * </li>
 * <li><em>Service Range.</em>
 * <p>
 * This address type represents a range of service addresses of the same type and with instances
 * between a lower and an upper range limit.
 * </p>
 * <p>
 * By binding a socket to this address type one can make it represent many instances, something
 * which has proved useful in many cases. This address type is also used as multicast address.
 * </p>
 * <p>
 * Typical invocation: {@link #ofServiceRange(int, int, int)}.
 * </p>
 * </li>
 * <li><em>Socket Address.</em>
 * <p>
 * This address is a reference to a specific socket in the cluster.
 * </p>
 * <p>
 * It contains a 32-bit port number and a 32-bit node hash number. The port number is generated by
 * the system when the socket is created, and the node hash is generated from the corresponding node
 * identity.
 * </p>
 * <p>
 * An address of this type can be used for connecting or for sending messages in the same way as
 * service addresses can be used, but is only valid as long as the referenced socket exists.
 * </p>
 * <p>
 * Typical invocation: {@link #ofSocket(int, int)}.
 * </p>
 * </li>
 * </ul>
 * <p>
 * When binding a socket to a service address or address range, the visibility scope of the binding
 * must be indicated. There are two options, {@link Scope#SCOPE_NODE} if the user only wants node
 * local visibility, and {@link Scope#SCOPE_CLUSTER} if he wants cluster global visibility. There
 * are almost no limitations to how sockets can be bound to service addresses: one socket can be
 * bound to many addresses or ranges, and many sockets can be bound to the same address or range.
 * </p>
 * <p>
 * The service types 0 through 63 are however reserved for system internal use, and are not
 * available for user space applications.
 * </p>
 * <p>
 * When sending a message by service address the sender may indicate a lookup domain, also called
 * lookup scope. This is a node hash number, limiting the set of eligible destination sockets to the
 * indicated node. If this value is zero, all matching sockets in the whole cluster, as visible from
 * the source node, are eligible.
 * </p>
 *
 * @author Christian Kohlschütter (documentation credits to Jon Maloy and the TIPC team).
 */
public final class AFTIPCSocketAddress extends AFSocketAddress {
  private static final long serialVersionUID = 1L;

  private static final Pattern PAT_TIPC_URI_HOST_AND_PORT = Pattern.compile(
      "^((?:(?:(?<scope>cluster|node|default|[0-9a-fx]+)\\-)?(?<type>service|service-range|socket)\\.)|"
          + "(?<scope2>cluster|node|default|[0-9a-fx]+)\\-(?<type2>[0-9a-fx]+)\\.)?"
          + "(?<a>[0-9a-fx]+)\\.(?<b>[0-9a-fx]+)(?:\\.(?<c>[0-9a-fx]+))?(?:\\:(?<javaPort>[0-9]+))?$");

  /**
   * The "topology" service name type.
   */
  public static final int TIPC_TOP_SRV = 1;

  /**
   * The lowest user-publishable name type.
   */
  public static final int TIPC_RESERVED_TYPES = 64;

  private static AFAddressFamily<AFTIPCSocketAddress> afTipc;

  /**
   * The TIPC address type.
   *
   * @author Christian Kohlschütter
   */
  @NonNullByDefault
  public static final class AddressType extends NamedInteger {
    private static final long serialVersionUID = 1L;

    /**
     * Describes a TIPC "service range" address.
     */
    public static final AddressType SERVICE_RANGE;

    /**
     * Describes a TIPC "service" address.
     */
    public static final AddressType SERVICE_ADDR;

    /**
     * Describes a TIPC "socket" address.
     */
    public static final AddressType SOCKET_ADDR;

    private static final @NonNull AddressType[] VALUES = init(new @NonNull AddressType[] {
        SERVICE_RANGE = new AddressType("SERVICE_RANGE", 1, //
            (a, b, c) -> formatTIPCInt(a) + "@" + formatTIPCInt(b) + "-" + formatTIPCInt(c)), //
        SERVICE_ADDR = new AddressType("SERVICE_ADDR", 2, //
            (a, b, c) -> formatTIPCInt(a) + "@" + formatTIPCInt(b) + (c == 0 ? "" : ":"
                + formatTIPCInt(c))), //
        SOCKET_ADDR = new AddressType("SOCKET_ADDR", 3, //
            (a, b, c) -> formatTIPCInt(a) + "@" + formatTIPCInt(b) + (c == 0 ? "" : ":"
                + formatTIPCInt(c))), //
    });

    /**
     * The provider of a debug string.
     */
    private final DebugStringProvider ds;

    private AddressType(int id) {
      super(id);
      this.ds = (a, b, c) -> ":" + Integer.toUnsignedString(a) + ":" + Integer.toUnsignedString(b)
          + ":" + Integer.toUnsignedString(c);
    }

    private AddressType(String name, int id, DebugStringProvider ds) {
      super(name, id);
      this.ds = ds;
    }

    static AddressType ofValue(int v) {
      return ofValue(VALUES, AddressType::new, v);
    }

    @FunctionalInterface
    interface DebugStringProvider extends Serializable {
      String toDebugString(int a, int b, int c);
    }

    /**
     * Formats an integer as an unsigned, zero-padded 32-bit hexadecimal number.
     *
     * @param i The number.
     * @return The string.
     */
    @SuppressWarnings("null")
    public static String formatTIPCInt(int i) {
      return String.format(Locale.ENGLISH, "0x%08x", (i & 0xFFFFFFFFL));
    }

    private String toDebugString(Scope scope, int a, int b, int c) {
      if (this == SOCKET_ADDR && scope.equals(Scope.SCOPE_NOT_SPECIFIED)) {
        return name() + "(" + value() + ");" + ds.toDebugString(a, b, c);
      } else {
        return name() + "(" + value() + ");" + scope + ":" + ds.toDebugString(a, b, c);
      }
    }
  }

  /**
   * The TIPC visibility scope.
   *
   * @author Christian Kohlschütter
   */
  @NonNullByDefault
  public static final class Scope extends NamedInteger {
    private static final long serialVersionUID = 1L;

    /**
     * Cluster-wide scope.
     */
    public static final Scope SCOPE_CLUSTER;

    /**
     * Node-only scope.
     */
    public static final Scope SCOPE_NODE;

    /**
     * Scope not specified (for example, when using socket addresses).
     */
    public static final Scope SCOPE_NOT_SPECIFIED;

    private static final @NonNull Scope[] VALUES = init(new @NonNull Scope[] {
        SCOPE_NOT_SPECIFIED = new Scope("SCOPE_NOT_SPECIFIED", 0), //
        SCOPE_CLUSTER = new Scope("SCOPE_CLUSTER", 2), //
        SCOPE_NODE = new Scope("SCOPE_NODE", 3), //
    });

    private Scope(int id) {
      super(id);
    }

    private Scope(String name, int id) {
      super(name, id);
    }

    /**
     * Returns a {@link Scope} instance given an integer value.
     *
     * @param v The scope value.
     * @return The {@link Scope} instance.
     */
    public static Scope ofValue(int v) {
      return ofValue(VALUES, Scope::new, v);
    }
  }

  private AFTIPCSocketAddress(int port, final byte[] socketAddress, ByteBuffer nativeAddress)
      throws SocketException {
    super(port, socketAddress, nativeAddress, addressFamily());
  }

  /**
   * Returns an {@link AFTIPCSocketAddress} that refers to a given service type and instance, using
   * the given scope.
   *
   * @param scope The address scope.
   * @param type The service type (0-63 are reserved).
   * @param instance The service instance ID.
   * @return A corresponding {@link AFTIPCSocketAddress} instance.
   * @throws SocketException if the operation fails.
   */
  public static AFTIPCSocketAddress ofService(Scope scope, int type, int instance)
      throws SocketException {
    return ofService(scope, type, instance, 0);
  }

  /**
   * Returns an {@link AFTIPCSocketAddress} that refers to a given service type and instance,
   * implicitly using cluster scope ({@link Scope#SCOPE_CLUSTER}).
   *
   * @param type The service type (0-63 are reserved).
   * @param instance The service instance ID.
   * @return A corresponding {@link AFTIPCSocketAddress} instance.
   * @throws SocketException if the operation fails.
   */
  public static AFTIPCSocketAddress ofService(int type, int instance) throws SocketException {
    return ofService(Scope.SCOPE_CLUSTER, type, instance, 0);
  }

  /**
   * Returns an {@link AFTIPCSocketAddress} that refers to a given service type and instance, using
   * the given scope and the given lookup domain.
   *
   * @param scope The address scope.
   * @param type The service type (0-63 are reserved).
   * @param instance The service instance ID.
   * @param domain The lookup domain. 0 indicates cluster global lookup, otherwise a node hash,
   *          indicating that lookup should be performed only on that node
   * @return A corresponding {@link AFTIPCSocketAddress} instance.
   * @throws SocketException if the operation fails.
   */
  public static AFTIPCSocketAddress ofService(Scope scope, int type, int instance, int domain)
      throws SocketException {
    return ofService(0, scope, type, instance, domain);
  }

  /**
   * Returns an {@link AFTIPCSocketAddress} that refers to a given service type and instance, using
   * the given scope and the given lookup domain. A Java-only "IP port number" is stored along the
   * instance for compatibility reasons.
   *
   * @param javaPort The emulated "port" number (not part of TIPC).
   * @param scope The address scope.
   * @param type The service type (0-63 are reserved).
   * @param instance The service instance ID.
   * @param domain The lookup domain. 0 indicates cluster global lookup, otherwise a node hash,
   *          indicating that lookup should be performed only on that node
   * @return A corresponding {@link AFTIPCSocketAddress} instance.
   * @throws SocketException if the operation fails.
   */
  public static AFTIPCSocketAddress ofService(int javaPort, Scope scope, int type, int instance,
      int domain) throws SocketException {
    return resolveAddress(toBytes(AddressType.SERVICE_ADDR, scope, type, instance, domain),
        javaPort, addressFamily());
  }

  /**
   * Returns an {@link AFTIPCSocketAddress} that refers to a given service range type and instance
   * boundaries (lower/upper values), using the given scope.
   *
   * @param scope The address scope.
   * @param type The service type (0-63 are reserved).
   * @param lower Lower end of service instance ID range.
   * @param upper Upper end of service instance ID range.
   * @return A corresponding {@link AFTIPCSocketAddress} instance.
   * @throws SocketException if the operation fails.
   */
  public static AFTIPCSocketAddress ofServiceRange(Scope scope, int type, int lower, int upper)
      throws SocketException {
    return ofServiceRange(0, scope, type, lower, upper);
  }

  /**
   * Returns an {@link AFTIPCSocketAddress} that refers to a given service range type and instance
   * boundaries (lower/upper values), implicitly using cluster scope ({@link Scope#SCOPE_CLUSTER}).
   *
   * @param type The service type (0-63 are reserved).
   * @param lower Lower end of service instance ID range.
   * @param upper Upper end of service instance ID range.
   * @return A corresponding {@link AFTIPCSocketAddress} instance.
   * @throws SocketException if the operation fails.
   */
  public static AFTIPCSocketAddress ofServiceRange(int type, int lower, int upper)
      throws SocketException {
    return ofServiceRange(0, Scope.SCOPE_CLUSTER, type, lower, upper);
  }

  /**
   * Returns an {@link AFTIPCSocketAddress} that refers to a given service range type and instance
   * boundaries (lower/upper values), implicitly using cluster scope ({@link Scope#SCOPE_CLUSTER}).
   * A Java-only "IP port number" is stored along the instance for compatibility reasons.
   *
   * @param javaPort The emulated "port" number (not part of TIPC).
   * @param scope The address scope.
   * @param type The service type (0-63 are reserved).
   * @param lower Lower end of service instance ID range.
   * @param upper Upper end of service instance ID range.
   * @return A corresponding {@link AFTIPCSocketAddress} instance.
   * @throws SocketException if the operation fails.
   */
  public static AFTIPCSocketAddress ofServiceRange(int javaPort, Scope scope, int type, int lower,
      int upper) throws SocketException {
    return resolveAddress(toBytes(AddressType.SERVICE_RANGE, scope, type, lower, upper), javaPort,
        addressFamily());
  }

  /**
   * Returns an {@link AFTIPCSocketAddress} that refers to a given TIPC socket address (i.e.,
   * referring to a particular socket instance instead of a service address).
   *
   * @param ref 32-bit port reference ID (not to be confused with the
   *          {@link InetSocketAddress#getPort()} port).
   * @param node Node hash number (can be used as lookup domain with
   *          {@link #ofService(Scope, int, int, int)}).
   * @return A corresponding {@link AFTIPCSocketAddress} instance.
   * @throws SocketException if the operation fails.
   */
  public static AFTIPCSocketAddress ofSocket(int ref, int node) throws SocketException {
    return ofSocket(0, ref, node);
  }

  /**
   * Returns an {@link AFTIPCSocketAddress} that refers to a given TIPC socket address (i.e.,
   * referring to a particular socket instance instead of a service address). A Java-only "IP port
   * number" is stored along the instance for compatibility reasons.
   *
   * @param javaPort The emulated "port" number (not part of TIPC).
   * @param ref 32-bit port reference ID (not to be confused with the
   *          {@link InetSocketAddress#getPort()} port).
   * @param node Node hash number (can be used as lookup domain with
   *          {@link #ofService(Scope, int, int, int)}).
   * @return A corresponding {@link AFTIPCSocketAddress} instance.
   * @throws SocketException if the operation fails.
   */
  public static AFTIPCSocketAddress ofSocket(int javaPort, int ref, int node)
      throws SocketException {
    return resolveAddress(toBytes(AddressType.SOCKET_ADDR, Scope.SCOPE_NOT_SPECIFIED, ref, node, 0),
        javaPort, addressFamily());
  }

  /**
   * Returns an {@link AFTIPCSocketAddress} that refers to the topology service.
   *
   * @return A corresponding {@link AFTIPCSocketAddress} instance.
   * @throws SocketException if the operation fails.
   */
  public static AFTIPCSocketAddress ofTopologyService() throws SocketException {
    return resolveAddress(toBytes(AddressType.SERVICE_ADDR, Scope.SCOPE_NOT_SPECIFIED, TIPC_TOP_SRV,
        TIPC_TOP_SRV, 0), 0, addressFamily());
  }

  private static int parseUnsignedInt(String v) {
    if (v.startsWith("0x")) {
      return Integer.parseUnsignedInt(v.substring(2), 16);
    } else {
      return Integer.parseUnsignedInt(v);
    }
  }

  /**
   * Returns an {@link AFTIPCSocketAddress} given a special {@link InetAddress} that encodes the
   * byte sequence of an AF_TIPC socket address, like those returned by {@link #wrapAddress()}.
   *
   * @param address The "special" {@link InetAddress}.
   * @param port The port (use 0 for "none").
   * @return The {@link AFTIPCSocketAddress} instance.
   * @throws SocketException if the operation fails, for example when an unsupported address is
   *           specified.
   */
  public static AFTIPCSocketAddress unwrap(InetAddress address, int port) throws SocketException {
    return AFSocketAddress.unwrap(address, port, addressFamily());
  }

  /**
   * Returns an {@link AFTIPCSocketAddress} given a special {@link InetAddress} hostname that
   * encodes the byte sequence of an AF_TIPC socket address, like those returned by
   * {@link #wrapAddress()}.
   *
   * @param hostname The "special" hostname, as provided by {@link InetAddress#getHostName()}.
   * @param port The port (use 0 for "none").
   * @return The {@link AFTIPCSocketAddress} instance.
   * @throws SocketException if the operation fails, for example when an unsupported address is
   *           specified.
   */
  public static AFTIPCSocketAddress unwrap(String hostname, int port) throws SocketException {
    return AFSocketAddress.unwrap(hostname, port, addressFamily());
  }

  /**
   * Returns an {@link AFTIPCSocketAddress} given a generic {@link SocketAddress}.
   *
   * @param address The address to unwrap.
   * @return The {@link AFTIPCSocketAddress} instance.
   * @throws SocketException if the operation fails, for example when an unsupported address is
   *           specified.
   */
  public static AFTIPCSocketAddress unwrap(SocketAddress address) throws SocketException {
    Objects.requireNonNull(address);
    if (!isSupportedAddress(address)) {
      throw new SocketException("Unsupported address");
    }
    return (AFTIPCSocketAddress) address;
  }

  /**
   * Returns the scope of this address.
   *
   * @return The scope.
   */
  public Scope getScope() {
    byte[] bytes = getBytes();
    if (bytes.length != (5 * 4)) {
      return Scope.SCOPE_NOT_SPECIFIED;
    }
    return Scope.ofValue(ByteBuffer.wrap(bytes, 4, 4).getInt());
  }

  /**
   * Returns the TIPC type part of this address.
   *
   * @return The type identifier
   */
  public int getTIPCType() {
    ByteBuffer bb = ByteBuffer.wrap(getBytes());
    int a = bb.getInt(2 * 4);
    return a;
  }

  /**
   * Returns the TIPC instance part of this address.
   *
   * @return The instance identifier.
   */
  public int getTIPCInstance() {
    ByteBuffer bb = ByteBuffer.wrap(getBytes());
    int a = bb.getInt(3 * 4);
    return a;
  }

  /**
   * Returns the TIPC domain part of this address.
   *
   * @return The domain identifier.
   */
  public int getTIPCDomain() {
    ByteBuffer bb = ByteBuffer.wrap(getBytes());
    int a = bb.getInt(4 * 4);
    return a;
  }

  /**
   * Returns the TIPC lower instance of this address.
   *
   * @return The lower instance identifier.
   */
  public int getTIPCLower() {
    ByteBuffer bb = ByteBuffer.wrap(getBytes());
    int a = bb.getInt(2 * 4);
    return a;
  }

  /**
   * Returns the TIPC upper instance of this address.
   *
   * @return The lower instance identifier.
   */
  public int getTIPCUpper() {
    ByteBuffer bb = ByteBuffer.wrap(getBytes());
    int a = bb.getInt(3 * 4);
    return a;
  }

  /**
   * Returns the TIPC ref of this address.
   *
   * @return The ref identifier.
   */
  public int getTIPCRef() {
    ByteBuffer bb = ByteBuffer.wrap(getBytes());
    int a = bb.getInt(2 * 4);
    return a;
  }

  /**
   * Returns the TIPC node hash of this address.
   *
   * @return The node hash.
   */
  public int getTIPCNodeHash() {
    ByteBuffer bb = ByteBuffer.wrap(getBytes());
    int a = bb.getInt(3 * 4);
    return a;
  }

  @Override
  public String toString() {
    int port = getPort();

    byte[] bytes = getBytes();
    if (bytes.length != (5 * 4)) {
      return getClass().getName() + "[" + (port == 0 ? "" : "port=" + port) + ";UNKNOWN" + "]";
    }

    ByteBuffer bb = ByteBuffer.wrap(bytes);
    int typeId = bb.getInt();
    int scopeId = bb.getInt();
    int a = bb.getInt();
    int b = bb.getInt();
    int c = bb.getInt();

    Scope scope = Scope.ofValue((byte) scopeId);

    AddressType type = AddressType.ofValue(typeId);
    String typeString = type.toDebugString(scope, a, b, c);

    return getClass().getName() + "[" + (port == 0 ? "" : "port=" + port + ";") + typeString + "]";
  }

  @Override
  public boolean hasFilename() {
    return false;
  }

  @Override
  public File getFile() throws FileNotFoundException {
    throw new FileNotFoundException("no file");
  }

  /**
   * Checks if an {@link InetAddress} can be unwrapped to an {@link AFTIPCSocketAddress}.
   *
   * @param addr The instance to check.
   * @return {@code true} if so.
   * @see #wrapAddress()
   * @see #unwrap(InetAddress, int)
   */
  public static boolean isSupportedAddress(InetAddress addr) {
    return AFSocketAddress.isSupportedAddress(addr, addressFamily());
  }

  /**
   * Checks if a {@link SocketAddress} can be unwrapped to an {@link AFTIPCSocketAddress}.
   *
   * @param addr The instance to check.
   * @return {@code true} if so.
   * @see #unwrap(InetAddress, int)
   */
  public static boolean isSupportedAddress(SocketAddress addr) {
    return (addr instanceof AFTIPCSocketAddress);
  }

  @SuppressWarnings("cast")
  private static byte[] toBytes(AddressType addrType, Scope scope, int a, int b, int c) {
    ByteBuffer bb = ByteBuffer.allocate(5 * 4);
    bb.putInt(addrType.value());
    bb.putInt(scope.value());
    bb.putInt(a);
    bb.putInt(b);
    bb.putInt(c);
    return (byte[]) bb.flip().array();
  }

  /**
   * Returns the corresponding {@link AFAddressFamily}.
   *
   * @return The address family instance.
   */
  @SuppressWarnings("null")
  public static synchronized AFAddressFamily<AFTIPCSocketAddress> addressFamily() {
    if (afTipc == null) {
      afTipc = AFAddressFamily.registerAddressFamily("tipc", //
          AFTIPCSocketAddress.class, new AFSocketAddressConfig<AFTIPCSocketAddress>() {

            @Override
            protected AFTIPCSocketAddress parseURI(URI u, int port) throws SocketException {
              return AFTIPCSocketAddress.of(u, port);
            }

            @Override
            protected AFSocketAddressConstructor<AFTIPCSocketAddress> addressConstructor() {
              return AFTIPCSocketAddress::new;
            }

            @Override
            protected String selectorProviderClassname() {
              return "org.newsclub.net.unix.tipc.AFTIPCSelectorProvider";
            }

            @Override
            protected Set<String> uriSchemes() {
              return new HashSet<>(Arrays.asList("tipc", "http+tipc", "https+tipc"));
            }
          });
      try {
        Class.forName("org.newsclub.net.unix.tipc.AFTIPCSelectorProvider");
      } catch (ClassNotFoundException e) {
        // ignore
      }
    }
    return afTipc;
  }

  private String toTipcInt(int v) {
    if (v < 0) {
      return "0x" + Integer.toUnsignedString(v, 16);
    } else {
      return Integer.toUnsignedString(v);
    }
  }

  /**
   * Returns an {@link AFTIPCSocketAddress} for the given URI, if possible.
   *
   * @param uri The URI.
   * @return The address.
   * @throws SocketException if the operation fails.
   */
  @SuppressWarnings("PMD.ShortMethodName")
  public static AFTIPCSocketAddress of(URI uri) throws SocketException {
    return of(uri, -1);
  }

  /**
   * Returns an {@link AFTIPCSocketAddress} for the given URI, if possible.
   *
   * @param uri The URI.
   * @param overridePort The port to forcibly use, or {@code -1} for "don't override".
   * @return The address.
   * @throws SocketException if the operation fails.
   */
  @SuppressWarnings({
      "PMD.CognitiveComplexity", "PMD.CyclomaticComplexity", "PMD.ExcessiveMethodLength",
      "PMD.NcssCount", "PMD.NPathComplexity", "PMD.ShortMethodName"})
  public static AFTIPCSocketAddress of(URI uri, int overridePort) throws SocketException {
    switch (uri.getScheme()) {
      case "tipc":
      case "http+tipc":
      case "https+tipc":
        break;
      default:
        throw new SocketException("Unsupported URI scheme: " + uri.getScheme());
    }

    String host = uri.getHost();
    if (host == null) {
      host = uri.getAuthority();
      if (host != null) {
        int at = host.indexOf('@');
        if (at >= 0) {
          host = host.substring(at + 1);
        }
      }
    }
    if (host == null) {
      throw new SocketException("Cannot get hostname from URI: " + uri);
    }
    int port = overridePort != -1 ? overridePort : uri.getPort();
    if (port != -1) {
      host += ":" + port;
    }
    try {
      Matcher m = PAT_TIPC_URI_HOST_AND_PORT.matcher(host);
      if (!m.matches()) {
        throw new SocketException("Invalid TIPC URI: " + uri);
      }

      String typeStr = m.group("type");
      String scopeStr = m.group("scope");
      if (typeStr == null) {
        typeStr = m.group("type2");
        scopeStr = m.group("scope2");
      }
      String strA = m.group("a");
      String strB = m.group("b");
      String strC = m.group("c");
      String javaPortStr = m.group("javaPort");

      final AddressType addrType;
      switch (typeStr == null ? "" : typeStr) {
        case "service":
          addrType = AddressType.SERVICE_ADDR;
          break;
        case "service-range":
          addrType = AddressType.SERVICE_RANGE;
          break;
        case "socket":
          addrType = AddressType.SOCKET_ADDR;
          break;
        case "":
          addrType = AddressType.SERVICE_ADDR;
          break;
        default:
          addrType = AddressType.ofValue(parseUnsignedInt(typeStr));
          break;
      }

      final Scope scope;
      switch (scopeStr == null ? "" : scopeStr) {
        case "cluster":
          scope = Scope.SCOPE_CLUSTER;
          break;
        case "node":
          scope = Scope.SCOPE_NODE;
          break;
        case "default":
          scope = Scope.SCOPE_NOT_SPECIFIED;
          break;
        case "":
          if (addrType == AddressType.SERVICE_ADDR || addrType == AddressType.SERVICE_RANGE) { // NOPMD
            scope = Scope.SCOPE_CLUSTER;
          } else {
            scope = Scope.SCOPE_NOT_SPECIFIED;
          }
          break;
        default:
          scope = Scope.ofValue(parseUnsignedInt(scopeStr));
          break;
      }

      int a = parseUnsignedInt(strA);
      int b = parseUnsignedInt(strB);

      int c;
      if (strC == null || strC.isEmpty()) {
        if (addrType == AddressType.SERVICE_RANGE) { // NOPMD
          c = b;
        } else {
          c = 0;
        }
      } else {
        c = parseUnsignedInt(strC);
      }

      int javaPort = javaPortStr == null || javaPortStr.isEmpty() ? port : Integer.parseInt(
          javaPortStr);
      if (overridePort != -1) {
        javaPort = overridePort;
      }

      return resolveAddress(toBytes(addrType, scope, a, b, c), javaPort, addressFamily());
    } catch (IllegalArgumentException e) {
      throw (SocketException) new SocketException("Invalid TIPC URI: " + uri).initCause(e);
    }
  }

  @Override
  @SuppressWarnings({"PMD.CognitiveComplexity", "PMD.CompareObjectsWithEquals"})
  public URI toURI(String scheme, URI template) throws IOException {
    switch (scheme) {
      case "tipc":
      case "http+tipc":
      case "https+tipc":
        break;
      default:
        return super.toURI(scheme, template);
    }

    byte[] bytes = getBytes();
    if (bytes.length != (5 * 4)) {
      return super.toURI(scheme, template);
    }

    ByteBuffer bb = ByteBuffer.wrap(bytes);
    AddressType addrType = AddressType.ofValue(bb.getInt());
    Scope scope = Scope.ofValue(bb.getInt());

    StringBuilder sb = new StringBuilder();

    boolean haveScope = true;
    if (scope == Scope.SCOPE_NOT_SPECIFIED) {
      sb.append("default-");
    } else if (scope == Scope.SCOPE_CLUSTER) {
      if (addrType == AddressType.SERVICE_ADDR || addrType == AddressType.SERVICE_RANGE) { // NOPMD
        // implied
        haveScope = false;
      } else {
        sb.append("cluster-");
      }
    } else if (scope == Scope.SCOPE_NODE) {
      sb.append("node-");
    } else {
      sb.append(toTipcInt(scope.value()));
      sb.append('-');
    }

    boolean addrTypeImplied = false;
    if (addrType == AddressType.SERVICE_ADDR) {
      if (!haveScope) {
        addrTypeImplied = true;
      } else {
        sb.append("service");
      }
    } else if (addrType == AddressType.SERVICE_RANGE) {
      sb.append("service-range");
    } else if (addrType == AddressType.SOCKET_ADDR) {
      sb.append("socket");
    } else {
      sb.append(toTipcInt(addrType.value()));
    }
    if (!addrTypeImplied) {
      sb.append('.');
    }

    int a = bb.getInt();
    int b = bb.getInt();
    int c = bb.getInt();

    sb.append(toTipcInt(a));
    sb.append('.');
    sb.append(toTipcInt(b));
    if (c != 0) {
      sb.append('.');
      sb.append(toTipcInt(c));
    }

    return new HostAndPort(sb.toString(), getPort()).toURI(scheme, template);
  }
}
