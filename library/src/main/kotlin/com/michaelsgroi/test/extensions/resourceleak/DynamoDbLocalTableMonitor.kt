package com.michaelsgroi.test.extensions.resourceleak

import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.ListTablesRequest
import java.net.URI

class DynamoDbLocalTableMonitor(
    port: Int = System.getProperty("EMBEDDED_DYNAMO_PORT", "8888").toInt(),
    /** Page size for `listTables`. `null` = AWS SDK default (100). Tests can override
     *  via `resource.leak.detector.ddbtables.list.page.size` to force pagination. */
    private val pageSize: Int? = null,
) : DiscreteResourceMonitor {
    private val log = LoggerFactory.getLogger(javaClass)
    override val resourceType = ResourceType.DDBTABLES
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
            // listTables() returns up to 100 tables per page by default; paginate to
            // capture all. Page size is configurable so tests can force multi-page paths.
            val request =
                ListTablesRequest
                    .builder()
                    .apply {
                        pageSize?.let { limit(it) }
                    }.build()
            client
                .listTablesPaginator(request)
                .tableNames()
                .stream()
                .map { ResourceId.DynamoDbTableId(it) }
                .toList()
                .toSet()
        } catch (e: Exception) {
            log.debug("Failed to gather DynamoDB Local table resources", e)
            emptySet()
        }
}
