param(
    [switch]$Test,
    [switch]$ConnectedTests,
    [switch]$Install,
    [switch]$Release,
    [string]$AndroidSdk,
    [string]$DebugKeystore
)

$ErrorActionPreference = 'Stop'
$workTrackRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $workTrackRoot
try {
    $jdkCandidates = @($env:JAVA_HOME)
    foreach ($jdkRoot in @(
        (Join-Path $env:ProgramFiles 'Android\openjdk'),
        (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
        (Join-Path $env:ProgramFiles 'Microsoft'),
        (Join-Path $env:ProgramFiles 'Java')
    )) {
        if (Test-Path -LiteralPath $jdkRoot) {
            $jdkCandidates += @(Get-ChildItem -LiteralPath $jdkRoot -Directory -Filter '*21*' | Sort-Object Name -Descending | ForEach-Object { $_.FullName })
        }
    }
    $jdkCandidates += Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    $workTrackJdk = $jdkCandidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe')) } | Select-Object -First 1
    if (-not $workTrackJdk) { throw 'Configurez JAVA_HOME vers un JDK 21.' }
    $env:JAVA_HOME = $workTrackJdk
    $env:DEBUG = $null

    $propertiesPath = Join-Path $workTrackRoot 'local.properties'
    if ($AndroidSdk -or -not (Test-Path -LiteralPath $propertiesPath)) {
        if (-not $AndroidSdk) {
            $sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'), 'C:\Program Files (x86)\Android\android-sdk')
            $AndroidSdk = $sdkCandidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
        }
        if (-not $AndroidSdk) { throw 'Indiquez le SDK Android avec -AndroidSdk ou ANDROID_HOME.' }
        $AndroidSdk = (Resolve-Path -LiteralPath $AndroidSdk).Path
        $escapedSdk = $AndroidSdk.Replace('\', '/').Replace(':', '\:')
        [IO.File]::WriteAllText($propertiesPath, "sdk.dir=$escapedSdk`n", [Text.UTF8Encoding]::new($false))
    }
    if (-not $DebugKeystore) {
        $visualStudioKey = Join-Path $env:LOCALAPPDATA 'Xamarin\Mono for Android\debug.keystore'
        if (Test-Path -LiteralPath $visualStudioKey) { $DebugKeystore = $visualStudioKey }
    }
    $buildVariant = if ($Release) { 'release' } else { 'debug' }
    $gradleTasks = @(if ($Release) { ':app:assembleRelease' } else { ':app:assembleDebug' })
    if ($Test) { $gradleTasks += @(':app:testDebugUnitTest', ':app:lintDebug') }
    if ($ConnectedTests) { $gradleTasks += ':app:connectedDebugAndroidTest' }
    $gradleArguments = $gradleTasks + @('--console=plain')
    if ($Release) { $gradleArguments += '-Pworktrack.releaseWithExistingKey=true' }
    if ($DebugKeystore) {
        $keyPath = (Resolve-Path -LiteralPath $DebugKeystore).Path.Replace('\', '/')
        $gradleArguments += "-Pworktrack.debugKeystore=$keyPath"
    }
    & (Join-Path $workTrackRoot 'gradlew.bat') @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw "Le build Gradle a échoué (code $LASTEXITCODE)." }

    $artifactFolder = Join-Path $workTrackRoot 'artifacts'
    New-Item -ItemType Directory -Path $artifactFolder -Force | Out-Null
    $outputFolder = Join-Path $workTrackRoot "app\build\outputs\apk\$buildVariant"
    $metadata = Get-Content -LiteralPath (Join-Path $outputFolder 'output-metadata.json') -Encoding UTF8 -Raw | ConvertFrom-Json
    $version = $metadata.elements[0].versionName
    $apkName = if ($Release) { "WorkTrack-$version.apk" } else { "WorkTrack-$version-debug.apk" }
    $apkPath = Join-Path $artifactFolder $apkName
    Copy-Item -LiteralPath (Join-Path $outputFolder "app-$buildVariant.apk") -Destination $apkPath -Force
    $checksum = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText("$apkPath.sha256", "$checksum  $apkName`n", [Text.UTF8Encoding]::new($false))
    Write-Host "APK prêt : $apkPath"

    if ($Install) {
        $adbCandidates = @(
            (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
            'C:\Program Files (x86)\Android\android-sdk\platform-tools\adb.exe'
        )
        if (-not $AndroidSdk) {
            $sdkProperty = Get-Content -LiteralPath $propertiesPath -Encoding UTF8 | Where-Object { $_.StartsWith('sdk.dir=') } | Select-Object -First 1
            if ($sdkProperty) { $AndroidSdk = $sdkProperty.Substring(8).Replace('\:', ':').Replace('\\', '\') }
        }
        if ($AndroidSdk) { $adbCandidates = @(Join-Path $AndroidSdk 'platform-tools\adb.exe') + $adbCandidates }
        if ($env:ANDROID_HOME) { $adbCandidates += Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' }
        $workTrackAdb = $adbCandidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
        if (-not $workTrackAdb) { throw 'ADB introuvable : installez Android SDK Platform-Tools.' }
        & $workTrackAdb install -r $apkPath
        if ($LASTEXITCODE -ne 0) { throw "L'installation ADB a échoué (code $LASTEXITCODE)." }
    }
} finally { Pop-Location }
