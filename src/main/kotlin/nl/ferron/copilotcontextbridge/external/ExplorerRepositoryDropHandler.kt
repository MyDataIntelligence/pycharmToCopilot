package nl.ferron.copilotcontextbridge.external

import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.datatransfer.DataFlavor
import java.nio.file.Path
import javax.swing.SwingUtilities
import javax.swing.TransferHandler

/** Reusable Windows Explorer / OS file-manager drop handler. Attach it to the outbound batch drop surface. */
class ExplorerRepositoryDropHandler(
    private val resolver: (List<Path>) -> ExternalRepositoryDropResolver.Result,
    private val resultConsumer: (ExternalRepositoryDropResolver.Result) -> Unit,
    private val errorConsumer: (String) -> Unit = {},
) : TransferHandler() {
    override fun canImport(support: TransferSupport): Boolean =
        support.isDrop && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

    override fun importData(support: TransferSupport): Boolean {
        if (!canImport(support)) return false
        val paths =
            runCatching {
                @Suppress("UNCHECKED_CAST")
                (support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<java.io.File>).map { it.toPath() }
            }.getOrElse {
                errorConsumer("Could not read dropped files: ${it.message ?: "unsupported file list"}")
                return false
            }
        if (paths.isEmpty()) return false
        AppExecutorUtil.getAppExecutorService().execute {
            runCatching { resolver(paths) }
                .onSuccess { result -> SwingUtilities.invokeLater { resultConsumer(result) } }
                .onFailure { error ->
                    SwingUtilities.invokeLater { errorConsumer(error.message ?: "Could not process dropped repository paths.") }
                }
        }
        return true
    }
}
