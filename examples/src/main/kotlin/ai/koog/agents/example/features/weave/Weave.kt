package ai.koog.agents.example.features.weave

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.example.ApiKeyService
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import io.opentelemetry.exporter.logging.LoggingSpanExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.coroutines.runBlocking
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 *  Example of Koog agents tracing to [W&B Weave](https://wandb.ai/site/weave/)
 * 
 * Agent traces are exported to:
 * - Console using [LoggingSpanExporter]
 * - Weave OTLP endpoint instance using [OtlpHttpSpanExporter]
 *
 * To run this example:
 *  1. Set up a Weave account at [https://wandb.ai](https://wandb.ai)
 *  2. Get your API key from [https://wandb.ai/authorize](https://wandb.ai/authorize) and expose it as a `WEAVE_API_KEY` environment variable.
 *  3. Find your entity name by visiting your W&B dashboard at [https://wandb.ai/home](https://wandb.ai/home) and checking the Teams field in the left sidebar,
 *  expose it as a `WEAVE_ENTITY` environment variable.
 *  4. Define a name for your project and expose it as a `WEAVE_PROJECT_NAME` environment variable.
 *  You don't have to create a project beforehand.
 *
 * @see <a href="https://weave-docs.wandb.ai/guides/tracking/otel/">Weave OpenTelemetry Docs</a>
 */
fun main() = runBlocking {
    val weaveUrl = System.getenv()["WEAVE_URL"] ?: "https://trace.wandb.ai"
    val entity = System.getenv()["WEAVE_ENTITY"] ?: throw IllegalArgumentException("WEAVE_ENTITY is not set")
    val projectName = System.getenv()["WEAVE_PROJECT_NAME"] ?: "koog-tracing"
    val apiKey = System.getenv()["WEAVE_API_KEY"] ?: throw IllegalArgumentException("WEAVE_API_KEY is not set")

    val agent = AIAgent(
        executor = simpleOpenAIExecutor(ApiKeyService.openAIApiKey),
        llmModel = OpenAIModels.Reasoning.GPT4oMini,
        systemPrompt = "You are a code assistant. Provide concise code examples."
    ) {
        install(OpenTelemetry) {
            addSpanExporter(
                createWeaveSpanExporter(
                    weaveUrl,
                    entity,
                    projectName,
                    apiKey,
                )
            )
        }
    }

    println("Running agent with Weave tracing")

    val result = agent.run("Tell me a joke about programming")

    println("Result: $result\nSee traces on https://wandb.ai/$entity/$projectName/weave/traces")
}

private fun createWeaveSpanExporter(
    weaveOtelUrl: String,
    entity: String,
    projectName: String,
    apiKey: String,
): SpanExporter {
    val auth = Base64.getEncoder().encodeToString("api:$apiKey".toByteArray(Charsets.UTF_8))

    return OtlpHttpSpanExporter.builder()
        .setTimeout(30, TimeUnit.SECONDS)
        .setEndpoint("$weaveOtelUrl/otel/v1/traces")
        .addHeader("project_id", "$entity/$projectName")
        .addHeader("Authorization", "Basic $auth")
        .build()
}