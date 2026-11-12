package honnyaku

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.EditorDiffViewer
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.util.Side
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener

/** The displayed editor can use a synthetic document, as in a unified diff. */
internal data class TranslationSource(
    val editor: Editor,
    val document: Document,
    val file: VirtualFile,
    val isDiff: Boolean = false,
    val sourceLine: (Int) -> Int = { it },
) {
    fun sourceOffset(editorOffset: Int): Int {
        val offset = editorOffset.coerceIn(0, editor.document.textLength)
        val line = editor.document.getLineNumber(offset)
        val column = offset - editor.document.getLineStartOffset(line)
        val target = sourceLine(line).coerceIn(0, document.lineCount - 1)
        return (document.getLineStartOffset(target) + column).coerceAtMost(document.getLineEndOffset(target))
    }
}

@Service(Service.Level.PROJECT)
internal class TranslationSources(private val project: Project) {
    private val diffs = mutableListOf<TranslationSource>()
    private val listeners = mutableListOf<() -> Unit>()

    fun subscribe(parent: Disposable, listener: () -> Unit) {
        listeners.add(listener)
        Disposer.register(parent, Disposable { listeners.remove(listener) })
    }

    val current: TranslationSource?
        get() = diffs.lastOrNull { !it.editor.isDisposed && it.editor.component.isShowing }
            ?: FileEditorManager.getInstance(project).selectedTextEditor?.let { editor ->
                realTranslationSource(project, editor)
            }

    fun register(parent: Disposable, source: TranslationSource) {
        diffs.add(source)
        val listener = HierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) changed()
        }
        source.editor.component.addHierarchyListener(listener)
        Disposer.register(parent, Disposable {
            source.editor.component.removeHierarchyListener(listener)
            diffs.remove(source)
            changed()
        })
        changed()
    }

    private fun changed() {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                current?.let {
                    project.service<HonnyakuSettings>().viewModel.selectedUri =
                        VfsUtilCore.virtualToIoFile(it.file).toURI().toASCIIString()
                }
                listeners.toList().forEach { it() }
                project.service<TranslationSessionController>().queueVisibleTranslation()
            }
        }
    }
}

internal fun realTranslationSource(project: Project, editor: Editor): TranslationSource? {
    if (editor.isDisposed) return null
    val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
    // A revision's highlight file may be the real file; only accept its actual Document.
    if (!file.isInLocalFileSystem || !isTranslationFile(file)) return null
    return TranslationSource(editor, editor.document, file)
}

class TranslationDiffExtension : DiffExtension() {
    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val project = context.project ?: return
        val source = diffTranslationSource(project, viewer) ?: return
        project.service<TranslationSources>().register(viewer, source)
    }
}

internal fun diffTranslationSource(project: Project, viewer: FrameDiffTool.DiffViewer): TranslationSource? {
    return if (viewer is UnifiedDiffViewer) {
        val document = viewer.getDocument(Side.RIGHT)
        val file = FileDocumentManager.getInstance().getFile(document) ?: return null
        if (!file.isInLocalFileSystem || !isTranslationFile(file)) return null
        TranslationSource(
            editor = viewer.editor,
            document = document,
            file = file,
            isDiff = true,
            sourceLine = { viewer.transferLineFromOneside(Side.RIGHT, it) },
        )
    } else {
        (viewer as? EditorDiffViewer)?.editors?.asReversed()?.firstNotNullOfOrNull {
            realTranslationSource(project, it)
        }?.copy(isDiff = true)
    }
}
