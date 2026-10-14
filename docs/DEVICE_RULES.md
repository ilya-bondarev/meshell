# Device ownership

`TelemetryModule` owns sampling, counter deltas and measurement conversions. Raw byte fields remain available to tool callers; the monitor reads derived display values. Missing CPU data is reported as unavailable, not a fabricated sample. Android restrictions can prevent a measurement.

`DeviceTools` owns USB enumeration and transfers for the agent. Android asks the user for permission per device; a denied request is reported, not retried silently.
