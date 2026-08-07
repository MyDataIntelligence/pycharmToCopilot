"""Deployment script with configuration references."""

PIPELINE_CONFIG = "config/pipeline.json"
SETTINGS_CONFIG = "config/settings.yaml"


def deployment_files() -> tuple[str, str]:
    """Return fixture deployment paths."""
    return PIPELINE_CONFIG, SETTINGS_CONFIG

