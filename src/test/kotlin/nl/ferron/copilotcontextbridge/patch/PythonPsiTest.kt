package nl.ferron.copilotcontextbridge.patch

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.python.PythonFileType
import com.jetbrains.python.psi.PyFile
import nl.ferron.copilotcontextbridge.analysis.SymbolIndexer

class PythonPsiTest : BasePlatformTestCase() {
    fun testFindsTopLevelDecoratedAsyncAndMethods() {
        val file =
            myFixture.configureByText(
                PythonFileType.INSTANCE,
                """
                @decorator
                async def fetch() -> str:
                    return "ok"

                class Client:
                    @classmethod
                    def create(cls):
                        return cls()

                    def run(self):
                        def nested():
                            return 1
                        return nested()
                """.trimIndent(),
            ) as PyFile
        val names = SymbolIndexer.index(file).map { it.qualifiedName }
        assertContainsElements(names, "fetch", "Client.create", "Client.run", "Client.run.nested")
        assertSize(1, PythonFunctionLocator.find(file, "Client.run.nested"))
    }

    fun testDedentPreservesFunctionBody() {
        val result = PatchValidator.dedent("    def run():\r\n        return 1\r\n")
        assertEquals("def run():\n    return 1\n", result)
    }
}
