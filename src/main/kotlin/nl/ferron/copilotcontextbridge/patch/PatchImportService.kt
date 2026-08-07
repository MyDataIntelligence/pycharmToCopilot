package nl.ferron.copilotcontextbridge.patch

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import java.nio.file.Path

class PatchImportService(
    private val project: Project,
) {
    fun load(path: Path): PatchValidator.Result {
        val patch = CopilotPatchParser().parse(path)
        return ReadAction.nonBlocking<PatchValidator.Result> { PatchValidator(project).validate(patch) }.executeSynchronously()
    }

    fun loadJson(json: String): PatchValidator.Result {
        val patch = CopilotPatchParser().parseJson(json)
        return ReadAction.nonBlocking<PatchValidator.Result> { PatchValidator(project).validate(patch) }.executeSynchronously()
    }
}
