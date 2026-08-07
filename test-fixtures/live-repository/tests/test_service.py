"""Tests related to the primary service."""

from src.services.service import run_service


def test_run_service_normalizes_negative_values() -> None:
    assert run_service(-2).value == 0

