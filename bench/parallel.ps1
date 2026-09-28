#requires -version 7
<#
.SYNOPSIS
    Task A5: runs the in-game bench as up to 4 Minecraft clients at once, then merges their reports into one.

.DESCRIPTION
    Shard 1 runs in the current worktree; shards 2..n each run in their own detached worktree,
    .worktrees/bench-shard-<k> (a sibling of the current worktree, created or reset to HEAD). All n clients
    run at the same time. Once every one of them has finished, this script hands their reports to the
    `benchMerge` Gradle task (ReportMerge, pure core), which writes ONE report,
    build/bench/report-<version>.json/.md, indistinguishable in shape and meaning from a single client's,
    then runs `benchVerify` on it unchanged.

    Refuses to start on a dirty tree: every shard plays the committed HEAD, so an uncommitted change would
    silently not be what any of them measured. If a shard fails, the others' reports are kept, the failed
    shard is named, and this script fails without attempting a merge (no partial merge ever passes the gate).

.PARAMETER Shards
    How many clients to run at once, 1..4 (the owner's machine: 4 comfortably). 1 just runs the plain,
    unsharded bench (./gradlew runClientGameTest) — no worktree juggling needed.

.PARAMETER Full / Fresh / Only / Ping / VerifySettle
    The usual bench flags (-Pbench.full, -Pbench.fresh, -Pbench.only=<names>, -Pbench.ping=<ms>,
    -Pbench.verifySettle), forwarded to every shard identically — ReportMerge refuses to merge shards that
    do not agree on them.

.PARAMETER UpdateBaseline
    -Pbench.updateBaseline, applied once, after the merge, from the complete merged report — never passed to
    the individual shard runs (a shard only sees its own slice of the scenarios, and shards 2..n's own
    bench/baseline.json lives in a disposable worktree, so updating it there would be lost).

.EXAMPLE
    bench/parallel.ps1 -Shards 4 -Full -Fresh
    A release run: the full profile, Meteor re-measured, sharded across 4 clients.
#>
param(
    [ValidateRange(1, 4)]
    [int]$Shards = 4,
    [switch]$Full,
    [switch]$Fresh,
    [string]$Only = "",
    [Nullable[int]]$Ping = $null,
    [switch]$VerifySettle,
    [switch]$UpdateBaseline
)

$ErrorActionPreference = "Stop"

# This script lives at <worktree>/bench/parallel.ps1; the worktree itself is its parent.
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$WorktreesRoot = (Resolve-Path (Join-Path $RepoRoot "..")).Path
Set-Location $RepoRoot

function Fail([string]$Message) {
    Write-Host "bench: $Message" -ForegroundColor Red
    exit 1
}

# Every shard plays the committed HEAD: an uncommitted change would silently not be what any of them measured.
$Status = git status --porcelain
if ($LASTEXITCODE -ne 0) { Fail "not a git repository (or git is not on PATH)" }
if ($Status) { Fail "the tree is dirty; commit or set the change aside before a sharded run (shards run the committed HEAD)" }
$Commit = (git rev-parse HEAD).Trim()
Write-Host "bench: sharding $Shards way(s) at commit $Commit"

# The usual bench flags, forwarded identically to every shard (ReportMerge refuses a mismatch).
$ShardFlags = [System.Collections.Generic.List[string]]::new()
if ($Full) { $ShardFlags.Add("-Pbench.full") }
if ($Fresh) { $ShardFlags.Add("-Pbench.fresh") }
if ($Only) { $ShardFlags.Add("-Pbench.only=$Only") }
if ($null -ne $Ping) { $ShardFlags.Add("-Pbench.ping=$Ping") }
if ($VerifySettle) { $ShardFlags.Add("-Pbench.verifySettle") }
# -Pbench.updateBaseline is deliberately NOT in here: see the .PARAMETER UpdateBaseline note above.

if ($Shards -eq 1) {
    Write-Host "bench: -Shards 1 — running the plain (unsharded) bench, no worktrees needed"
    $Flags = @($ShardFlags)
    if ($UpdateBaseline) { $Flags += "-Pbench.updateBaseline" }
    & .\gradlew.bat runClientGameTest @Flags
    exit $LASTEXITCODE
}

# Shards 2..n: detached worktrees .worktrees/bench-shard-<k>, created or reset to the same commit. Never the
# main worktree, never any other worktree but these.
for ($k = 2; $k -le $Shards; $k++) {
    $ShardPath = Join-Path $WorktreesRoot "bench-shard-$k"
    if (-not (Test-Path $ShardPath)) {
        Write-Host "bench: creating shard worktree bench-shard-$k"
        git worktree add --detach $ShardPath $Commit
        if ($LASTEXITCODE -ne 0) { Fail "could not create the shard $k worktree" }
    } else {
        Write-Host "bench: resetting shard worktree bench-shard-$k to $Commit"
        git -C $ShardPath checkout --detach $Commit --quiet 2>$null
        git -C $ShardPath reset --hard $Commit --quiet
        if ($LASTEXITCODE -ne 0) { Fail "could not reset the shard $k worktree to $Commit" }
        git -C $ShardPath clean -fd --quiet
    }
}

# The Meteor cache in first: a shard whose ca-* results are already cached does not remeasure them.
$CacheSrc = Join-Path $RepoRoot "build\bench\meteor-cache"
if (Test-Path $CacheSrc) {
    for ($k = 2; $k -le $Shards; $k++) {
        $CacheDst = Join-Path $WorktreesRoot "bench-shard-$k\build\bench\meteor-cache"
        New-Item -ItemType Directory -Force -Path $CacheDst | Out-Null
        Copy-Item -Path (Join-Path $CacheSrc "*") -Destination $CacheDst -Recurse -Force -ErrorAction SilentlyContinue
    }
    Write-Host "bench: Meteor cache copied into $($Shards - 1) shard worktree(s)"
}

# Launch all n clients at the same time; each logs to its own file so a hung one can be told apart from one
# still working. -Xmx3G per client: measured on this machine (i9-14900KF, 64 GB) comfortably running four
# gametest clients at once without swapping; window/render/fps options are left at the gametest run's own
# defaults (BenchTest.keepFullFrameRate already disables the AFK cap, the only one that could change a
# measurement) since nothing else here is touched.
$LogDir = Join-Path $RepoRoot "build\bench-shard-logs"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$Procs = @()
for ($k = 1; $k -le $Shards; $k++) {
    $Dir = if ($k -eq 1) { $RepoRoot } else { Join-Path $WorktreesRoot "bench-shard-$k" }
    $KFlags = @($ShardFlags) + "-Pbench.shard=$k/$Shards"
    $Log = Join-Path $LogDir "shard-$k.log"
    $ErrLog = Join-Path $LogDir "shard-$k.err.log"
    Write-Host "bench: launching shard $k/$Shards in $Dir (log: $Log)"
    $Proc = Start-Process -FilePath (Join-Path $Dir "gradlew.bat") `
        -ArgumentList (@("runClientGameTest") + $KFlags) `
        -WorkingDirectory $Dir -RedirectStandardOutput $Log -RedirectStandardError $ErrLog -PassThru -WindowStyle Minimized
    $Procs += [PSCustomObject]@{ K = $k; Dir = $Dir; Process = $Proc; Log = $Log }
}

Write-Host "bench: waiting for $Shards shard(s) to finish..."
$WallStart = Get-Date
foreach ($P in $Procs) { $P.Process.WaitForExit() }
$WallTotal = (Get-Date) - $WallStart

$Failed = @()
foreach ($P in $Procs) {
    $Elapsed = $P.Process.ExitTime - $P.Process.StartTime
    $Ok = $P.Process.ExitCode -eq 0
    $Line = "bench: shard {0}/{1}: {2} in {3:N1} min" -f $P.K, $Shards, $(if ($Ok) { "OK" } else { "FAILED (exit $($P.Process.ExitCode))" }), $Elapsed.TotalMinutes
    if ($Ok) { Write-Host $Line } else { Write-Host $Line -ForegroundColor Red; $Failed += $P.K }
}
Write-Host ("bench: total wall time {0:N1} min ({1} shard(s) in parallel)" -f $WallTotal.TotalMinutes, $Shards)

if ($Failed.Count -gt 0) {
    Fail "shard(s) $($Failed -join ', ') failed; the others' reports are kept but no merge is attempted (see build/bench-shard-logs)"
}

# The new Meteor cache entries back: a fresh shard's own ca-* results are worth keeping for next time.
for ($k = 2; $k -le $Shards; $k++) {
    $CacheBack = Join-Path $WorktreesRoot "bench-shard-$k\build\bench\meteor-cache"
    if (Test-Path $CacheBack) {
        New-Item -ItemType Directory -Force -Path $CacheSrc | Out-Null
        Copy-Item -Path (Join-Path $CacheBack "*") -Destination $CacheSrc -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# The merge (ReportMerge, pure core) into this worktree's own build/bench/report-<version>.json/.md, then
# benchVerify on it, unchanged.
$MergeFlags = @("-Pbench.shards=$Shards")
if ($UpdateBaseline) { $MergeFlags += "-Pbench.updateBaseline" }
& .\gradlew.bat benchMerge @MergeFlags
exit $LASTEXITCODE
