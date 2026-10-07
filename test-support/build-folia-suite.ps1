param([string]$MavenCommand='mvn')
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$cp=Join-Path $project 'target/folia-suite-classpath.txt'
& $MavenCommand -B -ntp -f (Join-Path $project 'pom.xml') dependency:build-classpath "-Dmdep.outputFile=$cp"
if($LASTEXITCODE -ne 0){throw 'Dependency resolution failed'}
$classes=Join-Path $project 'target/folia-suite-classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$javaBin=Join-Path $env:JAVA_HOME 'bin'
& (Join-Path $javaBin 'javac.exe') --release 25 -cp (Get-Content $cp -Raw).Trim() -d $classes (Join-Path $PSScriptRoot 'folia-suite/NordSuiteTest.java')
if($LASTEXITCODE -ne 0){throw 'Probe compilation failed'}
& (Join-Path $javaBin 'jar.exe') --create --file (Join-Path $project 'target/NordSuiteTest.jar') -C $classes . -C (Join-Path $PSScriptRoot 'folia-suite') plugin.yml
if($LASTEXITCODE -ne 0){throw 'Probe packaging failed'}
