"""Direct dependent of the primary service."""

from src.services.service import run_service


def call_service(value: int) -> int:
    """Call the primary service."""
    return run_service(value).value

