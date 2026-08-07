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
                    isAsync(function) -> "async function"
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
                is PyFunction -> functionName(current)?.let(names::add)
                is PyClass -> className(current)?.let(names::add)
            }
            current = current.parent
        }
        return names.asReversed().joinToString(".")
    }

    fun functionName(function: PyFunction): String? = FUNCTION_DECLARATION.find(function.text)?.groupValues?.get(1)

    fun isAsync(function: PyFunction): Boolean = Regex("(?m)^\\s*async\\s+def\\b").containsMatchIn(function.text)

    private fun className(pyClass: PyClass): String? = CLASS_DECLARATION.find(pyClass.text)?.groupValues?.get(1)

    private val FUNCTION_DECLARATION = Regex("(?m)^\\s*(?:async\\s+)?def\\s+([A-Za-z_]\\w*)\\s*\\(")
    private val CLASS_DECLARATION = Regex("(?m)^\\s*class\\s+([A-Za-z_]\\w*)\\b")
}
