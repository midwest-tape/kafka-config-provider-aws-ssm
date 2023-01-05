/**
 * Copyright © 2021 Jeremy Custenborder (jcustenborder@gmail.com)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hoopladigital.kafka.config.aws;

import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementClient;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementClientBuilder;
import com.amazonaws.services.simplesystemsmanagement.model.GetParametersByPathRequest;
import com.amazonaws.services.simplesystemsmanagement.model.GetParametersByPathResult;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.config.ConfigData;
import org.apache.kafka.common.config.provider.ConfigProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

//@Description("This config provider is used to retrieve secrets from the AWS Secrets Manager service.")
//@DocumentationTip("Config providers can be used with anything that supports the AbstractConfig base class that is shipped with Apache Kafka.")
//@DocumentationSections(
//    sections = {
//        @DocumentationSection(title = "Secret Value", text = "The value for the secret must be formatted as a JSON object. " +
//            "This allows multiple keys of data to be stored in a single secret. The name of the secret in AWS Secrets Manager " +
//            "will correspond to the path that is requested by the config provider.\n" +
//            "\n" +
//            ".. code-block:: json\n" +
//            "    :caption: Example Secret Value\n" +
//            "\n" +
//            "    {\n" +
//            "      \"username\" : \"${secretManager:secret/test/some/connector:username}\",\n" +
//            "      \"password\" : \"${secretManager:secret/test/some/connector:password}\"\n" +
//            "    }\n" +
//            "")
//    }
//)
@Data
@Slf4j
public class AwsSsmConfigProvider implements ConfigProvider {
  // this is just a default, it can be overridden by the config
  private long ttl = 1000 * 60 * 60; // 1 hour
  private String environment;
  private boolean addEnvironmentPrefix = true;
  private AWSSimpleSystemsManagement ssmClient;

  /**
   * This will return all parameters that match the path and environment.
   *
   * @param path the path where the data resides (eg., foo=${ssm:kafka_connect})
   * @return the ConfigData containing all the keys and values
   */
  @Override
  public ConfigData get(final String path) {
    log.debug("getting parameters for path '{}'", path);
    return new ConfigData(getParameters(path), ttl);
  }

  /**
   * This will return the specific parameters that match the environment, path, and keys.
   *
   * @param path the path where the data resides (eg., foo=${aws-ssm:kafka_connect})
   * @param keys the keys whose values will be retrieved (blah1, blah2)
   * @return the ConfigData containing the keys and values
   */
  @Override
  public ConfigData get(final String path, final Set<String> keys) {

    log.debug("getting ALL parameters for path '{}'", path);
    final ConfigData allData = get(path);

    log.debug("filtering parameters at path '{}' to only include {} keys: ({})", path, keys.size(), keys);
    final Map<String, String> values = new HashMap<>();
    for (final String key : keys) {
      values.put(key, allData.data().get(key));
    }

    log.debug(
      "returning {} filtered parameters for path '{}' and {} keys ({})",
      values.size(),
      path,
      keys.size(),
      keys
    );

    return new ConfigData(values, ttl);

  }

  @Override
  public void close() {
    // nothing to close, but we can say we are closing
    log.info("closing SSM ConfigProvider for environment: {}", environment);
  }

  @Override
  public void configure(final Map<String, ?> map) {

    log.trace("configuring SSM ConfigProvider with map: {}", map);

    if (ssmClient == null) {
      log.debug("creating SSM client");
//            final var builder = SsmClient.builder();
      final AWSSimpleSystemsManagementClientBuilder builder = AWSSimpleSystemsManagementClient.builder();

      final Object configRegion = map.get("region");
      if (null != configRegion) {
        log.debug("using region from configuration: '{}'", configRegion);
        final String region = configRegion.toString();
        builder.withRegion(region);
      }

      ssmClient = builder.build();
    }

    final Object configTtl = map.get("ttl");
    if (null != configTtl) {
      log.info("using ttl from configuration: {}", configTtl);
      ttl = Long.parseLong(configTtl.toString());
    }

    final Object configAddEnvironmentPrefix = map.get("addEnvironmentPrefix");
    if (null != configAddEnvironmentPrefix) {
      log.info("using addEnvironmentPrefix from configuration: {}", configAddEnvironmentPrefix);
      addEnvironmentPrefix = Boolean.parseBoolean(configAddEnvironmentPrefix.toString());
    }

    if (addEnvironmentPrefix) {
      final Object configEnv = map.get("environment");
      log.info("configuration defined environment as '{}'", configEnv);
      environment = String.valueOf(configEnv);
      log.info("configuring SSM ConfigProvider for environment: '{}'", environment);
    }

  }

  private Map<String, String> getParameters(final String path) {

    // get all the parameters in the relevant paths:
    final List<String> paths = new ArrayList<>();
    if (addEnvironmentPrefix) {
      log.debug("adding environment prefix to path: {}", path);
      paths.add(buildPath("global"));
      paths.add(buildPath(environment));
      paths.add(buildPath(environment, path));
    } else {
      paths.add(buildPath("global"));
      paths.add(buildPath(path));
    }

    final List<GetParametersByPathResult> responseList = new ArrayList<>();
    for (final String s : paths) {
      responseList.add(getByPath(s));
    }

    log.debug("merging all parameters");
    final Map<String, String> parameters = new HashMap<>();

    for (final GetParametersByPathResult response : responseList) {
      response.getParameters().forEach(p -> parameters.put(p.getName(), p.getValue()));
    }

    return parameters;

  }

  private GetParametersByPathResult getByPath(
    final String connectPath
  ) {
    log.debug("getting parameters for path '{}'", connectPath);
    final GetParametersByPathResult parameters = ssmClient.getParametersByPath(
//        GetParametersByPathRequest.builder()
//            .path(connectPath)
//            .withDecryption(true)
//            .recursive(false)
//            .build()
      new GetParametersByPathRequest()
        .withPath(connectPath)
        .withWithDecryption(true)
        .withRecursive(false)
    );
    log.debug("found {} parameters for path '{}'", parameters.getParameters().size(), connectPath);
    return parameters;
  }

  private String buildPath(final String... part) {
    return "/" + String.join("/", part) + "/";
  }

}
