package com.salesforce.test.extensions.resourceleak

import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import java.net.URI

class DynamoDbLocalTableMonitor(
    port: Int = System.getProperty("EMBEDDED_DYNAMO_PORT", "8888").toInt(),
) : DiscreteResourceMonitor {
    private val log = LoggerFactory.getLogger(javaClass)
    override val resourceIdClass = ResourceId.DynamoDbTableId::class
    private val client: DynamoDbClient =
        DynamoDbClient
            .builder()
            .endpointOverride(URI.create("http://localhost:$port"))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("dummy", "dummy")),
            ).region(Region.US_EAST_1)
            .build()

    override fun snapshot(): Set<ResourceId> =
        try {
            client
                .listTables()
                .tableNames()
                .map { ResourceId.DynamoDbTableId(it) }
                .toSet()
        } catch (e: Exception) {
            log.debug("Failed to gather DynamoDB Local table resources", e)
            emptySet()
        }
}
