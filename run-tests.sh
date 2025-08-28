#!/bin/bash

# Pre-commit/PR test script for Android project
# This script runs all tests before committing or creating a pull request

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR"

echo "=========================================="
echo " Running Android Project Tests"
echo "=========================================="

cd "$PROJECT_DIR"

# Function to check command success
check_result() {
    if [ $? -ne 0 ]; then
        echo " $1 failed!"
        exit 1
    else
        echo "
         $1 passed!"
    fi
}

# Clean previous build artifacts
echo ""
echo " Cleaning build artifacts..."
./gradlew clean
check_result "Clean"

# Run lint checks
echo ""
echo " Running lint checks..."
./gradlew lint
check_result "Lint checks"

# Run unit tests
echo ""
echo " Running unit tests..."
./gradlew test --stacktrace
check_result "Unit tests"

# Generate test coverage report
echo ""
echo " Generating test coverage report..."
./gradlew testDebugUnitTestCoverage || true

# Check if device is connected for instrumented tests
if adb devices | grep -q "device$"; then
    echo ""
    echo " Device connected. Running instrumented tests..."
    ./gradlew connectedAndroidTest
    check_result "Instrumented tests"
else
    echo ""
    echo "  No device connected. Skipping instrumented tests."
    echo "   To run instrumented tests, connect a device or start an emulator."
fi

# Build debug APK
echo ""
echo " Building debug APK..."
./gradlew assembleDebug
check_result "Debug build"

# Build release APK (if keystore exists)
if [ -f "/home/jinwoo/Desktop/안드로이드인증키/my-release-key.jks" ]; then
    echo ""
    echo " Building release APK..."
    ./gradlew assembleRelease
    check_result "Release build"
else
    echo ""
    echo "  Release keystore not found. Skipping release build."
fi

# Check for common issues
echo ""
echo " Checking for common issues..."

# Check for hardcoded secrets
if grep -r "api_key\|password\|secret" --include="*.kt" --include="*.java" --exclude-dir=build app/src/main; then
    echo "  Warning: Possible hardcoded secrets found. Please review."
fi

# Check for TODO comments
TODO_COUNT=$(grep -r "TODO\|FIXME" --include="*.kt" --include="*.java" --exclude-dir=build app/src/main | wc -l)
if [ $TODO_COUNT -gt 0 ]; then
    echo " Found $TODO_COUNT TODO/FIXME comments"
fi

# Memory leak check reminder
echo ""
echo " Remember to check LeakCanary reports in debug builds for memory leaks"

# Summary
echo ""
echo "=========================================="
echo " All tests passed successfully!"
echo "=========================================="
echo ""
echo "Test Results Summary:"
echo "  • Lint: "
echo "  • Unit Tests: "
echo "  • Build: "

if adb devices | grep -q "device$"; then
    echo "  • Instrumented Tests: "
else
    echo "  • Instrumented Tests:   (skipped - no device)"
fi

echo ""
echo " Test reports available at:"
echo "  • Unit tests: $PROJECT_DIR/app/build/reports/tests/"
echo "  • Lint: $PROJECT_DIR/app/build/reports/lint/"
echo "  • Coverage: $PROJECT_DIR/app/build/reports/coverage/"

echo ""
echo "Ready to commit/push! "