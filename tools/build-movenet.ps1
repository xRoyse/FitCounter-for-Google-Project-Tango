param(
    [Parameter(Mandatory = $true)][string]$TensorFlowRoot,
    [Parameter(Mandatory = $true)][string]$Ndk,
    [Parameter(Mandatory = $true)][string]$CmakeBin,
    [string]$MlDtypesRoot,
    [string]$PthreadpoolRoot,
    [string]$FxdivRoot,
    [string]$BuildRoot = (Join-Path $env:TEMP 'FitCounter-MoveNet-build'),
    [ValidateSet('armeabi-v7a', 'x86', 'arm64-v8a', 'x86_64')]
    [string[]]$Abis = @('armeabi-v7a', 'x86'),
    [int]$Jobs = 4
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$TensorFlowRoot = (Resolve-Path $TensorFlowRoot).Path
$Ndk = (Resolve-Path $Ndk).Path
$CmakeBin = (Resolve-Path $CmakeBin).Path
$cmake = Join-Path $CmakeBin 'cmake.exe'
$ninja = Join-Path $CmakeBin 'ninja.exe'
$env:Path = $CmakeBin + ';' + $env:Path

foreach ($abi in $Abis) {
    $api = if ($abi -eq 'armeabi-v7a' -or $abi -eq 'x86') { 19 } else { 21 }
    $build = Join-Path $BuildRoot $abi
    $options = @(
        "-DCMAKE_MAKE_PROGRAM=$ninja", "-DCMAKE_TOOLCHAIN_FILE=$Ndk/build/cmake/android.toolchain.cmake",
        "-DANDROID_ABI=$abi", "-DANDROID_PLATFORM=android-$api", "-DANDROID_STL=c++_static",
        "-DTENSORFLOW_ROOT=$TensorFlowRoot", '-DCMAKE_BUILD_TYPE=Release'
    )
    if ($MlDtypesRoot) {
        $mlDtypes = (Resolve-Path $MlDtypesRoot).Path
        $options += "-DFETCHCONTENT_SOURCE_DIR_ML_DTYPES=$mlDtypes"
    }
    if ($PthreadpoolRoot) {
        $pthreadpool = (Resolve-Path $PthreadpoolRoot).Path
        $options += "-DPTHREADPOOL_SOURCE_DIR=$pthreadpool"
    }
    if ($FxdivRoot) {
        $fxdiv = (Resolve-Path $FxdivRoot).Path
        $options += "-DFXDIV_SOURCE_DIR=$fxdiv"
    }
    & $cmake -S "$repo/app/src/movenet/cpp" -B $build -G Ninja @options
    if ($LASTEXITCODE -ne 0) { throw "CMake configuration failed for $abi" }
    & $cmake --build $build --target fitcounter_pose --parallel $Jobs
    if ($LASTEXITCODE -ne 0) { throw "TensorFlow Lite build failed for $abi" }
    $library = Join-Path $build 'libfitcounter_pose.so'
    $strip = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe'
    $destination = Join-Path $repo "app/src/movenet/jniLibs/$abi"
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    & $strip --strip-unneeded -o (Join-Path $destination 'libfitcounter_pose.so') $library
    if ($LASTEXITCODE -ne 0) { throw "Stripping symbols failed for $abi" }
}
