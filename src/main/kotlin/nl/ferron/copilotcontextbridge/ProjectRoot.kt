package nl.ferron.copilotcontextbridge

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

object ProjectRoot {
    fun path(project: Project): Path = Path.of(project.basePath ?: error("The project has no base path.")).toAbsolutePath().normalize()

    fun virtualFile(project: Project): VirtualFile =
        LocalFileSystem.getInstance().findFileByNioFile(path(project))
            ?: error("The project root is not available in the virtual file system.")
}
