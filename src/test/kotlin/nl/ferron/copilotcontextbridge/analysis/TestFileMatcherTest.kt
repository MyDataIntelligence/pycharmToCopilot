package nl.ferron.copilotcontextbridge.analysis

import junit.framework.TestCase

class TestFileMatcherTest : TestCase() {
    fun testExactPythonTestConventionsMatch() {
        assertEquals(100, TestFileMatcher.score("tests/test_payment_service.py", "src/payment_service.py"))
        assertEquals(100, TestFileMatcher.score("tests/payment_service_test.py", "src/payment_service.py"))
    }

    fun testFuzzyNameVariationMatchesConservatively() {
        assertTrue(TestFileMatcher.matches("tests/test_payment_processor.py", "src/payment_processing.py"))
        assertTrue(TestFileMatcher.score("tests/test_user_service.py", "src/user_services.py") in 72..99)
    }

    fun testUnrelatedShortOrGenericNamesDoNotMatch() {
        assertFalse(TestFileMatcher.matches("tests/test_api.py", "src/app.py"))
        assertFalse(TestFileMatcher.matches("tests/test_orders.py", "src/payments.py"))
    }
}
