# Introduction

This plugin provides integration with the AWS SSM Parameter store service.

> TODO: Confluent Hub installation instructions will be added here if/when 
> the plugin is published.

To build the plugin, run:

```shell
mvn clean package
```

The zip file you need to deploy is available under `target/components/packages/`. 

You can manually extract this zip file which includes all dependencies. All 
the dependencies that are required to deploy the plugin are under 
`target/kafka-connect-target` as well. Make sure that you include all the 
dependencies that are required to run the plugin.

1. Create a directory under the `plugin.path` on your Connect worker.
2. Copy all the dependencies under the newly created subdirectory.
3. Restart the Connect worker.

This config provider is used to retrieve secrets from the AWS SSM Parameter Store service.

> NOTE: Config providers can be used with anything that supports the `AbstractConfig` 
> base class that is shipped with Apache Kafka.

## Configuration

The following configuration options are available:

- `region`
    - The AWS region to use. If not specified, the default region will be used.
- `ttl`
    - The time to live for the configuration values (in ms). If not specified, 
        the default value of 3600000 ms (1 hour) will be used.
- `addEnvironmentPrefix`
    - Whether to add the environment prefix to the parameter name. If not 
        specified, the default value of `true` will be used.
- `environment`
    - An optional environment to be used as a prefix for parameter names. If 
        `addEnvironmentPrefix` is `false`, this value is ignored.

> NOTE: If you deploy this with the prefix "ssm", then these properties need 
> to be prefixed with "`config.providers.ssm.param.`".