package nl.ferron.copilotcontextbridge.patch

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import nl.ferron.copilotcontextbridge.ProjectRoot
import java.nio.file.Files
import java.nio.file.Path

class PatchImportService(
    private val project: Project,
) {
    fun load(path: Path): PatchValidator.Result {
        val patch =
            if (path.fileName.toString().endsWith(".zip", ignoreCase = true)) {
                val bytes = Files.readAllBytes(path)
                val generic = GenericCodeZipParser()
                if (generic.hasStructuredManifest(bytes)) {
                    CopilotPatchParser().parseZip(bytes)
                } else {
                    val root = ProjectRoot.path(project)
                    val repositoryId = ProjectRoot.virtualFile(project).name.replace(Regex("[^A-Za-z0-9._-]"), "-")
                    generic.parse(bytes, root, repositoryId)
                }
            } else {
                CopilotPatchParser().parse(path)
            }
        return ReadAction.nonBlocking<PatchValidator.Result> { PatchValidator(project).validate(patch) }.executeSynchronously()
    }

    fun loadJson(json: String): PatchValidator.Result {
        val patch = CopilotPatchParser().parseJson(json)
        return ReadAction.nonBlocking<PatchValidator.Result> { PatchValidator(project).validate(patch) }.executeSynchronously()
    }
}
