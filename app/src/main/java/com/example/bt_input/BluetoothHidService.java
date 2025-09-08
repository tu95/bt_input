package com.example.bt_input;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BluetoothHidService {
    private static final String TAG = "BluetoothHidService";
    
    // HID 设备描述符 - 包含鼠标和滚轮功能
    private static final byte[] HID_MOUSE_DESCRIPTOR = {
        (byte) 0x05, (byte) 0x01,  // Usage Page (Generic Desktop)
        (byte) 0x09, (byte) 0x02,  // Usage (Mouse)
        (byte) 0xA1, (byte) 0x01,  // Collection (Application)
        (byte) 0x09, (byte) 0x01,  //   Usage (Pointer)
        (byte) 0xA1, (byte) 0x00,  //   Collection (Physical)
        (byte) 0x05, (byte) 0x09,  //     Usage Page (Buttons)
        (byte) 0x19, (byte) 0x01,  //     Usage Minimum (1)
        (byte) 0x29, (byte) 0x03,  //     Usage Maximum (3)
        (byte) 0x15, (byte) 0x00,  //     Logical Minimum (0)
        (byte) 0x25, (byte) 0x01,  //     Logical Maximum (1)
        (byte) 0x95, (byte) 0x03,  //     Report Count (3)
        (byte) 0x75, (byte) 0x01,  //     Report Size (1)
        (byte) 0x81, (byte) 0x02,  //     Input (Data, Variable, Absolute)
        (byte) 0x95, (byte) 0x01,  //     Report Count (1)
        (byte) 0x75, (byte) 0x05,  //     Report Size (5)
        (byte) 0x81, (byte) 0x03,  //     Input (Constant, Variable, Absolute)
        (byte) 0x05, (byte) 0x01,  //     Usage Page (Generic Desktop)
        (byte) 0x09, (byte) 0x30,  //     Usage (X)
        (byte) 0x09, (byte) 0x31,  //     Usage (Y)
        (byte) 0x15, (byte) 0x81,  //     Logical Minimum (-127)
        (byte) 0x25, (byte) 0x7F,  //     Logical Maximum (127)
        (byte) 0x75, (byte) 0x08,  //     Report Size (8)
        (byte) 0x95, (byte) 0x02,  //     Report Count (2)
        (byte) 0x81, (byte) 0x06,  //     Input (Data, Variable, Relative)
        (byte) 0x09, (byte) 0x38,  //     Usage (Wheel)
        (byte) 0x15, (byte) 0x81,  //     Logical Minimum (-127)
        (byte) 0x25, (byte) 0x7F,  //     Logical Maximum (127)
        (byte) 0x75, (byte) 0x08,  //     Report Size (8)
        (byte) 0x95, (byte) 0x01,  //     Report Count (1)
        (byte) 0x81, (byte) 0x06,  //     Input (Data, Variable, Relative)
        (byte) 0xC0,               //   End Collection
        (byte) 0xC0                // End Collection
    };

    private Context context;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothHidDevice bluetoothHidDevice;
    private boolean isRegistered = false;
    private boolean isConnected = false;
    private android.bluetooth.BluetoothDevice connectedDevice = null;

    public interface HidServiceCallback {
        void onServiceConnected();
        void onServiceDisconnected();
        void onAppRegistered();
        void onAppUnregistered();
        void onDeviceConnected();
        void onDeviceDisconnected();
        void onError(String error);
    }

    private HidServiceCallback callback;

    public BluetoothHidService(Context context, HidServiceCallback callback) {
        this.context = context;
        this.callback = callback;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    @SuppressLint("MissingPermission")
    public void startHidService() {
        if (bluetoothAdapter == null) {
            callback.onError("蓝牙适配器不可用");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            callback.onError("请先启用蓝牙");
            return;
        }

        // 设置设备名称为 bt_input
        try {
            bluetoothAdapter.setName("bt_input");
            Log.d(TAG, "设备名称已设置为: bt_input");
        } catch (SecurityException e) {
            Log.w(TAG, "设置设备名称权限不足: " + e.getMessage());
        }
        
        // 开启蓝牙可发现性 (在后台处理，避免阻塞)
        try {
            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300); // 5分钟可发现
            discoverableIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(discoverableIntent);
            Log.d(TAG, "可发现性请求已发送");
        } catch (Exception e) {
            Log.w(TAG, "启动可发现性失败: " + e.getMessage());
            // 可发现性失败不应该阻止HID服务启动
        }

        // 获取 HID 设备 Profile
        bluetoothAdapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    bluetoothHidDevice = (BluetoothHidDevice) proxy;
                    Log.d(TAG, "HID 设备服务已连接");
                    callback.onServiceConnected();
                    registerHidApp();
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                Log.d(TAG, "HID 设备服务已断开");
                bluetoothHidDevice = null;
                callback.onServiceDisconnected();
            }
        }, BluetoothProfile.HID_DEVICE);
    }

    @SuppressLint("MissingPermission")
    private void registerHidApp() {
        if (bluetoothHidDevice == null) {
            callback.onError("HID 设备服务未连接");
            return;
        }

        // SDP 设置
        BluetoothHidDeviceAppSdpSettings sdpSettings = new BluetoothHidDeviceAppSdpSettings(
                "bt_input",                    // 设备名称
                "蓝牙鼠标设备",                // 设备描述
                "BT_Input Corp",               // 供应商
                BluetoothHidDevice.SUBCLASS1_MOUSE,  // 子类：鼠标设备
                HID_MOUSE_DESCRIPTOR          // HID 描述符
        );

        // QoS 设置 - 优化为低延迟配置
        BluetoothHidDeviceAppQosSettings qosSettings = new BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED,  // 使用保证服务
                500, 1, 0, 10000,  // 减少延迟：更小的令牌速率、更小的令牌桶、更短的峰值带宽
                BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED   // 保证服务质量
        );

        // 注册应用
        try {
            boolean result = bluetoothHidDevice.registerApp(sdpSettings, null, qosSettings, Runnable::run, hidDeviceCallback);
            
            if (result) {
                Log.d(TAG, "HID 应用注册请求已发送");
            } else {
                Log.e(TAG, "HID 应用注册返回false");
                callback.onError("HID 应用注册失败");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "HID 应用注册权限不足: " + e.getMessage());
            callback.onError("HID 应用注册权限不足: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "HID 应用注册异常: " + e.getMessage());
            callback.onError("HID 应用注册异常: " + e.getMessage());
        }
    }

    private final BluetoothHidDevice.Callback hidDeviceCallback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(android.bluetooth.BluetoothDevice pluggedDevice, boolean registered) {
            Log.d(TAG, "应用状态已更改: registered=" + registered);
            isRegistered = registered;
            if (registered) {
                callback.onAppRegistered();
            } else {
                callback.onAppUnregistered();
            }
        }

        @Override
        public void onConnectionStateChanged(android.bluetooth.BluetoothDevice device, int state) {
            Log.d(TAG, "连接状态已更改: " + state + " 设备: " + device.getName());
            boolean wasConnected = isConnected;
            isConnected = (state == android.bluetooth.BluetoothProfile.STATE_CONNECTED);
            
            if (isConnected && !wasConnected) {
                connectedDevice = device;
                Log.d(TAG, "设备已连接: " + device.getName());
                callback.onDeviceConnected();
            } else if (!isConnected && wasConnected) {
                connectedDevice = null;
                Log.d(TAG, "设备已断开: " + device.getName());
                callback.onDeviceDisconnected();
            }
        }

        @Override
        public void onGetReport(android.bluetooth.BluetoothDevice device, byte type, byte id, int bufferSize) {
            Log.d(TAG, "收到获取报告请求");
            // 处理获取报告请求
        }

        @Override
        public void onSetReport(android.bluetooth.BluetoothDevice device, byte type, byte id, byte[] data) {
            Log.d(TAG, "收到设置报告请求");
            // 处理设置报告请求
        }

        @Override
        public void onVirtualCableUnplug(android.bluetooth.BluetoothDevice device) {
            Log.d(TAG, "虚拟电缆已拔出");
            // 处理虚拟电缆拔出
        }
    };

    @SuppressLint("MissingPermission")
    public boolean sendMouseReport(byte buttons, byte deltaX, byte deltaY) {
        return sendMouseReport(buttons, deltaX, deltaY, (byte)0);
    }

    @SuppressLint("MissingPermission")
    public boolean sendMouseReport(byte buttons, byte deltaX, byte deltaY, byte scroll) {
        // 快速状态检查，减少日志输出
        if (bluetoothHidDevice == null || !isRegistered || !isConnected || connectedDevice == null) {
            return false;
        }

        // 构造鼠标报告 (4字节：按钮、X位移、Y位移、滚轮)
        byte[] report = {buttons, deltaX, deltaY, scroll};
        
        // 直接发送，移除调试日志以提高性能
        boolean result = bluetoothHidDevice.sendReport(connectedDevice, 0, report);
        if (!result) {
            // 只在失败时尝试备用reportId，减少不必要的尝试
            result = bluetoothHidDevice.sendReport(connectedDevice, 1, report);
        }
        
        return result;
    }

    @SuppressLint("MissingPermission")
    public void stopHidService() {
        if (bluetoothHidDevice != null && isRegistered) {
            bluetoothHidDevice.unregisterApp();
        }
        if (bluetoothAdapter != null) {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, bluetoothHidDevice);
        }
        isRegistered = false;
        bluetoothHidDevice = null;
    }

    public boolean isRegistered() {
        return isRegistered;
    }

    public boolean isConnected() {
        return isConnected;
    }

}
