# Building from source

Here's how you build junixsocket from the source.

## Prerequisites
 
 1. Make sure you have a 64-bit Intel machine running macOS or Linux.
 
    If you have a different platform or architecture, [continue here](customarch.html).
 
 2. Install the Java JDK 16 or newer, Maven 3.8.8 or newer, and junixsocket.
 
    Even though junixsocket can run on Java 8, you need Java 16 or better to build it, so we can
    support the new Java module system on newer Java versions as well as features that are only
    available on newer versions (e.g., UnixDomainSocketAddress).
 
 3. Install a development environment so you can compile C code (preferably clang/llvm).
 
    On macOS, this means installing Xcode.
    For development purposes, you may want to use the Xcode project defined in `junixsocket-native/junixsocket-native.xcodeproj`.
    However, this is not required. Running Maven (see below) is the authoritative way of building the native code.

    Also see [here](crosscomp.html) for instructions how to cross-compile for all supported architectures.

    Be sure to install `bash`, `gcc`,`clang`, `ld`/`binutils`, C headers (`glibc-devel`/`libc-dev`, `musl-dev`, etc.), and, optionally, for TIPC on Linux, Linux headers (e.g, `linux-headers`).

    For example, on Alpine Linux, run the following command:

		sudo apk add git maven clang gcc binutils bash musl-dev libc-dev linux-headers

## Building with Maven

Build and test junixsocket.

    cd junixsocket
    mvn clean install

That's it!

### SNAPSHOT builds

Development versions may need SNAPSHOT versions of dependencies. Use the following command to build:

    mvn clean install -Duse-snapshots

## Build options

While the default build options are a good start, you may want to change the level of scrutiny performed at build time.

Here's how to make building stricter (more potential errors are found):

    mvn clean install -Dstrict

Here's how to make building less strict (this turns off several code quality checkers but will dramatically shorten build times):

    mvn clean install -Dignorant

If some tests fail, you may try

    mvn clean install -DskipTests

If you're having problems with building the native library, you can skip directly to building the Java code via

    mvn clean install -rf :junixsocket-common

You can also try to build the full release version of junixsocket (which will include all cross-compile destinations) -- see the [release instructions](release.html) for details:

    mvn clean install -Dstrict -Drelease

## Issues and Workarounds

### clang/gcc

If you don't have clang, try compiling with gcc. You may need to specify the compiler/linker at the command line:

    mvn clean install -Djunixsocket.native.default.linkerName=gcc

### Failure to find com.kohlschutter.junixsocket:junixsocket-native-custom:jar:default:...-SNAPSHOT

You're building a SNAPSHOT version, skipping over native artifacts, and access to some native
artifacts is missing. Try building with the "use-snapshot" profile first:

    mvn clean install -Duse-snapshot -rf :junixsocket-native-custom

If that doesn't work, try ignoring junixsocket-native-custom as an optional dependency for testing:

    mvn clean install -Dnative-custom.skip -rf :junixsocket-common
