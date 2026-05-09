package com.salesforce.test.extensions.resourceleak

import com.amazonaws.services.dynamodbv2.local.main.ServerRunner
import org.junit.jupiter.api.Assertions.assertTrue
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

class DynamoDbTableLeakingTest {
    @Test
    fun `test that leaks a DynamoDB table`() {
        client.createTable { builder ->
            builder
                .tableName("leaked-by-DynamoDbTableLeakingTest")
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

        val tables = client.listTables().tableNames()
        assertTrue(tables.contains("leaked-by-DynamoDbTableLeakingTest"))
    }

    companion object {
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
