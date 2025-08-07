# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build debug version
./gradlew assembleDebug

# Build release version (requires signing keystore)
./gradlew assembleRelease

# Install debug version to connected device
./gradlew installDebug

# Run unit tests
./gradlew test

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Clean build artifacts
./gradlew clean

# Generate test coverage reports
./gradlew testDebugUnitTestCoverage

# Lint checks
./gradlew lint
```

## Project Architecture

This is a sophisticated Android application implementing **real-time sensor data collection with machine learning detection** and advanced performance monitoring.

### Core Architecture Pattern: MVVM
- **ViewModels**: Handle UI logic and state (`HomeViewModel`, `DashboardViewModel`, `NotificationsViewModel`)
- **Repositories**: Data layer abstraction (`HomeRepository`)
- **Models**: Data classes and entities
- **Views**: Activities and Fragments with ViewBinding

### Key Architectural Components

1. **Multi-Sensor Data Collection System**
   - `SensorCollector`: Aggregates GPS, IMU, GNSS, Camera data
   - `DataSynchronizer`: Timestamp synchronization across sensors
   - `LogManager`: Handles video/data persistence

2. **Machine Learning Pipeline**
   - YOLO-based object detection with TensorFlow Lite
   - GPU acceleration with CPU fallback
   - Real-time inference optimization

3. **Advanced 5-Layer Monitoring System**
   - `FileLogger`: Centralized logging with automatic rotation
   - `ResourceMonitor`: System resource tracking
   - `AdvancedPerformanceMonitor`: FPS monitoring, ANR detection, heap analysis
   - `GpuMemoryMonitor`: GPU memory pressure detection and leak prevention
   - `PerfettoTracer`: System-level trace collection

4. **Custom Memory Management**
   - `BitmapPoolManager`: Tagged bitmap pooling with health monitoring
   - Automatic memory pressure detection and cleanup
   - Emergency recovery procedures for critical memory states

## Key Dependencies & Versions

- **Kotlin**: 2.0.21
- **Android Gradle Plugin**: 8.9.1
- **Compile/Target SDK**: 35, Min SDK: 30
- **TensorFlow Lite**: 2.16.1 (with GPU delegate)
- **OkHttp**: 4.12.0
- **Retrofit**: 2.9.0
- **Firebase BOM**: 32.7.1 (Crashlytics, Performance, Analytics)
- **LeakCanary**: 2.14 (debug builds only)

## Critical Development Notes

### Performance Monitoring System
The app implements enterprise-level monitoring with automatic recovery mechanisms:
- **FPS monitoring** with adaptive frame skipping
- **Memory leak detection** with automatic bitmap cleanup
- **GPU memory monitoring** with pressure-based throttling
- **ANR detection** with stack trace capture
- **Heap dump generation** for critical memory states

### Memory Management Patterns
- **Custom BitmapPool**: Use `BitmapPoolManager.getBitmap()` and `recycleBitmap()` consistently
- **GPU Memory**: Monitor via `GpuMemoryMonitor` - implements automatic throttling
- **Memory Pressure**: System automatically adjusts performance based on available memory

### Machine Learning Integration
- **Model Loading**: YOLO models loaded via TensorFlow Lite with GPU delegate preference
- **Inference Threading**: Background inference with UI thread result callbacks
- **Performance Optimization**: Adaptive frame skipping based on inference latency

### Data Collection Patterns
- **Sensor Data**: All sensors managed through `SensorCollector` singleton
- **Data Sync**: Use `DataSynchronizer` for timestamp alignment across sensors
- **Persistence**: `LogManager` handles automatic data/video saving with rotation

## Testing Approach

- **Unit Tests**: Located in `/app/src/test/java/` using JUnit 4.13.2
- **Instrumented Tests**: Located in `/app/src/androidTest/java/` using AndroidX Test + Espresso
- **Performance Testing**: Use built-in monitoring system metrics
- **Memory Testing**: LeakCanary integration in debug builds

## Release Configuration

- **ProGuard**: Enabled with custom rules for TensorFlow Lite
- **Signing**: Release builds require keystore configuration in `app/build.gradle.kts`
- **Firebase**: Crashlytics and Performance monitoring enabled in release builds
- **Native Libraries**: Custom packaging rules for .so file conflict resolution

## Permission Requirements

Critical permissions that must be granted for full functionality:
- Camera (real-time detection)
- Fine/Coarse/Background Location (GPS data collection)
- External Storage Management (data persistence)
- High Sampling Rate Sensors (IMU data)
- Battery Optimization Exemption (background operation)

## Common Development Tasks

### Adding New Sensors
1. Extend `SensorCollector` with new sensor type
2. Update `DataSynchronizer` for timestamp alignment
3. Modify data models in repository layer
4. Update UI ViewModels if display needed

### Modifying Performance Monitoring
- **FPS Monitoring**: Modify `AdvancedPerformanceMonitor.startFpsMonitoring()`
- **Memory Alerts**: Update thresholds in `ResourceMonitor`
- **GPU Monitoring**: Adjust pressure levels in `GpuMemoryMonitor`

### ML Model Updates
1. Replace model files in `assets/` directory
2. Update model configuration in detection classes
3. Test GPU delegate compatibility
4. Verify inference performance meets real-time requirements

## File Structure Overview

- **Core Logic**: `/app/src/main/java/com/example/myapplication/`
- **UI Components**: `ui/` directory with MVVM structure
- **Data Layer**: `data/` directory (repositories, sensors, sync)
- **Monitoring System**: `Logsystem/` directory
- **Utilities**: `utils/` directory (bitmap management, helpers)
- **ML Models**: `assets/` directory
- **Build Config**: Root and `app/build.gradle.kts`

## Emergency Recovery Procedures

The app implements automatic recovery for critical states:
- **Memory Pressure**: Automatic bitmap cleanup and pool shrinking
- **GPU Memory Leaks**: Automatic context recreation and resource cleanup
- **ANR Prevention**: Background thread migration for heavy operations
- **Performance Degradation**: Adaptive frame rate and inference rate adjustment

## Debugging and Profiling

- **Logging**: All components use `FileLogger` - check device storage for log files
- **Memory Analysis**: LeakCanary reports available in debug builds
- **Performance**: Built-in FPS and resource monitoring with automatic reporting
- **GPU Issues**: `GpuMemoryMonitor` provides detailed GPU memory state tracking
- **System Tracing**: Use `PerfettoTracer` for system-level performance analysis