package nl.ferron.copilotcontextbridge.ui

import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal class SimpleDocumentListener(
    private val changed: () -> Unit,
) : DocumentListener {
    override fun insertUpdate(event: DocumentEvent) = changed()

    override fun removeUpdate(event: DocumentEvent) = changed()

    override fun changedUpdate(event: DocumentEvent) = changed()
}
