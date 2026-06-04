# Zephyr environment setup for Windows (nRF Connect SDK toolchain)
# Usage: . .\scripts\setup-env.ps1

$toolchainRoot = "C:\ncs\toolchains\0b393f9e1b"

if (-not (Test-Path $toolchainRoot)) {
    Write-Error "Toolchain not found at $toolchainRoot"
    Write-Host "Install: nrfutil toolchain-manager install --ncs-version v3.3.0"
    return
}

$env:PATH = "$toolchainRoot;$toolchainRoot\mingw64\bin;$toolchainRoot\bin;$toolchainRoot\opt\bin;$toolchainRoot\opt\bin\Scripts;$toolchainRoot\opt\nanopb\generator-bin;$toolchainRoot\opt\zephyr-sdk\arm-zephyr-eabi\bin;$toolchainRoot\opt\zephyr-sdk\riscv64-zephyr-elf\bin;" + $env:PATH
$env:PYTHONPATH = "$toolchainRoot\opt\bin;$toolchainRoot\opt\bin\Lib;$toolchainRoot\opt\bin\Lib\site-packages"
$env:ZEPHYR_TOOLCHAIN_VARIANT = "zephyr"
$env:ZEPHYR_SDK_INSTALL_DIR = "$toolchainRoot\opt\zephyr-sdk"
$env:ZEPHYR_BASE = "$PSScriptRoot\..\zephyr"

Write-Host "Zephyr environment loaded for Windows."
Write-Host "ZEPHYR_BASE = $env:ZEPHYR_BASE"
