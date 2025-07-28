package ai.koog.integration.tests

import aws.sdk.kotlin.services.bedrock.BedrockClient
import aws.sdk.kotlin.services.bedrock.listFoundationModels
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.OS
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies that the credentials provided to the build can access the Bedrock control-plane
 * API (ListFoundationModels). If the call fails we abort early instead of waiting for a
 * full prompt execution to blow up.
 *
 * This test only runs when AWS credentials are available (typically in heavy-tests workflow).
 */
class BedrockCredentialsSmokeTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "KOOG_HEAVY_TESTS", matches = "true")
    fun listFoundationModelsWorks() = runBlocking {
        val region = System.getenv("AWS_REGION") ?: "us-west-2"

        BedrockClient { this.region = region }.use { bedrock ->
            val resp = bedrock.listFoundationModels { }
            assertTrue(
                (resp.modelSummaries?.isNotEmpty()) == true,
                "Bedrock ListFoundationModels returned no models – credentials/region might be wrong"
            )
        }
    }
}
