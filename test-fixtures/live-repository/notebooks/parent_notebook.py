"""Fabric parent notebook fixture."""

CHILD_RUN = 'notebookutils.notebook.run("child_notebook", 60)'


def get_child_run() -> str:
    """Return the configured child notebook execution command."""
    return CHILD_RUN.strip()


def build_run_command(timeout: int) -> str:
    """Build a notebook run command."""
    return f'notebookutils.notebook.run("child_notebook", {timeout})'


def is_child_configured() -> bool:
    return bool(CHILD_RUN)


def get_default_timeout() -> int:
    return 60


def get_notebook_name() -> str:
    return 'child_notebook'


def command_length() -> int:
    return len(CHILD_RUN)


def command_contains_timeout(value: str) -> bool:
    return value in CHILD_RUN


def get_upper_command() -> str:
    return CHILD_RUN.upper()


def get_lower_command() -> str:
    return CHILD_RUN.lower()


def starts_with_notebookutils() -> bool:
    return CHILD_RUN.startswith('notebookutils')