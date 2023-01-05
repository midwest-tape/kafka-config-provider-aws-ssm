/**
 * Copyright © 2021 Jeremy Custenborder (jcustenborder@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hoopladigital.kafka.config.aws;

import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.model.GetParametersByPathRequest;
import com.amazonaws.services.simplesystemsmanagement.model.GetParametersByPathResult;
import com.amazonaws.services.simplesystemsmanagement.model.Parameter;
import org.apache.kafka.common.config.ConfigData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

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
  private AWSSimpleSystemsManagement ssmClient;

  @Captor
  private ArgumentCaptor<GetParametersByPathRequest> parameterCaptor;

  private final Map<String, String> config = new HashMap<>();

  @BeforeEach
  public void setup() {
    config.put("environment", "unit-test");
    try(final AutoCloseable ignored = openMocks(this)) {
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
      .thenReturn(new GetParametersByPathResult());

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
      assertEquals(expected.get(i), value.getPath());
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

    final GetParametersByPathResult pathValues = new GetParametersByPathResult();
    final GetParametersByPathResult globalValues = new GetParametersByPathResult();
    final GetParametersByPathResult response = new GetParametersByPathResult().withParameters(Arrays.asList(
      new Parameter()
        .withName(paramName)
        .withValue(paramValue)
    ));

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
      assertEquals(expected.get(i), value.getPath());
    }
    Map<String,String> exxpected = new HashMap<>();
    exxpected.put(paramName, paramValue);
    assertEquals(new ConfigData(exxpected).data(), actual.data());

  }
/*
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

		final var pathValues = new GetParametersByPathResult();
		final var globalValues = new GetParametersByPathResult();
		final var response = new GetParametersByPathResult().withParameters(List.of(
			new Parameter()
				.withName(paramName)
				.withValue(paramValue)
		));

		when(ssmClient.getParametersByPath(any(GetParametersByPathRequest.class)))
			.thenReturn(pathValues, response, globalValues);

		final var actual = ssmConfigProvider.get("path", Set.of(paramName));

		verify(ssmClient, times(expected.size())).getParametersByPath(parameterCaptor.capture());
		final var allValues = parameterCaptor.getAllValues();
		assertEquals(expected.size(), allValues.size());
		for (int i = 0; i < allValues.size(); i++) {
			final var value = allValues.get(i);
			assertEquals(expected.get(i), value.getPath());
		}

		assertEquals(new ConfigData(Map.of(paramName, paramValue)).data(), actual.data());

	}
*/

}
