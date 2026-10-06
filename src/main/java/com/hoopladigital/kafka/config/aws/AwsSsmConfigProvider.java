package com.hoopladigital.kafka.config.aws;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.config.ConfigData;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.config.provider.ConfigProvider;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.SsmClientBuilder;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

@Data
@Slf4j
public class AwsSsmConfigProvider implements ConfigProvider {
  // no TTL by default: when a provider returns a TTL, Connect schedules a connector
  // restart each time it expires. Set "ttl" (ms) only if parameters actually rotate.
  private Long ttl;
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
    return new ConfigData(withPluginClassLoader(() -> getParameters(path)), ttl);
  }

  /**
   * This will return the specific parameters that match the environment, path, and keys.
   *
   * @param path the path where the data resides (eg., foo=${aws-ssm:kafka_connect})
   * @param keys the keys whose values will be retrieved (blah1, blah2)
   * @return the ConfigData containing the keys and values
   * @throws ConfigException if any of the keys is not found
   */
  @Override
  public ConfigData get(final String path, final Set<String> keys) {

    log.debug("getting ALL parameters for path '{}'", path);
    final Map<String, String> allData = withPluginClassLoader(() -> getParameters(path));

    log.debug("filtering parameters at path '{}' to only include {} keys: ({})", path, keys.size(), keys);
    final Map<String, String> values = new HashMap<>();
    final Set<String> missing = new TreeSet<>();
    for (final String key : keys) {
      final String value = allData.get(key);
      if (value == null) {
        missing.add(key);
      } else {
        values.put(key, value);
      }
    }

    // without this, Connect leaves the literal ${ssm:...} placeholder in the config
    if (!missing.isEmpty()) {
      throw new ConfigException(String.format(
        "SSM parameter(s) %s not found for path '%s' (environment '%s', addEnvironmentPrefix=%s)",
        missing,
        path,
        environment,
        addEnvironmentPrefix
      ));
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
    withPluginClassLoader(() -> {
      doConfigure(map);
      return null;
    });
  }

  private void doConfigure(final Map<String, ?> map) {

    log.trace("configuring SSM ConfigProvider with keys: {}", map.keySet());

    if (ssmClient == null) {

      log.debug("creating SSM client");

      final SsmClientBuilder builder = SsmClient.builder();

      final Object configRegion = map.get("region");
      if (null != configRegion) {
        log.debug("using region from configuration: '{}'", configRegion);
        final String region = configRegion.toString();
        builder.region(Region.of(region));
      }

      // IRSA (web identity) needs the sts module on the classpath; without it the
      // default chain silently skips it and falls back to IMDS, which pods can't reach
      final AwsCredentialsProvider credentialsProvider = DefaultCredentialsProvider.create();
      checkCredentials(credentialsProvider);
      builder.credentialsProvider(credentialsProvider);

      // be explicit so the SDK doesn't have to discover an HTTP client via ServiceLoader
      builder.httpClientBuilder(ApacheHttpClient.builder());

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

  /**
   * Resolve credentials once at startup so a broken setup shows up in the worker log
   * right away. This only logs: throwing here would stop the whole Connect worker, not
   * just the connectors that use ${ssm:...}.
   */
  private void checkCredentials(final AwsCredentialsProvider credentialsProvider) {
    final String roleArn = System.getenv("AWS_ROLE_ARN");
    try {
      credentialsProvider.resolveCredentials();
      log.info(
        "SSM ConfigProvider resolved AWS credentials using {}",
        roleArn != null ? "role " + roleArn : "the default credential chain"
      );
    } catch (final SdkException e) {
      log.error(
        "SSM ConfigProvider could not resolve AWS credentials (AWS_ROLE_ARN={}); "
          + "connectors using ${ssm:...} will fail: {}",
        roleArn,
        e.getMessage()
      );
    }
  }

  /**
   * Connect calls providers from worker threads whose context class loader is not the
   * plugin's, while the AWS SDK discovers implementations via the context class loader.
   */
  private <T> T withPluginClassLoader(final Supplier<T> action) {
    final Thread thread = Thread.currentThread();
    final ClassLoader original = thread.getContextClassLoader();
    thread.setContextClassLoader(AwsSsmConfigProvider.class.getClassLoader());
    try {
      return action.get();
    } finally {
      thread.setContextClassLoader(original);
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
          response.parameters().forEach(p -> {
              final var nameWithPath = p.name();
              if (nameWithPath.contains("/")) {
                  final var strings = nameWithPath.split("/");
                  parameters.put(strings[strings.length - 1], p.value());
              } else {
                  parameters.put(nameWithPath, p.value());
              }
          });
      }

    // values are decrypted secrets, so only ever log the names
    log.trace("parameter names: {}", parameters.keySet());

    return parameters;

  }

    private GetParametersByPathResponse getByPath(final String connectPath) {

        log.debug("getting parameters for path '{}'", connectPath);

        final var responseList = new ArrayList<GetParametersByPathResponse>();

        String nextToken = null;
        boolean more;
        do {

            final GetParametersByPathResponse parameters = ssmClient.getParametersByPath(
                GetParametersByPathRequest.builder()
                    .path(connectPath)
                    .nextToken(nextToken)
                    .withDecryption(true)
                    .recursive(false)
                    .build()
            );

            more = parameters.nextToken() != null;
            nextToken = parameters.nextToken();
            responseList.add(parameters);
            log.debug("found {} parameters for path '{}'", parameters.parameters().size(), connectPath);

        } while (more);

        final var parameters = new ArrayList<Parameter>();
        for (final var r : responseList) {
            parameters.addAll(r.parameters());
        }

        log.debug("found a total of {} parameters for path '{}'", parameters.size(), connectPath);

        return GetParametersByPathResponse.builder()
            .parameters(parameters)
            .build();

    }

  private String buildPath(final String... part) {
    return "/" + String.join("/", part) + "/";
  }

}
