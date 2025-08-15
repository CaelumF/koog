package ai.koog.agents.core.feature

import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.context.AIAgentContext
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.agent.entity.AIAgentNodeBase
import ai.koog.agents.core.agent.entity.AIAgentStorageKey
import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.environment.AIAgentEnvironment
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.handler.AfterNodeHandler
import ai.koog.agents.core.feature.handler.AgentHandler
import ai.koog.agents.core.feature.handler.AgentTransformEnvironmentContext
import ai.koog.agents.core.feature.handler.BeforeAgentStartedHandler
import ai.koog.agents.core.feature.handler.BeforeNodeHandler
import ai.koog.agents.core.feature.handler.ExecuteNodeHandler
import ai.koog.agents.core.feature.handler.GraphAgentStartContext
import ai.koog.agents.core.feature.handler.NodeAfterExecuteContext
import ai.koog.agents.core.feature.handler.NodeBeforeExecuteContext
import ai.koog.agents.core.feature.handler.NodeExecutionErrorContext
import ai.koog.agents.core.feature.handler.NodeExecutionErrorHandler
import kotlin.reflect.KType

/**
 * Represents a pipeline for AI agent graph execution, extending the functionality of `AIAgentPipeline`.
 * This class manages the execution of specific nodes in the pipeline using registered handlers.
 */
public class AIAgentGraphPipeline : AIAgentPipeline() {
    /**
     * Map of node execution handlers registered for different features.
     * Keys are feature storage keys, values are node execution handlers.
     */
    private val executeNodeHandlers: MutableMap<AIAgentStorageKey<*>, ExecuteNodeHandler> = mutableMapOf()

    /**
     * Notifies all registered node handlers before a node is executed.
     *
     * @param node The node that is about to be executed
     * @param context The agent context in which the node is being executed
     * @param input The input data for the node execution
     */
    public suspend fun onBeforeNode(
        node: AIAgentNodeBase<*, *>,
        context: AIAgentContext,
        input: Any?,
        inputType: KType
    ) {
        val eventContext = NodeBeforeExecuteContext(node, context, input, inputType)
        executeNodeHandlers.values.forEach { handler -> handler.beforeNodeHandler.handle(eventContext) }
    }

    /**
     * Notifies all registered node handlers after a node has been executed.
     *
     * @param node The node that was executed
     * @param context The agent context in which the node was executed
     * @param input The input data that was provided to the node
     * @param output The output data produced by the node execution
     */
    public suspend fun onAfterNode(
        node: AIAgentNodeBase<*, *>,
        context: AIAgentContext,
        input: Any?,
        output: Any?,
        inputType: KType,
        outputType: KType,
    ) {
        val eventContext = NodeAfterExecuteContext(node, context, input, output, inputType, outputType)
        executeNodeHandlers.values.forEach { handler -> handler.afterNodeHandler.handle(eventContext) }
    }

    /**
     * Handles errors occurring during the execution of a node by invoking all registered node execution error handlers.
     *
     * @param node The instance of the node where the error occurred.
     * @param context The context associated with the AI agent executing the node.
     * @param throwable The exception or error that occurred during node execution.
     */
    public suspend fun onNodeExecutionError(
        node: AIAgentNodeBase<*, *>,
        context: AIAgentContext,
        throwable: Throwable
    ) {
        val eventContext = NodeExecutionErrorContext(node, context, throwable)
        executeNodeHandlers.values.forEach { handler -> handler.nodeExecutionErrorHandler.handle(eventContext) }
    }

    /**
     * Intercepts node execution before it starts.
     *
     * @param handle The handler that processes before-node events
     *
     * Example:
     * ```
     * pipeline.interceptBeforeNode(InterceptContext) { eventContext ->
     *     logger.info("Node ${eventContext.node.name} is about to execute with input: ${eventContext.input}")
     * }
     * ```
     */
    public fun <TFeature : Any> interceptBeforeNode(
        interceptContext: InterceptContext<TFeature>,
        handle: suspend TFeature.(eventContext: NodeBeforeExecuteContext) -> Unit
    ) {
        val existingHandler = executeNodeHandlers.getOrPut(interceptContext.feature.key) { ExecuteNodeHandler() }

        existingHandler.beforeNodeHandler = BeforeNodeHandler { eventContext: NodeBeforeExecuteContext ->
            with(interceptContext.featureImpl) { handle(eventContext) }
        }
    }

    /**
     * Intercepts node execution after it completes.
     *
     * @param handle The handler that processes after-node events
     *
     * Example:
     * ```
     * pipeline.interceptAfterNode(InterceptContext) { eventContext ->
     *     logger.info("Node ${eventContext.node.name} executed with input: ${eventContext.input} and produced output: ${eventContext.output}")
     * }
     * ```
     */
    public fun <TFeature : Any> interceptAfterNode(
        interceptContext: InterceptContext<TFeature>,
        handle: suspend TFeature.(eventContext: NodeAfterExecuteContext) -> Unit
    ) {
        val existingHandler = executeNodeHandlers.getOrPut(interceptContext.feature.key) { ExecuteNodeHandler() }

        existingHandler.afterNodeHandler = AfterNodeHandler { eventContext: NodeAfterExecuteContext ->
            with(interceptContext.featureImpl) { handle(eventContext) }
        }
    }

    /**
     * Intercepts and handles node execution errors for a given feature.
     *
     * @param interceptContext The context containing the feature and its implementation required for interception.
     * @param handle A suspend function that processes the node execution error within the scope of the provided feature.
     *
     * Example:
     * ```
     * pipeline.interceptNodeExecutionError(InterceptContext) { eventContext ->
     *     logger.error("Node ${eventContext.node.name} execution failed with error: ${eventContext.throwable}")
     * }
     * ```
     */
    public fun <TFeature : Any> interceptNodeExecutionError(
        interceptContext: InterceptContext<TFeature>,
        handle: suspend TFeature.(eventContext: NodeExecutionErrorContext) -> Unit
    ) {
        val existingHandler = executeNodeHandlers.getOrPut(interceptContext.feature.key) { ExecuteNodeHandler() }

        existingHandler.nodeExecutionErrorHandler =
            NodeExecutionErrorHandler { eventContext: NodeExecutionErrorContext ->
                with(interceptContext.featureImpl) { handle(eventContext) }
            }
    }

    /**
     * Installs a feature into the pipeline with the provided configuration.
     *
     * This method initializes the feature with a custom configuration and registers it in the pipeline.
     * The feature's message processors are initialized during installation.
     *
     * @param Config The type of the feature configuration
     * @param Feature The type of the feature being installed
     * @param feature The feature implementation to be installed
     * @param configure A lambda to customize the feature configuration
     */
    public fun <Config : FeatureConfig, Feature : Any> install(
        feature: AIAgentGraphFeature<Config, Feature>,
        configure: Config.() -> Unit
    ) {
        val config = feature.createInitialConfig().apply { configure() }
        feature.install(
            config = config,
            pipeline = this,
        )

        registeredFeatures[feature.key] = config
    }

    /**
     * Notifies all registered handlers that an agent has started execution.
     *
     * @param runId The unique identifier for the agent run
     * @param agent The agent instance for which the execution has started
     * @param strategy The strategy being executed by the agent
     */
    @OptIn(InternalAgentsApi::class)
    public suspend fun onBeforeAgentStarted(
        runId: String,
        agent: GraphAIAgent<*, *>,
        strategy: AIAgentGraphStrategy<*, *>,
        context: AIAgentContext
    ) {
        agentHandlers.values.forEach { handler ->
            val eventContext =
                GraphAgentStartContext(
                    agent = agent,
                    runId = runId,
                    strategy = strategy,
                    feature = handler.feature,
                    context = context
                )
            handler.handleBeforeAgentStartedUnsafe(eventContext)
        }
    }

    /**
     * Transforms the agent environment by applying all registered environment transformers.
     *
     * This method allows features to modify or enhance the agent's environment before it starts execution.
     * Each registered handler can apply its own transformations to the environment in sequence.
     *
     * @param strategy The strategy associated with the agent
     * @param agent The agent instance for which the environment is being transformed
     * @param baseEnvironment The initial environment to be transformed
     * @return The transformed environment after all handlers have been applied
     */
    public fun transformEnvironment(
        strategy: AIAgentGraphStrategy<*, *>,
        agent: GraphAIAgent<*, *>,
        baseEnvironment: AIAgentEnvironment
    ): AIAgentEnvironment {
        return agentHandlers.values.fold(baseEnvironment) { environment, handler ->
            val eventContext =
                AgentTransformEnvironmentContext(strategy = strategy, agent = agent, feature = handler.feature)
            handler.transformEnvironmentUnsafe(eventContext, environment)
        }
    }

    /**
     * Intercepts on before an agent started to modify or enhance the agent.
     *
     * @param handle The handler that processes agent creation events
     *
     * Example:
     * ```
     * pipeline.interceptBeforeAgentStarted(InterceptContext) {
     *     readStages { stages ->
     *         // Inspect agent stages
     *     }
     * }
     * ```
     */
    public fun <TFeature : Any> interceptBeforeAgentStarted(
        context: InterceptContext<TFeature>,
        handle: suspend (GraphAgentStartContext<TFeature>) -> Unit
    ) {
        @Suppress("UNCHECKED_CAST")
        val existingHandler: AgentHandler<TFeature> =
            agentHandlers.getOrPut(context.feature.key) { AgentHandler(context.featureImpl) } as? AgentHandler<TFeature>
                ?: return

        existingHandler.beforeAgentStartedHandler = BeforeAgentStartedHandler { context ->
            handle(context)
        }
    }
}
