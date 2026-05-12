package com.michaelsgroi.test.extensions.resourceleak

import com.amazonaws.services.dynamodbv2.local.main.ServerRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import java.net.URI

/**
 * Leaks 250 DynamoDB tables. The associated scenario IT runs this class under the
 * leak detector and asserts the resulting raw report names all 250 — proving that
 * `DynamoDbLocalTableMonitor` paginates past the AWS SDK's 100-tables-per-page cap.
 *
 * Lives under `integration-tests/ddb` (rather than `library/`) because it requires
 * a running DynamoDB Local instance.
 */
class DynamoDbPaginationLeakingTest {
    @Test
    fun `leak tables to exercise listTables pagination`() {
        repeat(EXPECTED_TABLE_COUNT) { i ->
            client.createTable { builder ->
                builder
                    .tableName("pagination-test-table-%04d".format(i))
                    .keySchema(
                        KeySchemaElement
                            .builder()
                            .attributeName("id")
                            .keyType(KeyType.HASH)
                            .build(),
                    ).attributeDefinitions(
                        AttributeDefinition
                            .builder()
                            .attributeName("id")
                            .attributeType(ScalarAttributeType.S)
                            .build(),
                    ).provisionedThroughput(
                        ProvisionedThroughput
                            .builder()
                            .readCapacityUnits(1)
                            .writeCapacityUnits(1)
                            .build(),
                    )
            }
        }
        // Sanity: all created. (The leak detector itself runs separately and asserts on the report.)
        assertEquals(
            EXPECTED_TABLE_COUNT,
            client
                .listTablesPaginator()
                .tableNames()
                .toList()
                .size,
        )
    }

    companion object {
        // The IT runs with `ddbtables.list.page.size=2`, so 5 tables forces the
        // monitor's listTablesPaginator() through 3 pages — exercising pagination
        // without paying the wall-clock cost of leaking many tables.
        const val EXPECTED_TABLE_COUNT = 5
        private val PORT = System.getProperty("EMBEDDED_DYNAMO_PORT", "8888").toInt()
        private val server =
            ServerRunner.createServerFromCommandLineArgs(
                arrayOf("-inMemory", "-port", PORT.toString()),
            )
        private lateinit var client: DynamoDbClient

        @BeforeAll
        @JvmStatic
        fun setUp() {
            server.safeStart()
            client =
                DynamoDbClient
                    .builder()
                    .endpointOverride(URI.create("http://localhost:$PORT"))
                    .region(Region.US_EAST_1)
                    .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create("dummy", "dummy")),
                    ).httpClient(UrlConnectionHttpClient.builder().build())
                    .build()
        }
    }
}
