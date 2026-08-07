"""Tests related to the application entry point."""

from src.app import execute


def test_execute_returns_value() -> None:
    assert execute(2).value == 2

