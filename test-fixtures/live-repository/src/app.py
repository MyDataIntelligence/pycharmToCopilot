"""Application entry point for dependency tests."""

from src.services.service import ServiceResult, run_service

CONFIG_PATH = "config/settings.yaml"


def execute(value: int) -> ServiceResult:
    """Execute the fixture service."""
    return run_service(value)

