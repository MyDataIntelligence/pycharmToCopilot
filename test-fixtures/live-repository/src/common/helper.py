"""Second-level dependency fixture."""


def normalize_value(value: int) -> int:
    """Normalize a numeric value."""
    return max(value, 0)

