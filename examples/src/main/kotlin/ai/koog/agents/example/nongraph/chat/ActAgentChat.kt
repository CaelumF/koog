package ai.koog.agents.example.nongraph.chat

import ai.koog.agents.core.agent.actAIAgent
import ai.koog.agents.core.agent.requestLLM
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.llm.OllamaModels
import kotlinx.coroutines.runBlocking
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
fun main(): Unit = runBlocking {
    val loopAgent = actAIAgent<String, Unit>(
        prompt = "You're a simple chat agent",
        promptExecutor = simpleOllamaAIExecutor(),
        model = OllamaModels.Meta.LLAMA_3_2
    ) {
        var userResponse = it
        while (userResponse != "/bye") {
            val responses = requestLLM(userResponse)
            println(responses.content)
            userResponse = readln()
        }
    }

    println("Simple chat agent started\nUse /bye to quit\nEnter your message:")
    val input = readln()
    loopAgent.run(input)
}
