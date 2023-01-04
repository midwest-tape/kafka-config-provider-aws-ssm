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
package com.github.jcustenborder.kafka.config.aws;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementClientBuilder;

class SecretsManagerFactoryImpl implements SecretsManagerFactory {

  @Override
  public AWSSimpleSystemsManagement create(SecretsManagerConfigProviderConfig config) {

    AWSSimpleSystemsManagementClientBuilder builder = AWSSimpleSystemsManagementClientBuilder.standard();

    if (null != config.region && !config.region.isEmpty()) {
      builder = builder.withRegion(config.region);
    }
    if (null != config.credentials) {
      builder = builder.withCredentials(new AWSStaticCredentialsProvider(config.credentials));
    }

    return builder.build();

  }

}