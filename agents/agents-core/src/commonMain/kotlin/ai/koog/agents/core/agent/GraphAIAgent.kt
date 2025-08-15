@file:OptIn(InternalAgentsApi::class)

package ai.koog.agents.core.agent

import ai.koog.agents.core.agent.config.AIAgentConfigBase
import ai.koog.agents.core.agent.context.AIAgentGraphContext
import ai.koog.agents.core.agent.context.AIAgentLLMContext
import ai.koog.agents.core.agent.context.element.AgentRunInfoContextElement
import ai.koog.agents.core.agent.context.getAgentContextData
import ai.koog.agents.core.agent.context.removeAgentContextData
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.agent.entity.AIAgentStateManager
import ai.koog.agents.core.agent.entity.AIAgentStorage
import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.environment.GenericAgentEnvironment
import ai.koog.agents.core.feature.AIAgentFeature
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.AIAgentGraphPipeline
import ai.koog.agents.core.feature.PromptExecutorProxy
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.utils.Closeable
import ai.koog.prompt.executor.model.PromptExecutor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlin.reflect.KType
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Represents an implementation of an AI agent that provides functionalities to execute prompts,
 * manage tools, handle agent pipelines, and interact with various configurable strategies and features.
 *
 * The agent operates within a coroutine scope and leverages a tool registry and feature context
 * to enable dynamic additions or configurations during its lifecycle. Its behavior is driven
 * by a local agent strategy and executed via a prompt executor.
 *
 * @param Input Type of agent input.
 * @param Output Type of agent output.
 *
 * @property inputType [KType] representing [Input] - agent input.
 * @property outputType [KType] representing [Output] - agent output.
 * @property promptExecutor Executor used to manage and execute prompt strings.
 * @property strategy Strategy defining the local behavior of the agent.
 * @property agentConfig Configuration details for the local agent that define its operational parameters.
 * @property toolRegistry Registry of tools the agent can interact with, defaulting to an empty registry.
 * @property installFeatures Lambda for installing additional features within the agent environment.
 * @property clock The clock used to calculate message timestamps
 * @constructor Initializes the AI agent instance and prepares the feature context and pipeline for use.
 */
@OptIn(ExperimentalUuidApi::class)
public open class GraphAIAgent<Input, Output>(
    public val inputType: KType,
    public val outputType: KType,
    public val promptExecutor: PromptExecutor,
    private val strategy: AIAgentGraphStrategy<Input, Output>,
    public val agentConfig: AIAgentConfigBase,
    override val id: String = Uuid.random().toString(),
    public val toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
    public val clock: Clock = Clock.System,
    private val installFeatures: FeatureContext.() -> Unit = {},
) : AIAgent<Input, Output>, Closeable {

    private companion object {
        private val logger = KotlinLogging.logger {}
    }

    private val pipeline = AIAgentGraphPipeline()

    private val environment = GenericAgentEnvironment(
        id,
        strategy.name,
        logger,
        toolRegistry,
        pipeline = pipeline
    )

    /**
     * The context for adding and configuring features in a Kotlin AI Agent instance.
     *
     * Note: The method is used to hide internal install() method from a public API to prevent
     *       calls in an [AIAgent] instance, like `agent.install(MyFeature) { ... }`.
     *       This makes the API a bit stricter and clear.
     */
    public class FeatureContext internal constructor(private val agent: GraphAIAgent<*, *>) {
        /**
         * Installs and configures a feature into the current AI agent context.
         *
         * @param feature the feature to be added, defined by an implementation of [AIAgentFeature], which provides specific functionality
         * @param configure an optional lambda to customize the configuration of the feature, where the provided [Config] can be modified
         */
        public fun <Config : FeatureConfig, Feature : Any> install(
            feature: AIAgentGraphFeature<Config, Feature>,
            configure: Config.() -> Unit = {}
        ) {
            agent.install(feature, configure)
        }
    }

    private var isRunning = false

    private val runningMutex = Mutex()

    init {
        FeatureContext(this).installFeatures()
    }

    override suspend fun run(agentInput: Input): Output {
        runningMutex.withLock {
            if (isRunning) {
                throw IllegalStateException("Agent is already running")
            }

            isRunning = true
        }

        pipeline.prepareFeatures()

        val sessionUuid = Uuid.random()
        val runId = sessionUuid.toString()

        return withContext(
            AgentRunInfoContextElement(
                agentId = id,
                runId = runId,
                agentConfig = agentConfig,
                strategyName = strategy.name
            )
        ) {
            val stateManager = AIAgentStateManager()
            val storage = AIAgentStorage()

            // Environment (initially equal to the current agent), transformed by some features
            //   (ex: testing feature transforms it into a MockEnvironment with mocked tools)
            val preparedEnvironment =
                pipeline.transformEnvironment(strategy = strategy, agent = this@GraphAIAgent, baseEnvironment = environment)

            val agentContext = AIAgentGraphContext(
                environment = preparedEnvironment,
                agentInput = agentInput,
                agentInputType = inputType,
                config = agentConfig,
                llm = AIAgentLLMContext(
                    tools = toolRegistry.tools.map { it.descriptor },
                    toolRegistry = toolRegistry,
                    prompt = agentConfig.prompt,
                    model = agentConfig.model,
                    promptExecutor = PromptExecutorProxy(
                        executor = promptExecutor,
                        pipeline = pipeline,
                        runId = runId
                    ),
                    environment = preparedEnvironment,
                    config = agentConfig,
                    clock = clock
                ),
                stateManager = stateManager,
                storage = storage,
                runId = runId,
                strategyName = strategy.name,
                pipeline = pipeline,
                agentId = id,
            )

            logger.debug { formatLog(agentId = id, runId = runId, message = "Starting agent execution") }

            pipeline.onBeforeAgentStarted(
                runId = runId,
                agent = this@GraphAIAgent,
                strategy = strategy,
                context = agentContext
            )

            setExecutionPointIfNeeded(agentContext)

            val result = try {
                var strategyResult = strategy.execute(context = agentContext, input = agentInput)
                while (strategyResult == null && agentContext.getAgentContextData() != null) {
                    setExecutionPointIfNeeded(agentContext)
                    strategyResult = strategy.execute(context = agentContext, input = agentInput)
                }
                strategyResult
            } catch (e: Throwable) {
                logger.error(e) { "Execution exception reported by server!" }
                pipeline.onAgentRunError(agentId = id, runId = runId, throwable = e)
                throw e
            }

            logger.debug { formatLog(agentId = id, runId = runId, message = "Finished agent execution") }
            pipeline.onAgentFinished(agentId = id, runId = runId, result = result, resultType = outputType)

            runningMutex.withLock {
                isRunning = false
            }

            return@withContext result ?: error("result is null")
        }
    }

    private suspend fun setExecutionPointIfNeeded(
        agentContext: AIAgentGraphContext
    ) {
        val additionalContextData = agentContext.getAgentContextData() ?: return

        additionalContextData.let { contextData ->
            val nodeId = contextData.nodeId
            strategy.setExecutionPoint(nodeId, contextData.lastInput)
            val messages = contextData.messageHistory
            agentContext.llm.withPrompt {
                this.withMessages { (messages).sortedBy { m -> m.metaInfo.timestamp } }
            }
        }

        agentContext.removeAgentContextData()
    }

    override suspend fun close() {
        pipeline.onAgentBeforeClosed(agentId = id)
        pipeline.closeFeaturesStreamProviders()
    }

    private fun <Config : FeatureConfig, Feature : Any> install(feature: AIAgentGraphFeature<Config, Feature>, configure: Config.() -> Unit) =
        pipeline.install(feature, configure)

    private fun formatLog(agentId: String, runId: String, message: String): String =
        "[agent id: $agentId, run id: $runId] $message"
}
