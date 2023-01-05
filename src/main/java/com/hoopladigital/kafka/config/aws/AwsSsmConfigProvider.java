package com.hoopladigital.kafka.config.aws;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.config.ConfigData;
import org.apache.kafka.common.config.provider.ConfigProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.SsmClientBuilder;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@Slf4j
public class AwsSsmConfigProvider implements ConfigProvider {
  // this is just a default, it can be overridden by the config
  private long ttl = 1000 * 60 * 60; // 1 hour
  private String environment;
  private boolean addEnvironmentPrefix = true;
  private SsmClient ssmClient;

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

      final SsmClientBuilder builder = SsmClient.builder();

      final Object configRegion = map.get("region");
      if (null != configRegion) {
        log.debug("using region from configuration: '{}'", configRegion);
        final String region = configRegion.toString();
        builder.region(Region.of(region));
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
      paths.add(buildPath(environment));
      paths.add(buildPath(environment, path));
    } else {
      paths.add(buildPath(path));
    }

    final List<GetParametersByPathResponse> responseList = new ArrayList<>();
    for (final String s : paths) {
      responseList.add(getByPath(s));
    }

    log.debug("merging all parameters");
    final Map<String, String> parameters = new HashMap<>();

    for (final GetParametersByPathResponse response : responseList) {
      response.parameters().forEach(p -> parameters.put(p.name(), p.value()));
    }

    return parameters;

  }

  private GetParametersByPathResponse getByPath(
    final String connectPath
  ) {
    log.debug("getting parameters for path '{}'", connectPath);
    final GetParametersByPathResponse parameters = ssmClient.getParametersByPath(
        GetParametersByPathRequest.builder()
            .path(connectPath)
            .withDecryption(true)
            .recursive(false)
            .build()
    );
    log.debug("found {} parameters for path '{}'", parameters.parameters().size(), connectPath);
    return parameters;
  }

  private String buildPath(final String... part) {
    return "/" + String.join("/", part) + "/";
  }

}
