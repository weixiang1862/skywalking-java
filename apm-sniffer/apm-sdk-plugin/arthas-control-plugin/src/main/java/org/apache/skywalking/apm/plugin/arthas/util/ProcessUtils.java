/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.apm.plugin.arthas.util;

import com.taobao.arthas.common.AnsiLog;
import com.taobao.arthas.common.IOUtils;
import com.taobao.arthas.common.JavaVersionUtils;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * copy from https://github.com/alibaba/arthas/blob/master/boot/src/main/java/com/taobao/arthas/boot/ProcessUtils.java
 */
public class ProcessUtils {

    private static String FOUND_JAVA_HOME = null;

    public static void runJarWithArgs(List<String> attachArgs) {
        // find java/java.exe, then try to find tools.jar
        String javaHome = findJavaHome();

        // find java/java.exe
        File javaPath = findJava(javaHome);
        if (javaPath == null) {
            throw new IllegalArgumentException(
                "Can not find java/java.exe executable file under java home: " + javaHome);
        }

        File toolsJar = findToolsJar(javaHome);

        if (JavaVersionUtils.isLessThanJava9()) {
            if (toolsJar == null || !toolsJar.exists()) {
                throw new IllegalArgumentException("Can not find tools.jar under java home: " + javaHome);
            }
        }

        List<String> command = new ArrayList<String>();
        command.add(javaPath.getAbsolutePath());

        if (toolsJar != null && toolsJar.exists()) {
            command.add("-Xbootclasspath/a:" + toolsJar.getAbsolutePath());
        }

        command.addAll(attachArgs);

        ProcessBuilder pb = new ProcessBuilder(command);
        // https://github.com/alibaba/arthas/issues/2166
        pb.environment().put("JAVA_TOOL_OPTIONS", "");
        try {
            final Process proc = pb.start();
            Thread redirectStdout = new Thread(() -> {
                InputStream inputStream = proc.getInputStream();
                try {
                    IOUtils.copy(inputStream, System.out);
                } catch (IOException e) {
                    IOUtils.close(inputStream);
                }

            });

            Thread redirectStderr = new Thread(() -> {
                InputStream inputStream = proc.getErrorStream();
                try {
                    IOUtils.copy(inputStream, System.err);
                } catch (IOException e) {
                    IOUtils.close(inputStream);
                }

            });
            redirectStdout.start();
            redirectStderr.start();
            redirectStdout.join();
            redirectStderr.join();

            int exitValue = proc.exitValue();
            if (exitValue != 0) {
                System.exit(1);
            }
        } catch (Throwable e) {
            // ignore
        }
    }

    /**
     * <pre>
     * 1. Try to find java home from System Property java.home
     * 2. If jdk > 8, FOUND_JAVA_HOME set to java.home
     * 3. If jdk <= 8, try to find tools.jar under java.home
     * 4. If tools.jar do not exists under java.home, try to find System env JAVA_HOME
     * 5. If jdk <= 8 and tools.jar do not exists under JAVA_HOME, throw IllegalArgumentException
     * </pre>
     */
    public static String findJavaHome() {
        if (FOUND_JAVA_HOME != null) {
            return FOUND_JAVA_HOME;
        }

        String javaHome = System.getProperty("java.home");

        if (JavaVersionUtils.isLessThanJava9()) {
            File toolsJar = new File(javaHome, "lib/tools.jar");
            if (!toolsJar.exists()) {
                toolsJar = new File(javaHome, "../lib/tools.jar");
            }
            if (!toolsJar.exists()) {
                // maybe jre
                toolsJar = new File(javaHome, "../../lib/tools.jar");
            }

            if (toolsJar.exists()) {
                FOUND_JAVA_HOME = javaHome;
                return FOUND_JAVA_HOME;
            }

            if (!toolsJar.exists()) {
                AnsiLog.debug("Can not find tools.jar under java.home: " + javaHome);
                String javaHomeEnv = System.getenv("JAVA_HOME");
                if (javaHomeEnv != null && !javaHomeEnv.isEmpty()) {
                    AnsiLog.debug("Try to find tools.jar in System Env JAVA_HOME: " + javaHomeEnv);
                    // $JAVA_HOME/lib/tools.jar
                    toolsJar = new File(javaHomeEnv, "lib/tools.jar");
                    if (!toolsJar.exists()) {
                        // maybe jre
                        toolsJar = new File(javaHomeEnv, "../lib/tools.jar");
                    }
                }

                if (toolsJar.exists()) {
                    AnsiLog.info("Found java home from System Env JAVA_HOME: " + javaHomeEnv);
                    FOUND_JAVA_HOME = javaHomeEnv;
                    return FOUND_JAVA_HOME;
                }

                throw new IllegalArgumentException("Can not find tools.jar under java home: " + javaHome
                                                       + ", please try to start arthas-boot with full path java. Such as /opt/jdk/bin/java -jar arthas-boot.jar");
            }
        } else {
            FOUND_JAVA_HOME = javaHome;
        }
        return FOUND_JAVA_HOME;
    }

    private static File findJava(String javaHome) {
        String[] paths = {
            "bin/java",
            "bin/java.exe",
            "../bin/java",
            "../bin/java.exe"
        };

        List<File> javaList = new ArrayList<File>();
        for (String path : paths) {
            File javaFile = new File(javaHome, path);
            if (javaFile.exists()) {
                AnsiLog.debug("Found java: " + javaFile.getAbsolutePath());
                javaList.add(javaFile);
            }
        }

        if (javaList.isEmpty()) {
            AnsiLog.debug("Can not find java/java.exe under current java home: " + javaHome);
            return null;
        }

        // find the shortest path, jre path longer than jdk path
        if (javaList.size() > 1) {
            Collections.sort(javaList, new Comparator<File>() {
                @Override
                public int compare(File file1, File file2) {
                    try {
                        return file1.getCanonicalPath().length() - file2.getCanonicalPath().length();
                    } catch (IOException e) {
                        // ignore
                    }
                    return -1;
                }
            });
        }
        return javaList.get(0);
    }

    private static File findToolsJar(String javaHome) {
        if (JavaVersionUtils.isGreaterThanJava8()) {
            return null;
        }

        File toolsJar = new File(javaHome, "lib/tools.jar");
        if (!toolsJar.exists()) {
            toolsJar = new File(javaHome, "../lib/tools.jar");
        }
        if (!toolsJar.exists()) {
            // maybe jre
            toolsJar = new File(javaHome, "../../lib/tools.jar");
        }

        if (!toolsJar.exists()) {
            throw new IllegalArgumentException("Can not find tools.jar under java home: " + javaHome);
        }

        AnsiLog.debug("Found tools.jar: " + toolsJar.getAbsolutePath());
        return toolsJar;
    }
}
