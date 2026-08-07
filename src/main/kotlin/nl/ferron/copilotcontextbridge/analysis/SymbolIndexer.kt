package nl.ferron.copilotcontextbridge.analysis

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import nl.ferron.copilotcontextbridge.model.PythonSymbol

object SymbolIndexer {
    fun index(file: PyFile): List<PythonSymbol> {
        val result = mutableListOf<PythonSymbol>()
        PsiTreeUtil.findChildrenOfType(file, PyClass::class.java).forEach { klass ->
            result += PythonSymbol(qualifiedName(klass), "class")
        }
        PsiTreeUtil.findChildrenOfType(file, PyFunction::class.java).forEach { function ->
            val kind =
                when {
                    function.isAsync -> "async function"
                    function.containingClass != null -> "method"
                    PsiTreeUtil.getParentOfType(function.parent, PyFunction::class.java) != null -> "nested function"
                    else -> "function"
                }
            result += PythonSymbol(qualifiedName(function), kind, FunctionHasher.hash(function.text))
        }
        return result.sortedBy { it.qualifiedName }
    }

    fun qualifiedName(element: PsiElement): String {
        val names = mutableListOf<String>()
        var current: PsiElement? = element
        while (current != null && current !is PyFile) {
            when (current) {
                is PyFunction -> current.name?.let(names::add)
                is PyClass -> current.name?.let(names::add)
            }
            current = current.parent
        }
        return names.asReversed().joinToString(".")
    }
}
