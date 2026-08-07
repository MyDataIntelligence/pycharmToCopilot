package nl.ferron.copilotcontextbridge.actions

import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiFileSystemItem
import nl.ferron.copilotcontextbridge.state.ContextSelectionService
import javax.swing.SwingUtilities

internal object ActionSupport {
    fun files(event: AnActionEvent): List<VirtualFile> {
        val fromContext =
            mergeFiles(
                event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.asList().orEmpty(),
                listOfNotNull(event.getData(CommonDataKeys.VIRTUAL_FILE)),
                event
                    .getData(LangDataKeys.PSI_ELEMENT_ARRAY)
                    ?.filterIsInstance<PsiFileSystemItem>()
                    ?.map(PsiFileSystemItem::getVirtualFile)
                    .orEmpty(),
                listOfNotNull((event.getData(CommonDataKeys.PSI_ELEMENT) as? PsiFileSystemItem)?.virtualFile),
                event
                    .getData(PlatformCoreDataKeys.SELECTED_ITEMS)
                    ?.mapNotNull(::virtualFileOf)
                    .orEmpty(),
            )
        if (fromContext.isNotEmpty() || !SwingUtilities.isEventDispatchThread()) return fromContext
        val paneSelection =
            event.project
                ?.let(ProjectView::getInstance)
                ?.currentProjectViewPane
                ?.selectedUserObjects
                ?.mapNotNull(::virtualFileOf)
                .orEmpty()
        return mergeFiles(paneSelection)
    }

    internal fun virtualFileOf(value: Any?): VirtualFile? =
        when (value) {
            is VirtualFile -> value
            is PsiFileSystemItem -> value.virtualFile
            is AbstractTreeNode<*> -> virtualFileOf(value.value)
            else -> null
        }

    internal fun mergeFiles(vararg groups: Collection<VirtualFile>): List<VirtualFile> =
        groups
            .asSequence()
            .flatten()
            .distinctBy(VirtualFile::getPath)
            .toList()

    fun open(event: AnActionEvent) {
        event.project?.let { project -> ToolWindowManager.getInstance(project).getToolWindow("Copilot Context Bridge")?.show() }
    }

    fun update(
        event: AnActionEvent,
        requireSelection: Boolean = true,
    ) {
        val files = files(event)
        event.presentation.isVisible = event.project != null
        event.presentation.isEnabled = event.project != null && (!requireSelection || files.isNotEmpty())
    }

    fun selection(event: AnActionEvent): ContextSelectionService? = event.project?.getService(ContextSelectionService::class.java)
}
