package com.cloak.server.usecase;

import com.cloak.server.domain.device.DeviceKeyBundle;

/** Register a device's public bundle for an authenticated owner. */
public record RegisterDeviceKeysCommand(String ownerSub, DeviceKeyBundle bundle) {}
