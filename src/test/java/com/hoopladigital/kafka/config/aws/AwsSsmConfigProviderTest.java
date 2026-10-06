package com.hoopladigital.kafka.config.aws;

import org.apache.kafka.common.config.ConfigData;
import org.apache.kafka.common.config.ConfigException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

public class AwsSsmConfigProviderTest {

  private AwsSsmConfigProvider ssmConfigProvider;

  @Mock
  private SsmClient ssmClient;

  @Captor
  private ArgumentCaptor<GetParametersByPathRequest> parameterCaptor;

  private final Map<String, String> config = new HashMap<>();

  @BeforeEach
  public void setup() {
    config.put("environment", "unit-test");
    try (final AutoCloseable ignored = openMocks(this)) {
      ssmConfigProvider = new AwsSsmConfigProvider();
      ssmConfigProvider.setSsmClient(ssmClient);
      ssmConfigProvider.configure(config);
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void should_query_environment_and_path() {

    final List<String> expected = List.of(
      "/unit-test/",
      "/unit-test/path/"
    );

    // just return empty objects here
    when(ssmClient.getParametersByPath(any(GetParametersByPathRequest.class)))
      .thenReturn(GetParametersByPathResponse.builder().build());

    final ConfigData actual = ssmConfigProvider.get("path");

    assertTrue(actual.data().isEmpty());
    assertRequestedPaths(expected);

  }

  @Test
  public void should_create_default_ssm_client() {

    // static credentials so the startup credential check resolves without AWS access
    System.setProperty("aws.region", "us-east-1");
    System.setProperty("aws.accessKeyId", "unit-test");
    System.setProperty("aws.secretAccessKey", "unit-test");
    try (final AwsSsmConfigProvider provider = new AwsSsmConfigProvider()) {
      provider.configure(config);
      assertNotNull(provider.getSsmClient());
      assertNotNull(provider.getEnvironment());
    } finally {
      System.clearProperty("aws.region");
      System.clearProperty("aws.accessKeyId");
      System.clearProperty("aws.secretAccessKey");
    }

  }

  @Test
  public void should_get_value() {

    final String paramName = "key";
    final String paramValue = "snapped the frame";

    when(ssmClient.getParametersByPath(any(GetParametersByPathRequest.class)))
      .thenReturn(GetParametersByPathResponse.builder().build(), responseWith("/unit-test/path/" + paramName, paramValue));

    final ConfigData actual = ssmConfigProvider.get("path", Set.of(paramName));

    assertRequestedPaths(List.of("/unit-test/", "/unit-test/path/"));
    assertEquals(Map.of(paramName, paramValue), actual.data());

  }

  @Test
  public void should_get_value_without_environment() {

    final var paramName = "key";
    final var paramValue = "snapped the frame";

    // reconfigure the provided to NOT include environment
    final var newConfig = new HashMap<>(config);
    newConfig.put("addEnvironmentPrefix", "false");
    ssmConfigProvider.configure(newConfig);

    when(ssmClient.getParametersByPath(any(GetParametersByPathRequest.class)))
      .thenReturn(responseWith(paramName, paramValue));

    final var actual = ssmConfigProvider.get("path", Set.of(paramName));

    assertRequestedPaths(List.of("/path/"));
    assertEquals(Map.of(paramName, paramValue), actual.data());

  }

  @Test
  public void should_fail_when_key_is_missing() {

    when(ssmClient.getParametersByPath(any(GetParametersByPathRequest.class)))
      .thenReturn(GetParametersByPathResponse.builder().build(), responseWith("/unit-test/path/present", "value"));

    final ConfigException e = assertThrows(
      ConfigException.class,
      () -> ssmConfigProvider.get("path", Set.of("present", "missing"))
    );

    assertTrue(e.getMessage().contains("[missing]"), e.getMessage());
    assertTrue(e.getMessage().contains("'path'"), e.getMessage());

  }

  @Test
  public void should_not_set_ttl_by_default() {

    when(ssmClient.getParametersByPath(any(GetParametersByPathRequest.class)))
      .thenReturn(GetParametersByPathResponse.builder().build(), responseWith("key", "value"));

    assertNull(ssmConfigProvider.get("path", Set.of("key")).ttl());

  }

  @Test
  public void should_use_configured_ttl() {

    final var newConfig = new HashMap<>(config);
    newConfig.put("ttl", "60000");
    ssmConfigProvider.configure(newConfig);

    when(ssmClient.getParametersByPath(any(GetParametersByPathRequest.class)))
      .thenReturn(GetParametersByPathResponse.builder().build(), responseWith("key", "value"));

    assertEquals(60000L, ssmConfigProvider.get("path", Set.of("key")).ttl());

  }

  @Test
  public void should_use_plugin_class_loader_and_restore_it() throws Exception {

    final ClassLoader original = Thread.currentThread().getContextClassLoader();
    try (final URLClassLoader workerLoader = new URLClassLoader(new URL[0], null)) {
      Thread.currentThread().setContextClassLoader(workerLoader);

      when(ssmClient.getParametersByPath(any(GetParametersByPathRequest.class)))
        .thenAnswer(invocation -> {
          assertSame(
            AwsSsmConfigProvider.class.getClassLoader(),
            Thread.currentThread().getContextClassLoader()
          );
          return GetParametersByPathResponse.builder().build();
        });

      ssmConfigProvider.get("path");

      assertSame(workerLoader, Thread.currentThread().getContextClassLoader());
    } finally {
      Thread.currentThread().setContextClassLoader(original);
    }

  }

  private GetParametersByPathResponse responseWith(final String name, final String value) {
    return GetParametersByPathResponse.builder()
      .parameters(Parameter.builder().name(name).value(value).build())
      .build();
  }

  private void assertRequestedPaths(final List<String> expected) {
    verify(ssmClient, times(expected.size())).getParametersByPath(parameterCaptor.capture());
    final List<GetParametersByPathRequest> allValues = parameterCaptor.getAllValues();
    assertEquals(expected.size(), allValues.size());
    for (int i = 0; i < allValues.size(); i++) {
      assertEquals(expected.get(i), allValues.get(i).path());
    }
  }

}
