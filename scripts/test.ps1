$ErrorActionPreference = 'Stop'
python -u "$PSScriptRoot/testing/progressive.py" @args
exit $LASTEXITCODE
