package ai.koog.agents.snapshot.providers.file

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileAgentCheckpointStorageProviderTest {
    private lateinit var tempDir: java.nio.file.Path
    private lateinit var provider: JVMFilePersistencyStorageProvider

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("checkpoint-test")
        provider = JVMFilePersistencyStorageProvider(tempDir, "testAgentId")
    }

    @AfterTest
    fun cleanup() {
        // Delete the temp directory and all its contents
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.delete(it) }
    }

    @Test
    fun testSaveAndRetrieveCheckpoint() = runTest {
        // Create a test checkpoint
        val checkpointId = "test-checkpoint"
        val createdAt = Clock.System.now()
        val nodeId = "test-node"
        val lastInput = "test-input"
        val time = Clock.System.now()
        val messageHistory = listOf(
            Message.User("Hello", metaInfo = RequestMetaInfo(time)),
            Message.Assistant("Hi there!", metaInfo = ResponseMetaInfo(time))
        )

        val checkpoint = AgentCheckpointData(
            checkpointId = checkpointId,
            createdAt = createdAt,
            nodeId = nodeId,
            lastInput = lastInput,
            messageHistory = messageHistory
        )

        // Save the checkpoint
        provider.saveCheckpoint(checkpoint)

        // Retrieve all checkpoints for the agent
        val checkpoints = provider.getCheckpoints()
        assertEquals(1, checkpoints.size, "Should have one checkpoint")

        // Verify the retrieved checkpoint
        val retrievedCheckpoint = checkpoints.first()
        assertEquals(checkpointId, retrievedCheckpoint.checkpointId)
        assertEquals(createdAt, retrievedCheckpoint.createdAt)
        assertEquals(nodeId, retrievedCheckpoint.nodeId)
        assertEquals(lastInput, retrievedCheckpoint.lastInput)
        assertEquals(messageHistory.size, retrievedCheckpoint.messageHistory.size)
        
        // Check first message (User)
        val originalUserMsg = messageHistory[0] as Message.User
        val retrievedUserMsg = retrievedCheckpoint.messageHistory[0] as Message.User
        assertEquals(originalUserMsg.content, retrievedUserMsg.content)
        
        // Check second message (Assistant)
        val originalAssistantMsg = messageHistory[1] as Message.Assistant
        val retrievedAssistantMsg = retrievedCheckpoint.messageHistory[1] as Message.Assistant
        assertEquals(originalAssistantMsg.content, retrievedAssistantMsg.content)

        // Test getLatestCheckpoint
        val latestCheckpoint = provider.getLatestCheckpoint()
        assertNotNull(latestCheckpoint, "Latest checkpoint should not be null")
        assertEquals(checkpointId, latestCheckpoint.checkpointId)

        // Create a second checkpoint with a later timestamp
        val laterCheckpointId = "later-checkpoint"
        val laterCreatedAt = Clock.System.now()
        val laterCheckpoint = AgentCheckpointData(
            checkpointId = laterCheckpointId,
            createdAt = laterCreatedAt,
            nodeId = nodeId,
            lastInput = lastInput,
            messageHistory = messageHistory
        )

        // Save the later checkpoint
        provider.saveCheckpoint(laterCheckpoint)

        // Verify that getLatestCheckpoint returns the later checkpoint
        val newLatestCheckpoint = provider.getLatestCheckpoint()
        assertNotNull(newLatestCheckpoint, "New latest checkpoint should not be null")
        assertEquals(laterCheckpointId, newLatestCheckpoint.checkpointId)

        // Verify that getCheckpoints returns both checkpoints
        val allCheckpoints = provider.getCheckpoints()
        assertEquals(2, allCheckpoints.size, "Should have two checkpoints")
        assertTrue(allCheckpoints.any { it.checkpointId == checkpointId })
        assertTrue(allCheckpoints.any { it.checkpointId == laterCheckpointId })
    }
}