package nl.ferron.copilotcontextbridge.staging

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

class FileListTransferable(
    files: Collection<File>,
    private val pathText: String,
) : Transferable {
    private val files = files.toList()
    private val flavors = arrayOf(DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor)

    override fun getTransferDataFlavors(): Array<DataFlavor> = flavors.clone()

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in flavors

    override fun getTransferData(flavor: DataFlavor): Any =
        when (flavor) {
            // Return a fresh snapshot so a clipboard consumer cannot mutate the
            // transferable's internal file list between repeated reads.
            DataFlavor.javaFileListFlavor -> files.toList()
            DataFlavor.stringFlavor -> pathText
            else -> throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
        }
}
