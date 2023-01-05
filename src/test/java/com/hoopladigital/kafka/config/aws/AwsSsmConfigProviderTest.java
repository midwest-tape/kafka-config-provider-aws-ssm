package com.hoopladigital.kafka.config.aws;

import org.apache.kafka.common.config.ConfigData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
  public void should_blow_with_if_no_parameters_found() {

    final List<String> expected = Arrays.asList(
      "/global/",
      "/unit-test/",
      "/unit-test/path/"
    );

    // just return empty objects here
    when(ssmClient.getParametersByPath(any(GetParametersByPathRequest.class)))
      .thenReturn(GetParametersByPathResponse.builder().build());

    try {
      // no return value needed here:
      ssmConfigProvider.get("path");
    } catch (final Exception e) {
      assertEquals(
        "Parameter 'test/prefix/key' not found; for more information enable trace logging for com.hoopladigital.SsmConfigProvider",
        e.getMessage()
      );
    }

    verify(ssmClient, times(expected.size())).getParametersByPath(parameterCaptor.capture());
    final List<GetParametersByPathRequest> allValues = parameterCaptor.getAllValues();
    assertEquals(expected.size(), allValues.size());
    for (int i = 0; i < allValues.size(); i++) {
      final GetParametersByPathRequest value = allValues.get(i);
      assertEquals(expected.get(i), value.path());
    }

  }

  @Test
  public void should_create_default_ssm_client() {

    System.setProperty("aws.region", "us-east-1");
    try (final AwsSsmConfigProvider provider = new AwsSsmConfigProvider()) {
      provider.configure(config);
      assertNotNull(provider.getSsmClient());
      assertNotNull(provider.getEnvironment());
    } finally {
      System.clearProperty("aws.region");
    }

  }

  @Test
  public void should_get_value() {

    final List<String> expected = Arrays.asList(
      "/global/",
      "/unit-test/",
      "/unit-test/path/"
    );
    final String paramName = "key";
    final String paramValue = "snapped the frame";

    final GetParametersByPathResponse pathValues = GetParametersByPathResponse.builder().build();
    final GetParametersByPathResponse globalValues = GetParametersByPathResponse.builder().build();
    final GetParametersByPathResponse response = GetParametersByPathResponse.builder()
      .parameters(
        Parameter.builder()
          .name(paramName)
          .value(paramValue)
          .build()
      )
      .build();

    when(ssmClient.getParametersByPath(any(GetParametersByPathRequest.class)))
      .thenReturn(pathValues, response, globalValues);

    final Set<String> foo = new HashSet<>();
    foo.add(paramName);

    final ConfigData actual = ssmConfigProvider.get("path", foo);

    verify(ssmClient, times(expected.size())).getParametersByPath(parameterCaptor.capture());
    final List<GetParametersByPathRequest> allValues = parameterCaptor.getAllValues();
    assertEquals(expected.size(), allValues.size());
    for (int i = 0; i < allValues.size(); i++) {
      final GetParametersByPathRequest value = allValues.get(i);
      assertEquals(expected.get(i), value.path());
    }
    Map<String, String> exxpected = new HashMap<>();
    exxpected.put(paramName, paramValue);
    assertEquals(new ConfigData(exxpected).data(), actual.data());

  }

	@Test
	public void should_get_value_without_environment() {

		final var expected = List.of(
			"/global/",
			"/path/"
		);

		final var paramName = "key";
		final var paramValue = "snapped the frame";

		// reconfigure the provided to NOT include environment
		final var newConfig = new HashMap<>(config);
		newConfig.put("addEnvironmentPrefix","false");
		ssmConfigProvider.configure(newConfig);

		final var pathValues = GetParametersByPathResponse.builder().build();
		final var globalValues = GetParametersByPathResponse.builder().build();
        final var response = GetParametersByPathResponse.builder()
            .parameters(
                Parameter.builder()
                    .name(paramName)
                    .value(paramValue)
                    .build()
            )
            .build();

		when(ssmClient.getParametersByPath(any(GetParametersByPathRequest.class)))
			.thenReturn(pathValues, response, globalValues);

		final var actual = ssmConfigProvider.get("path", Set.of(paramName));

		verify(ssmClient, times(expected.size())).getParametersByPath(parameterCaptor.capture());
		final var allValues = parameterCaptor.getAllValues();
		assertEquals(expected.size(), allValues.size());
		for (int i = 0; i < allValues.size(); i++) {
			final var value = allValues.get(i);
			assertEquals(expected.get(i), value.path());
		}

		assertEquals(new ConfigData(Map.of(paramName, paramValue)).data(), actual.data());

	}


}
