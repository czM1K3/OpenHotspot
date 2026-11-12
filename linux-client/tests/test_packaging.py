"""The shipped example config, systemd units and scripts stay consistent with the code."""
import subprocess
from pathlib import Path

import pytest

from openhotspot.config import Config, load_config

PKG = Path(__file__).resolve().parents[1] / "packaging"


def test_example_config_parses_and_equals_the_defaults():
    assert load_config(PKG / "config.example.toml") == Config()


@pytest.mark.parametrize("name", ["install.sh", "uninstall.sh"])
def test_scripts_are_valid_bash_and_executable(name):
    path = PKG / name
    assert path.stat().st_mode & 0o111
    assert subprocess.run(["bash", "-n", str(path)], capture_output=True).returncode == 0


@pytest.mark.parametrize("name, tray", [("openhotspot.service", False), ("openhotspot-tray.service", True)])
def test_units_run_the_cli(name, tray):
    text = (PKG / "systemd" / name).read_text()
    exec_line = next(line for line in text.splitlines() if line.startswith("ExecStart="))
    assert exec_line.startswith("ExecStart=%h/.local/bin/openhotspot daemon") and exec_line.endswith("--tray") == tray
    assert "WantedBy=" + ("graphical-session.target" if tray else "default.target") in text
    assert "NoNewPrivileges=yes" in text and "Restart=on-failure" in text
