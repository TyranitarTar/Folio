param([string[]]$Tasks = @('assembleDebug'))
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$taskJdk = (Get-ChildItem -LiteralPath 'toolchain\jdk' -Directory | Select-Object -First 1).FullName
$env:JAVA_HOME = $taskJdk
$env:ANDROID_HOME = Join-Path $PSScriptRoot 'toolchain\sdk'
$env:ANDROID_USER_HOME = Join-Path $PSScriptRoot 'toolchain\android-user'
$env:GRADLE_USER_HOME = Join-Path $PSScriptRoot 'toolchain\gradle-user'
$env:Path = "$taskJdk\bin;$env:Path"
$sdkProperty = $env:ANDROID_HOME.Replace('\','/').Replace(':','\:')
"sdk.dir=$sdkProperty" | Set-Content -LiteralPath 'local.properties' -Encoding ascii
& "$PSScriptRoot\toolchain\gradle\gradle-8.11.1\bin\gradle.bat" @Tasks --console=plain --stacktrace
exit $LASTEXITCODE
