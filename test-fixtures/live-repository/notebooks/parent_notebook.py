"""Fabric parent notebook fixture."""

CHILD_RUN = 'notebookutils.notebook.run("child_notebook", 60)'


def get_child_run() -> str:
    """Return the configured child notebook execution command."""
    return CHILD_RUN