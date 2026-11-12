# Chuck — Vehicle Diagnostics

**A better way to find the cause.**

Chuck is a standalone Android diagnostic assistant. It is intentionally separate from Grand Lake Alert.

## Current foundation
- Compact tablet-friendly Compose dashboard.
- Symptom intake and investigation placeholder.
- No fabricated vehicle readings: values remain blank until a real adapter supplies data.
- Android project configured for Java 17 / Android Gradle Plugin 8.7.3.

## Adapter plan
The first target adapter is a **Wi-Fi ELM327**. Connection support must accommodate differing adapter IP addresses and TCP ports rather than assuming every clone uses the same defaults. The tablet may need to remain connected to the adapter's Wi-Fi network even when that network has no internet access.

## Diagnostic principles
- Understand the failure before recommending parts.
- Separate observed facts, working hypotheses, tests, and confirmed causes.
- Generic OBD-II data is not the same as full manufacturer-specific module access.
- Start read-only; do not run actuator commands without explicit authorization and safety checks.
- Preserve case history so confirmed repairs and failed attempts can inform later diagnosis.

## Build
Open this repository in Android Studio and build the **debug** variant, or use the included GitHub Actions workflow.

This is an early project foundation, not yet a functioning scan tool. OBD communication, VIN decoding, live PIDs, DTC retrieval, online research, and persistent case storage are still to be implemented.
