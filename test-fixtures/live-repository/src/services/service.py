"""Primary fixture service."""

from dataclasses import dataclass

from src.common.helper import normalize_value


@dataclass(frozen=True)
class ServiceResult:
    """Structured fixture result."""

    value: int


def run_service(value: int) -> ServiceResult:
    """Normalize and return a service result."""
    return ServiceResult(normalize_value(value))


async def run_service_async(value: int) -> ServiceResult:
    """Return the asynchronous fixture result."""
    return run_service(value)


class ServiceClient:
    """Client used for method replacement tests."""

    @staticmethod
    def validate(value: int) -> bool:
        """Return whether a value is accepted."""
        return value >= 0

    @classmethod
    def create(cls, value: int) -> "ServiceClient":
        """Create a client after validating input."""
        if not cls.validate(value):
            raise ValueError("value must be non-negative")
        return cls()

    def process(self, value: int) -> ServiceResult:
        """Process a value."""

        def add_one(item: int) -> int:
            """Add one inside the method."""
            return item + 1

        return run_service(add_one(value))

