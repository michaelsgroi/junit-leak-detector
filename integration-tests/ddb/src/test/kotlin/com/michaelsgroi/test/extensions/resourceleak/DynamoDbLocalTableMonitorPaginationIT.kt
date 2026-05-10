package com.michaelsgroi.test.extensions.resourceleak

import com.amazonaws.services.dynamodbv2.local.main.ServerRunner
import org.junit.jupiter.api.AfterAll
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
 * `listTables` returns at most 100 tables per page. The monitor must paginate;
 * otherwise a suite that creates >100 tables silently caps its leak count at 100.
 */
class DynamoDbLocalTableMonitorPaginationIT {
    @Test
    fun `snapshot returns all tables when more than 100 exist`() {
        val expected = 250
        repeat(expected) { i ->
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

        val resources = DynamoDbLocalTableMonitor(port = PORT).snapshot()

        assertEquals(expected, resources.size)
    }

    companion object {
        private val PORT = System.getProperty("EMBEDDED_DYNAMO_PORT", "8889").toInt()
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

        @AfterAll
        @JvmStatic
        fun tearDown() {
            server.stop()
        }
    }
}
