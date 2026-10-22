# Build the Olog service documentation

You can build the Olog service documentation
by using a local copy using Pixi
or a local installation of Python.

## Option 1: Using Pixi (recommended, except on Mac OS)

Install [Pixi](https://pixi.sh) and run:

```bash
# Navigate to the docs directory
cd docs

# Build the documentation
pixi run build

# Or directly build HTML
pixi run html

# Serve documentation locally
pixi run serve

# Clean build artifacts
pixi run clean
```

The Pixi configuration is in `pyproject.toml` under the `[tool.pixi.*]` sections.

## Option 2: Using Sphinx directly

Create a Python virtual environment
where you install Sphinx and its dependencies:

```bash
# Navigate to the docs directory
cd docs

# Create a virtual environment
python -m venv .venv
# Enter the environment (for Bash, Zsh)
#
# NOTE: If you're using a shell different than Bash or Zsh,
# such as Windows' `cmd.exe` or PowerShell,
# see the official "How venvs work" documentation:
# https://docs.python.org/3/library/venv.html#how-venvs-work
source .venv/bin/activate

# Install from pyproject.toml (installs all dependencies)
pip install .
```

Then build the web version
(make sure you've entered the Python virtual environment):

```bash
make clean html
```

The above creates a document tree starting with `_build/html/index.html`.

