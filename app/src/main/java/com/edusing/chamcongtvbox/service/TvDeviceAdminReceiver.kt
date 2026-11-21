package com.edusing.chamcongtvbox.service

import android.app.admin.DeviceAdminReceiver

/**
 * Receiver quản trị thiết bị phục vụ chế độ **Device Owner** trên Android TV Box chuyên dụng:
 * khi cài 1 lệnh ADB (`adb shell dpm set-device-owner com.edusing.chamcongtvbox/.service.TvDeviceAdminReceiver`),
 * ứng dụng được Android cho phép tự tải và cài đè bản APK mới 100% âm thầm không cần màn hình hay Root.
 */
class TvDeviceAdminReceiver : DeviceAdminReceiver()
