@echo off
rem ============================================================================
rem  Full sync cycle: pull latest from GitHub -> mirror to the corporate GitLab.
rem
rem    GitHub (origin/main)  --fetch + fast-forward-->  local main  --push-->  corp/master
rem
rem  GitHub stays the source of truth; corp is a downstream mirror.
rem  Branch-name mismatch is handled (local `main` -> corp `master`).
rem
rem  One-time setup (on the work machine that can reach corp git):
rem      git remote add corp <corp-url>
rem    or pass the URL once to this script:
rem      push-to-corp.bat https://git.tkbbank.ru/tkbpay/.../sbp_router.git
rem
rem  Then just run:  push-to-corp.bat
rem ============================================================================
setlocal
cd /d "%~dp0"

rem --- Optional first arg: (re)configure the `corp` remote URL. ---
if not "%~1"=="" call :set_corp "%~1"

rem --- Resolve the GitHub remote: pick the one whose URL contains github.com (else `origin`). ---
set "GH=origin"
for /f "delims=" %%r in ('git remote') do git remote get-url %%r 2>nul | findstr /i "github.com" >nul && set "GH=%%r"

git remote get-url corp >nul 2>&1
if errorlevel 1 goto :no_corp

echo === 1/4  Fetching from GitHub (%GH%) ===
git fetch %GH% --prune
if errorlevel 1 goto :err_fetch

echo === 2/4  Switching to local main ===
git checkout main
if errorlevel 1 goto :err_checkout

echo === 3/4  Fast-forwarding main to %GH%/main ===
git merge --ff-only %GH%/main
if errorlevel 1 goto :err_merge

echo === 4/4  Pushing local main to corp/master ===
git push corp main:master
if errorlevel 1 goto :err_push
git push corp --tags

echo.
echo Done: GitHub %GH%/main mirrored to corp/master.
endlocal & exit /b 0

:set_corp
git remote get-url corp >nul 2>&1
if errorlevel 1 (git remote add corp %1) else (git remote set-url corp %1)
goto :eof

:no_corp
echo [ERROR] Remote "corp" is not configured.
echo   Run once:  git remote add corp ^<corp-url^>
echo   or:        %~nx0 ^<corp-url^>
endlocal & exit /b 1

:err_fetch
echo [ERROR] fetch from GitHub failed.
endlocal & exit /b 1

:err_checkout
echo [ERROR] cannot switch to "main" - uncommitted changes or wrong repo.
endlocal & exit /b 1

:err_merge
echo [ERROR] local main diverged from GitHub - push local commits to GitHub first or rebase, then re-run.
endlocal & exit /b 1

:err_push
echo [ERROR] push to corp failed.
endlocal & exit /b 1
