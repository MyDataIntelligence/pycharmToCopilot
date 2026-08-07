package nl.ferron.copilotcontextbridge.patch

import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import nl.ferron.copilotcontextbridge.analysis.SymbolIndexer

object PythonFunctionLocator {
    fun find(
        file: PyFile,
        qualifiedName: String,
    ): List<PyFunction> =
        PsiTreeUtil.findChildrenOfType(file, PyFunction::class.java).filter { SymbolIndexer.qualifiedName(it) == qualifiedName }
}
