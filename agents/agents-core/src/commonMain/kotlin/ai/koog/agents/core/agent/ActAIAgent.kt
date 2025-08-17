@file:OptIn(ExperimentalUuidApi::class)

package ai.koog.agents.core.agent

import ai.koog.agents.core.agent.ActAIAgent.FeatureContext
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.config.AIAgentConfigBase
import ai.koog.agents.core.agent.context.AIAgentLLMContext
import ai.koog.agents.core.agent.context.element.AgentRunInfoContextElement
import ai.koog.agents.core.agent.entity.AIAgentStateManager
import ai.koog.agents.core.agent.entity.AIAgentStorage
import ai.koog.agents.core.environment.GenericAgentEnvironment
import ai.koog.agents.core.feature.AIAgentFeature
import ai.koog.agents.core.feature.AIAgentNonGraphFeature
import ai.koog.agents.core.feature.AIAgentNonGraphPipeline
import ai.koog.agents.core.feature.PromptExecutorProxy
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Represents the core AI agent for processing input and generating output using
 * a defined configuration, toolset, and prompt execution pipeline.
 *
 * @param Input The type of input data expected by the agent.
 * @param Output The type of output data produced by the agent.
 * @param id The unique identifier for the agent instance.
 * @param promptExecutor The executor responsible for processing prompts and interacting with language models.
 * @param agentConfig The configuration for the agent, including the prompt structure and execution parameters.
 * @param toolRegistry The registry of tools available for the agent. Defaults to an empty registry if not specified.
 */
@ExperimentalUuidApi
public class ActAIAgent<Input, Output>(
    public val promptExecutor: PromptExecutor,
    public val agentConfig: AIAgentConfigBase,
    override val id: String = Uuid.random().toString(),
    public val toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
    public val strategy: AIAgentLoopStrategy<Input, Output>,
    public val clock: Clock = Clock.System,
    public val featureContext: FeatureContext.() -> Unit = {}
) : AIAgent<Input, Output> {

    init {
        FeatureContext(this).featureContext()
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }

    private val pipeline = AIAgentNonGraphPipeline()

    private val environment = GenericAgentEnvironment(
        id,
        strategy.name,
        logger,
        toolRegistry,
        pipeline = pipeline
    )

    /**
     * Represents a context for managing and configuring features in an AI agent.
     * Provides functionality to install and configure features into a specific instance of an AI agent.
     */
    public class FeatureContext internal constructor(private val agent: ActAIAgent<*, *>) {
        /**
         * Installs and configures a feature into the current AI agent context.
         *
         * @param feature the feature to be added, defined by an implementation of [AIAgentFeature], which provides specific functionality
         * @param configure an optional lambda to customize the configuration of the feature, where the provided [Config] can be modified
         */
        public fun <Config : FeatureConfig, Feature : Any> install(
            feature: AIAgentNonGraphFeature<Config, Feature>,
            configure: Config.() -> Unit = {}
        ) {
            agent.install(feature, configure)
        }
    }

    private var isRunning = false

    private val runningMutex = Mutex()

    private fun <Config : FeatureConfig, Feature : Any> install(
        feature: AIAgentNonGraphFeature<Config, Feature>,
        configure: Config.() -> Unit
    ) {
        pipeline.install(feature, configure)
    }

    override suspend fun run(agentInput: Input): Output {
        runningMutex.withLock {
            if (isRunning) {
                throw IllegalStateException("Agent is already running")
            }
            isRunning = true
        }

        val runId = Uuid.random().toString()

        val llm = AIAgentLLMContext(
            tools = toolRegistry.tools.map { it.descriptor },
            toolRegistry = toolRegistry,
            prompt = agentConfig.prompt,
            model = agentConfig.model,
            promptExecutor = PromptExecutorProxy(
                executor = promptExecutor,
                pipeline = pipeline,
                runId = runId
            ),
            environment = environment,
            config = agentConfig,
            clock = clock
        )

        val context = AIAgentLoopContext(
            environment,
            id,
            runId,
            agentInput,
            agentConfig,
            llm,
            AIAgentStateManager(),
            storage = AIAgentStorage(),
            strategyName = strategy.name,
            pipeline = pipeline
        )

        val result = withContext(
            AgentRunInfoContextElement(
                agentId = id,
                runId = runId,
                agentConfig = agentConfig,
                strategyName = strategy.name
            )
        ) {
            strategy.execute(context, agentInput)
        }

        runningMutex.withLock {
            isRunning = false
        }

        return result
    }

    override suspend fun close() {
        pipeline.onAgentBeforeClosed(agentId = id)
        pipeline.closeFeaturesStreamProviders()
    }
}

/**
 * Creates a new instance of `LoopAIAgent` to manage and execute AI-driven loops with a specific configuration,
 * tool registry, and strategy for handling operations.
 *
 * @param promptExecutor The `PromptExecutor` responsible for processing language model prompts and managing interactions with the AI model.
 * @param agentConfig The `AIAgentConfigBase` defining the configuration for the AI agent, including prompts, model details, and iteration limits.
 * @param toolRegistry A `ToolRegistry` specifying the tools available to the agent. If no tools are provided, it defaults to an empty registry.
 * @param loop A suspendable lambda function representing the custom loop execution strategy. It takes an input of type `Input` and a context of type `AIAgentLoopContext`, and returns
 *  an output of type `Output`.
 * @return A `LoopAIAgent` instance initialized with the specified prompt executor, configuration, tool registry, and loop strategy.
 */
public fun <Input, Output> actAIAgent(
    promptExecutor: PromptExecutor,
    agentConfig: AIAgentConfigBase,
    toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
    loop: suspend AIAgentLoopContext.(input: Input) -> Output
): AIAgent<Input, Output> {
    return ActAIAgent(
        promptExecutor = promptExecutor,
        agentConfig = agentConfig,
        toolRegistry = toolRegistry,
        strategy = loopStrategy(
            loop = loop
        )
    )
}

/**
 * Creates a `LoopAIAgent` that continuously performs actions based on the input, a context, and a defined loop logic.
 *
 * @param model The large language model to be used for the agent's configuration.
 * @param promptExecutor The executor responsible for processing prompts with the language model.
 * @param prompt The system-level prompt used to configure the agent's behavior.
 * @param toolRegistry A registry containing tools available to the agent during execution. Defaults to `ToolRegistry.EMPTY`.
 * @param loop A suspendable function representing the loop logic. It takes an input and an `AIAgentLoopContext`
 *        and produces an output.
 * @return A configured `LoopAIAgent` instance capable of processing the specified logic using the provided inputs.
 */
public fun <Input, Output> actAIAgent(
    prompt: String,
    promptExecutor: PromptExecutor,
    model: LLModel = OpenAIModels.Chat.GPT4o,
    toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
    featureContext: FeatureContext.() -> Unit = {},
    loop: suspend AIAgentLoopContext.(input: Input) -> Output,
): AIAgent<Input, Output> {
    return ActAIAgent(
        promptExecutor = promptExecutor,
        agentConfig = AIAgentConfig.withSystemPrompt(prompt, model),
        toolRegistry = toolRegistry,
        strategy = loopStrategy(loop = loop),
        featureContext = featureContext
    )
}
