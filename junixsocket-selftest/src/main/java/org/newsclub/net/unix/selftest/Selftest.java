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
package org.newsclub.net.unix.selftest;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.jupiter.engine.discovery.DiscoverySelectorResolver;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;
import org.junit.platform.engine.support.hierarchical.HierarchicalTestEngine;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketCapability;

import com.kohlschutter.annotations.compiletime.SuppressFBWarnings;
import com.kohlschutter.util.ConsolePrintStream;
import com.kohlschutter.util.SystemPropertyUtil;

/**
 * Performs a series of self-tests.
 * 
 * Specifically, we run all unit tests of junixsocket-core and junixsocket-rmi.
 * 
 * NOTE: The Selftest will fail when run from within Eclipse due to test classes not being present.
 * Invoke via <code>java -jar junixsocket-selftest-...-jar-with-dependencies.jar</code>.
 * 
 * @author Christian Kohlschütter
 */
public class Selftest {
  private static final Class<? extends Annotation> CAP_ANNOTATION_CLASS =
      getAFUNIXSocketCapabilityRequirementClass();

  private final ConsolePrintStream out = ConsolePrintStream.wrapSystemOut();
  private final Map<String, Object> results = new LinkedHashMap<>();
  private final List<AFUNIXSocketCapability> supportedCapabilites = new ArrayList<>();
  private final List<AFUNIXSocketCapability> unsupportedCapabilites = new ArrayList<>();
  private boolean withIssues = false;
  private boolean fail = false;

  /**
   * maven-shade-plugin's minimizeJar isn't perfect, so we give it a little hint by adding static
   * references to classes that are otherwise only found via reflection.
   * 
   * @author Christian Kohlschütter
   */
  @SuppressFBWarnings("UUF_UNUSED_FIELD")
  static final class MinimizeJarDependencies {
    JupiterTestEngine jte;
    HierarchicalTestEngine<?> hte;
    EngineDescriptor ed;
    DiscoverySelectorResolver dsr;
    org.newsclub.lib.junixsocket.common.NarMetadata nmCommon;
    org.newsclub.lib.junixsocket.custom.NarMetadata nmCustom;
  }

  public Selftest() {
  }

  /**
   * Run this from the command line to ensure junixsocket works correctly on the target system.
   * 
   * A zero error code indicates success.
   * 
   * @param args Ignored.
   * @throws Exception on error.
   */
  public static void main(String[] args) throws Exception {
    Selftest st = new Selftest();

    st.printExplanation();
    st.dumpSystemProperties();
    st.dumpOSReleaseFiles();
    st.checkSupported();
    st.checkCapabilities();

    for (Entry<String, Class<?>[]> en : new SelftestProvider().tests().entrySet()) {
      st.runTests(en.getKey(), en.getValue());
    }

    st.dumpResults();

    int rc = st.isFail() ? 1 : 0;

    if (SystemPropertyUtil.getBooleanSystemProperty("selftest.wait.at-end", false)) {
      System.gc(); // NOPMD
      System.out.print("Press any key to end test. ");
      System.out.flush();
      System.in.read();
      System.out.println("RC=" + rc);
    }

    System.out.flush();
    System.exit(rc); // NOPMD
  }

  public void printExplanation() throws IOException {
    out.println(
        "This program determines whether junixsocket is supported on the current platform.");
    out.println("The final line should say whether the selftest passed or failed.");
    out.println();
    out.println(
        "If the selftest failed, please visit https://github.com/kohlschutter/junixsocket/issues");
    out.println("and file a new bug report with the output below.");
    out.println();
    out.println("junixsocket selftest version " + AFUNIXSocket.getVersion());
    out.println();
  }

  public void dumpSystemProperties() {
    out.println("System properties:");
    out.println();
    for (Map.Entry<Object, Object> en : new TreeMap<>(System.getProperties()).entrySet()) {
      String key = String.valueOf(en.getKey());
      String value = String.valueOf(en.getValue());
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        switch (c) {
          case '\n':
            sb.append("\\n");
            break;
          case '\r':
            sb.append("\\r");
            break;
          case '\t':
            sb.append("\\r");
            break;
          default:
            if (c < 32 || c >= 127) {
              sb.append(String.format(Locale.ENGLISH, "\\u%04x", (int) c));
            }
            sb.append(c);
            break;
        }
      }
      out.println(key + ": " + sb.toString());
    }
    out.println();
  }

  public void checkSupported() {
    out.print("AFUNIXSocket.isSupported: ");
    out.flush();

    boolean isSupported = AFUNIXSocket.isSupported();
    out.println(isSupported);
    out.println();
    out.flush();

    if (!isSupported) {
      out.println("FAIL: junixsocket is not supported on this platform");
      out.println();
      fail = true;
    }
  }

  public void checkCapabilities() {
    for (AFUNIXSocketCapability cap : AFUNIXSocketCapability.values()) {
      boolean supported = AFUNIXSocket.supports(cap);
      (supported ? supportedCapabilites : unsupportedCapabilites).add(cap);
    }
  }

  /**
   * Checks if any test has failed so far.
   * 
   * @return {@code true} if failed.
   */
  public boolean isFail() {
    return fail;
  }

  /**
   * Dumps the results of the selftest.
   * 
   */
  public void dumpResults() {
    out.println();
    out.println("Selftest results:");

    for (Map.Entry<String, Object> en : results.entrySet()) {
      Object res = en.getValue();
      String result = "DONE";
      String extra;
      if (res == null) {
        result = "SKIP";
        extra = "(skipped by user request)";
      } else if (res instanceof Throwable) {
        result = "FAIL";
        extra = res.toString();
        fail = true;
      } else {
        TestExecutionSummary summary = (TestExecutionSummary) en.getValue();

        extra = summary.getTestsSucceededCount() + "/" + summary.getTestsFoundCount();
        long nSkipped = summary.getTestsSkippedCount();
        if (nSkipped > 0) {
          extra += " (" + nSkipped + " skipped)";
        }

        if (summary.getTestsFailedCount() > 0) {
          result = "FAIL";
          fail = true;
        } else if (summary.getTestsFoundCount() == 0) {
          result = "NONE";
          fail = true;
        } else if ((summary.getTestsSucceededCount() + summary.getTestsSkippedCount()) == summary
            .getTestsFoundCount()) {
          result = "PASS";
        } else if (summary.getTestsAbortedCount() > 0) {
          withIssues = true;
        }
      }
      out.println(result + "\t" + en.getKey() + "\t" + extra);
    }
    out.println();

    out.println("Supported capabilities:   " + supportedCapabilites);
    out.println("Unsupported capabilities: " + unsupportedCapabilites);
    out.println();

    if (fail) {
      out.println("Selftest FAILED");
    } else if (withIssues) {
      out.println("Selftest PASSED WITH ISSUES");
    } else {
      out.println("Selftest PASSED");
    }
  }

  @SuppressWarnings("unchecked")
  private static Class<? extends Annotation> getAFUNIXSocketCapabilityRequirementClass() {
    try {
      return (Class<? extends Annotation>) Class.forName(
          "org.newsclub.net.unix.AFUNIXSocketCapabilityRequirement");
    } catch (ClassNotFoundException e1) {
      return null;
    }
  }

  private boolean checkIfCapabilitiesSupported(String className) {
    if (CAP_ANNOTATION_CLASS != null) {
      try {
        Class<?> klass = Class.forName(className);
        Annotation annotation = klass.getAnnotation(CAP_ANNOTATION_CLASS);
        if (annotation != null) {
          try {
            AFUNIXSocketCapability[] caps = (AFUNIXSocketCapability[]) annotation.getClass()
                .getMethod("value").invoke(annotation);
            if (caps != null) {
              for (AFUNIXSocketCapability cap : caps) {
                if (!AFUNIXSocket.supports(cap)) {
                  out.println("Skipping class " + className + "; unsupported capability: " + cap);
                  return false;
                }
              }
            }
          } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException
              | NoSuchMethodException | SecurityException e) {
            // ignore
          }
        }
      } catch (ClassNotFoundException e) {
        out.println("Class not found: " + className);
        withIssues = true;
      }
    }

    return true;
  }

  /**
   * Runs the given test classes for the specified module.
   * 
   * @param module The module name.
   * @param testClasses The test classes.
   */
  public void runTests(String module, Class<?>[] testClasses) {
    String prefix = "Testing \"" + module + "\"... ";
    out.markPosition();
    out.update(prefix);
    out.flush();

    String only = System.getProperty("selftest.only", "");

    Object summary;
    if (Boolean.valueOf(System.getProperty("selftest.skip." + module, "false"))) {
      out.println("Skipping module " + module + "; skipped by request");
      withIssues = true;
      summary = null;
    } else {
      List<Class<?>> list = new ArrayList<>(testClasses.length);
      for (Class<?> testClass : testClasses) {
        if (testClass == null) {
          // ignore
          continue;
        }
        String className = testClass.getName();
        String simpleName = testClass.getSimpleName();

        if (!only.isEmpty() && !only.equals(className) && !only.equals(simpleName)) {
          continue;
        }

        String skipFullyQualifiedProp = System.getProperty("selftest.skip." + className, "");
        boolean skipFullyQualified = !skipFullyQualifiedProp.isEmpty() && Boolean.valueOf(
            skipFullyQualifiedProp);

        if (skipFullyQualified || Boolean.valueOf(System.getProperty("selftest.skip." + simpleName,
            "false"))) {
          out.println("Skipping test class " + className + "; skipped by request");
          withIssues = true;
        } else if (checkIfCapabilitiesSupported(className)) {
          list.add(testClass);
        }
      }

      try {
        summary = new SelftestExecutor(list, prefix).execute(out);
      } catch (Exception e) {
        e.printStackTrace(out);
        summary = e;
      }
    }
    results.put(module, summary);
  }

  private void dumpContentsOfSystemConfigFile(File file) {
    if (!file.exists()) {
      return;
    }
    String p = file.getAbsolutePath();
    System.out.println("BEGIN contents of file: " + p);

    final int maxToRead = 4096;
    char[] buf = new char[4096];
    int numRead = 0;
    try (InputStreamReader isr = new InputStreamReader(new FileInputStream(file),
        StandardCharsets.UTF_8);) {

      OutputStreamWriter outWriter = new OutputStreamWriter(System.out, Charset.defaultCharset());
      int read = -1;
      boolean lastWasNewline = false;
      while (numRead < maxToRead && (read = isr.read(buf)) != -1) {
        numRead += read;
        outWriter.write(buf, 0, read);
        outWriter.flush();
        lastWasNewline = (read > 0 && buf[read - 1] == '\n');
      }
      if (!lastWasNewline) {
        System.out.println();
      }
      if (read != -1) {
        System.out.println("[...]");
      }
    } catch (Exception e) {
      System.out.println("ERROR while reading contents of file: " + p + ": " + e);
    }
    System.out.println("=END= contents of file: " + p);
    System.out.println();
  }

  public void dumpOSReleaseFiles() throws IOException {
    Set<Path> canonicalPaths = new HashSet<>();
    for (String f : new String[] {
        "/etc/os-release", "/etc/lsb-release", "/etc/lsb_release", "/etc/system-release",
        "/etc/system-release-cpe",
        //
        "/etc/debian_version", "/etc/fedora-release", "/etc/redhat-release", "/etc/centos-release",
        "/etc/centos-release-upstream", "/etc/SuSE-release", "/etc/arch-release",
        "/etc/gentoo-release", "/etc/ubuntu-release",}) {

      File file = new File(f);
      if (!file.exists() || file.isDirectory()) {
        continue;
      }
      Path p = file.toPath().toAbsolutePath();
      for (int i = 0; i < 2; i++) {
        if (Files.isSymbolicLink(p)) {
          Path p2 = Files.readSymbolicLink(p);
          if (!p2.isAbsolute()) {
            p = new File(p.toFile().getParentFile(), p2.toString()).toPath().toAbsolutePath();
          }
        }
      }

      if (!canonicalPaths.add(p)) {
        continue;
      }

      dumpContentsOfSystemConfigFile(file);
    }
  }
}
