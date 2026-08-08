package nl.ferron.copilotcontextbridge.patch

import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import nl.ferron.copilotcontextbridge.settings.ProjectSettings

class PythonFunctionReplacementService(
    private val project: Project,
) {
    private val logger = Logger.getInstance(PythonFunctionReplacementService::class.java)

    data class ApplyResult(
        val applied: List<String>,
        val skipped: List<String>,
        val failures: List<String>,
    )

    fun apply(
        validation: PatchValidator.Result,
        selectedNames: Set<String>,
        forcedNames: Set<String>,
    ): ApplyResult {
        if (!validation.validation.valid) {
            return ApplyResult(
                applied = emptyList(),
                skipped = emptyList(),
                failures = listOf("Patch validation failed; no project files were modified."),
            )
        }
        val applied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val affectedFiles = linkedSetOf<PsiFile>()
        val lastInsertionByAnchor = mutableMapOf<PsiElement, PsiElement>()
        val settings = project.getService(ProjectSettings::class.java).state
        val eligible =
            validation.targets.filter { target ->
                val name = target.validated.request.qualifiedName
                val key = "${target.validated.request.path}::$name"
                val selected = key in selectedNames
                val safe =
                    target.validated.status in setOf(ReplacementStatus.MATCH, ReplacementStatus.NEW) ||
                        (target.validated.status == ReplacementStatus.CHANGED && key in forcedNames)
                val structurallyReady =
                    when (target.validated.request.operation) {
                        "add_file" -> target.fileOperationReady && target.file != null && target.fileParent != null
                        "delete_file" -> target.fileOperationReady && target.file != null
                        else -> target.parsed != null && (target.function != null || target.insertionParent != null)
                    }
                if (!selected || !safe || !structurallyReady) {
                    skipped += "${target.validated.request.path}:$name — ${target.validated.status}"
                    false
                } else {
                    true
                }
            }

        fun replace(target: PatchValidator.Target) {
            try {
                val operation = target.validated.request.operation
                if (operation == "delete_file") {
                    target.file!!.delete()
                    applied += "${target.validated.request.path}:${target.validated.request.qualifiedName}"
                    return
                }
                val replaced =
                    if (operation == "add_file") {
                        createFile(target)
                    } else if (target.validated.status == ReplacementStatus.NEW) {
                        val container =
                            when (val parent = target.insertionParent) {
                                is PyFile -> parent
                                is PyClass -> parent.statementList
                                is PyFunction -> parent.statementList
                                else -> error("Unsupported insertion parent.")
                            }
                        if (target.insertionAnchor != null) {
                            val effectiveAnchor = lastInsertionByAnchor[target.insertionAnchor] ?: target.insertionAnchor
                            container.addAfter(target.parsed!!, effectiveAnchor).also {
                                lastInsertionByAnchor[target.insertionAnchor] = it
                            }
                        } else {
                            container.add(target.parsed!!)
                        }
                    } else {
                        target.function!!.replace(target.parsed!!)
                    }
                // PyCharm 2026.2 currently crashes its Python line-wrapping post-processor when
                // reformat(PyFunction) is called directly after a structural PSI replacement.
                // Keep the safe, parsed PSI replacement instead of risking a rolled-back change.
                // Older supported IDEs can still use the native formatter.
                if (settings.reformatReplacements && ApplicationInfo.getInstance().build.baselineVersion < 262) {
                    CodeStyleManager.getInstance(project).reformat(replaced)
                } else if (settings.reformatReplacements) {
                    logger.debug("Skipped unsafe PyFunction reformat on PyCharm 2026.2 or newer")
                }
                replaced.containingFile?.let(affectedFiles::add)
                applied += "${target.validated.request.path}:${target.validated.request.qualifiedName}"
            } catch (error: Exception) {
                failures += "${target.validated.request.path}:${target.validated.request.qualifiedName} — ${error.message}"
            }
        }
        if (settings.oneUndoOperation) {
            WriteCommandAction.writeCommandAction(project).withName("Apply Copilot function replacements").run<RuntimeException> {
                eligible.forEach(::replace)
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                if (settings.optimizeImports && failures.isEmpty()) optimizeImports(affectedFiles)
            }
        } else {
            eligible.forEach { target ->
                WriteCommandAction.writeCommandAction(project).withName("Apply Copilot function replacement").run<RuntimeException> {
                    replace(target)
                    PsiDocumentManager.getInstance(project).commitAllDocuments()
                    if (settings.optimizeImports && failures.isEmpty()) optimizeImports(affectedFiles)
                }
            }
        }
        return ApplyResult(applied, skipped, failures)
    }

    private fun createFile(target: PatchValidator.Target): PsiFile {
        val directory: PsiDirectory = target.fileParent ?: error("Target parent directory no longer exists.")
        val fileName =
            target.validated.request.path
                .replace('\\', '/')
                .substringAfterLast('/')
        check(directory.findFile(fileName) == null) { "Target file already exists." }
        val created = directory.createFile(fileName)
        val document =
            PsiDocumentManager.getInstance(project).getDocument(created)
                ?: error("Could not create an editable document for the new file.")
        document.setText(target.validated.newText)
        PsiDocumentManager.getInstance(project).commitDocument(document)
        return created
    }

    private fun optimizeImports(files: Collection<PsiFile>) {
        files.forEach { file -> OptimizeImportsProcessor(project, file).runWithoutProgress() }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }
}
