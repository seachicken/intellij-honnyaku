package com.intellij.air.thread.view

import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import honnyaku.AirCodexTranslationSource
import honnyaku.TranslationSource
import honnyaku.airCodexThreadId
import honnyaku.isWholeDocumentTranslation
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AirCodexTranslationSourceTest : BasePlatformTestCase() {
    override fun runInDispatchThread() = false
    private val id = "019a9b25-1234-7000-8000-123456789abc"
    private val otherId = "019a9b25-1234-7000-8000-000000000000"
    private fun onEdt(action: () -> Unit) {
        EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(action) }
    }

    fun testOnlyCodexAirTabsWithAResolvedConversationAreRecognized() {
        onEdt {
            assertEquals(id, airCodexThreadId(AgentThreadViewVirtualFile(id)))
            assertNull(airCodexThreadId(AgentThreadViewVirtualFile(id, "claude")))
            assertNull(airCodexThreadId(AgentThreadViewVirtualFile("pending:123")))
            assertNull(airCodexThreadId(LightVirtualFile("$id.txt")))
        }
    }

    private data class Update(val source: TranslationSource, val text: String)

    private fun awaitText(updates: LinkedBlockingQueue<Update>, expected: String): TranslationSource {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (true) {
            val update = updates.poll((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                ?: error("No source update containing '$expected'")
            if (update.text == expected) return update.source
        }
    }

    fun testSelectedResponsesUpdateWithoutAFileBackedEditorAndPauseWhenHidden() {
        val replies = java.util.concurrent.CopyOnWriteArrayList(listOf("最初の返答"))
        val updates = LinkedBlockingQueue<Update>()
        lateinit var bridge: AirCodexTranslationSource
        onEdt {
            bridge = AirCodexTranslationSource(project, testRootDisposable, {
                bridge.current?.let { updates.add(Update(it, it.document.text)) }
            }, { replies.toList() })
            FileEditorManager.getInstance(project).openFile(AgentThreadViewVirtualFile(id), true)
            bridge.setActive(true)
        }
        val first = awaitText(updates, "最初の返答")
        onEdt {
            assertNull(first.editor)
            assertEquals("honnyaku-air:///codex/$id.txt", first.uri)
            assertTrue(isWholeDocumentTranslation(first.file))
            assertEquals(1, first.snapshotVersion)
        }
        replies.add("Second response")
        val second = awaitText(updates, "最初の返答\n\nSecond response")
        onEdt {
            assertSame(first.document, second.document)
            assertEquals(first.uri, second.uri)
            assertEquals(2, second.snapshotVersion)
            assertEquals("最初の返答\n\n".length, second.preferredOffset)
            bridge.setActive(false)
        }
        updates.clear()
        replies.add("Hidden response")
        assertNull(updates.poll(1_500, TimeUnit.MILLISECONDS))
        onEdt { bridge.setActive(true) }
        awaitText(updates, "最初の返答\n\nSecond response\n\nHidden response")
    }

    fun testSwitchingTabsUsesOnlyTheSelectedConversationAndReturnsToFileTranslation() {
        val replies = mapOf(id to listOf("First thread"), otherId to listOf("Other thread"))
        val updates = LinkedBlockingQueue<Update>()
        lateinit var bridge: AirCodexTranslationSource
        onEdt {
            bridge = AirCodexTranslationSource(project, testRootDisposable, {
                bridge.current?.let { updates.add(Update(it, it.document.text)) }
            }, replies::get)
            FileEditorManager.getInstance(project).openFile(AgentThreadViewVirtualFile(id), true)
            bridge.setActive(true)
        }
        val first = awaitText(updates, "First thread")
        onEdt { FileEditorManager.getInstance(project).openFile(AgentThreadViewVirtualFile(otherId), true) }
        val other = awaitText(updates, "Other thread")
        assertFalse(first.uri == other.uri)
        onEdt {
            assertEquals(other.uri, bridge.current!!.uri)
            val file = myFixture.addFileToProject("source.txt", "File contents").virtualFile
            FileEditorManager.getInstance(project).openFile(file, true)
            assertNull(bridge.current)
        }
    }
}

// The production adapter recognizes Air tabs by their fully qualified JVM class name.
internal class AgentThreadViewVirtualFile(private val thread: String, private val agent: String = "codex") : LightVirtualFile("Air session") {
    fun `getAgentId-KdGbIeA`(): String = agent
    fun getThreadId(): String = thread
}
