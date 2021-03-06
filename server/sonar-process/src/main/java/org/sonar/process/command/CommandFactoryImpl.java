/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.process.command;

import java.io.File;
import java.util.Map;
import java.util.Optional;
import org.sonar.process.ProcessId;
import org.sonar.process.ProcessProperties;
import org.sonar.process.Props;
import org.sonar.process.es.EsFileSystem;
import org.sonar.process.es.EsLogging;
import org.sonar.process.es.EsSettings;
import org.sonar.process.es.EsYmlSettings;
import org.sonar.process.jmvoptions.CeJvmOptions;
import org.sonar.process.jmvoptions.EsJvmOptions;
import org.sonar.process.jmvoptions.JvmOptions;
import org.sonar.process.jmvoptions.WebJvmOptions;

import static org.sonar.process.ProcessProperties.HTTPS_PROXY_HOST;
import static org.sonar.process.ProcessProperties.HTTPS_PROXY_PORT;
import static org.sonar.process.ProcessProperties.HTTP_PROXY_HOST;
import static org.sonar.process.ProcessProperties.HTTP_PROXY_PORT;

public class CommandFactoryImpl implements CommandFactory {
  /**
   * Properties about proxy that must be set as system properties
   */
  private static final String[] PROXY_PROPERTY_KEYS = new String[] {
    HTTP_PROXY_HOST,
    HTTP_PROXY_PORT,
    "http.nonProxyHosts",
    HTTPS_PROXY_HOST,
    HTTPS_PROXY_PORT,
    "http.auth.ntlm.domain",
    "socksProxyHost",
    "socksProxyPort"};

  private final Props props;
  private final File tempDir;

  public CommandFactoryImpl(Props props, File tempDir) {
    this.props = props;
    this.tempDir = tempDir;
  }

  @Override
  public EsCommand createEsCommand() {
    EsFileSystem esFileSystem = new EsFileSystem(props);
    if (!esFileSystem.getExecutable().exists()) {
      throw new IllegalStateException("Cannot find elasticsearch binary");
    }
    Map<String, String> settingsMap = new EsSettings(props, esFileSystem).build();

    return new EsCommand(ProcessId.ELASTICSEARCH, esFileSystem.getHomeDirectory())
      .setFileSystem(esFileSystem)
      .setLog4j2Properties(new EsLogging().createProperties(props, esFileSystem.getLogDirectory()))
      .setArguments(props.rawProperties())
      .setClusterName(settingsMap.get("cluster.name"))
      .setHost(settingsMap.get("network.host"))
      .setPort(Integer.valueOf(settingsMap.get("transport.tcp.port")))
      .addEsOption("-Epath.conf="+esFileSystem.getConfDirectory().getAbsolutePath())
      .setEsJvmOptions(new EsJvmOptions()
        .addFromMandatoryProperty(props, ProcessProperties.SEARCH_JAVA_OPTS)
        .addFromMandatoryProperty(props, ProcessProperties.SEARCH_JAVA_ADDITIONAL_OPTS))
      .setEsYmlSettings(new EsYmlSettings(settingsMap))
      .setEnvVariable("ES_JVM_OPTIONS", esFileSystem.getJvmOptions().getAbsolutePath())
      .setEnvVariable("JAVA_HOME", System.getProperties().getProperty("java.home"));
  }

  @Override
  public JavaCommand createWebCommand(boolean leader) {
    File homeDir = props.nonNullValueAsFile(ProcessProperties.PATH_HOME);

    WebJvmOptions jvmOptions = new WebJvmOptions(tempDir)
      .addFromMandatoryProperty(props, ProcessProperties.WEB_JAVA_OPTS)
      .addFromMandatoryProperty(props, ProcessProperties.WEB_JAVA_ADDITIONAL_OPTS);
    addProxyJvmOptions(jvmOptions);

    JavaCommand<WebJvmOptions> command = new JavaCommand<WebJvmOptions>(ProcessId.WEB_SERVER, homeDir)
      .setArguments(props.rawProperties())
      .setJvmOptions(jvmOptions)
      // required for logback tomcat valve
      .setEnvVariable(ProcessProperties.PATH_LOGS, props.nonNullValue(ProcessProperties.PATH_LOGS))
      .setArgument("sonar.cluster.web.startupLeader", Boolean.toString(leader))
      .setClassName("org.sonar.server.app.WebServer")
      .addClasspath("./lib/common/*")
      .addClasspath("./lib/server/*");
    String driverPath = props.value(ProcessProperties.JDBC_DRIVER_PATH);
    if (driverPath != null) {
      command.addClasspath(driverPath);
    }
    return command;
  }

  @Override
  public JavaCommand createCeCommand() {
    File homeDir = props.nonNullValueAsFile(ProcessProperties.PATH_HOME);

    CeJvmOptions jvmOptions = new CeJvmOptions(tempDir)
      .addFromMandatoryProperty(props, ProcessProperties.CE_JAVA_OPTS)
      .addFromMandatoryProperty(props, ProcessProperties.CE_JAVA_ADDITIONAL_OPTS);
    addProxyJvmOptions(jvmOptions);

    JavaCommand<CeJvmOptions> command = new JavaCommand<CeJvmOptions>(ProcessId.COMPUTE_ENGINE, homeDir)
      .setArguments(props.rawProperties())
      .setJvmOptions(jvmOptions)
      .setClassName("org.sonar.ce.app.CeServer")
      .addClasspath("./lib/common/*")
      .addClasspath("./lib/server/*")
      .addClasspath("./lib/ce/*");
    String driverPath = props.value(ProcessProperties.JDBC_DRIVER_PATH);
    if (driverPath != null) {
      command.addClasspath(driverPath);
    }
    return command;
  }

  private <T extends JvmOptions> void addProxyJvmOptions(JvmOptions<T> jvmOptions) {
    for (String key : PROXY_PROPERTY_KEYS) {
      getPropsValue(key).ifPresent(val -> jvmOptions.add("-D" + key + "=" + val));
    }

    // defaults of HTTPS are the same than HTTP defaults
    setSystemPropertyToDefaultIfNotSet(jvmOptions, HTTPS_PROXY_HOST, HTTP_PROXY_HOST);
    setSystemPropertyToDefaultIfNotSet(jvmOptions, HTTPS_PROXY_PORT, HTTP_PROXY_PORT);
  }

  private void setSystemPropertyToDefaultIfNotSet(JvmOptions jvmOptions,
    String httpsProperty, String httpProperty) {
    Optional<String> httpValue = getPropsValue(httpProperty);
    Optional<String> httpsValue = getPropsValue(httpsProperty);
    if (!httpsValue.isPresent() && httpValue.isPresent()) {
      jvmOptions.add("-D" + httpsProperty + "=" + httpValue.get());
    }
  }

  private Optional<String> getPropsValue(String key) {
    return Optional.ofNullable(props.value(key));
  }
}
