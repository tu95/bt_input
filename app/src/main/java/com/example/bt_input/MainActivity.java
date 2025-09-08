package com.example.bt_input; // 确保这是你的包名

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements BluetoothHidService.HidServiceCallback {

    private static final String TAG = "MainActivity_BluetoothMouse";
    private static final int DEFAULT_MOUSE_SENSITIVITY = 5; // 默认鼠标灵敏度
    private static final int DEFAULT_CLICK_SENSITIVITY = 5; // 默认点击灵敏度

    private View touchpadView;
    private View scrollWheelView;
    private TextView textViewCoordinates;
    private Button buttonConnect;
    private Button buttonRandomMove;
    private TextView textViewStatus;
    private SeekBar seekBarMouseSensitivity;
    private SeekBar seekBarClickSensitivity;
    private TextView textViewMouseSensitivity;
    private TextView textViewClickSensitivity;

    // 灵敏度设置
    private int mouseSensitivity = DEFAULT_MOUSE_SENSITIVITY;
    private int clickSensitivity = DEFAULT_CLICK_SENSITIVITY;

    private float lastTouchX;
    private float lastTouchY;
    private float initialTouchX;
    private float initialTouchY;
    private long touchStartTime;
    
    // 滚轮相关变量
    private float lastScrollY;
    private int scrollAccumulator = 0;
    
    // 随机滑动相关变量
    private boolean isRandomMoving = false;
    private Thread randomMoveThread;
    
    // 防止息屏相关变量
    private PowerManager.WakeLock wakeLock;

    // 蓝牙HID相关变量
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothHidService hidService;
    private boolean isHidRegistered = false;

    // 权限请求启动器
    private ActivityResultLauncher<String[]> permissionLauncher;
    private ActivityResultLauncher<Intent> bluetoothEnableLauncher;

    @SuppressLint("ClickableViewAccessibility") // 解释见下方
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // 初始化蓝牙适配器
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // 初始化HID服务
        hidService = new BluetoothHidService(this, this);

        // 初始化WakeLock
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "BT_Input:KeepScreenOn");

        // 初始化权限请求启动器
        initializePermissionLaunchers();

        // 初始化视图
        touchpadView = findViewById(R.id.touchpadView);
        scrollWheelView = findViewById(R.id.scrollWheelView);
        textViewCoordinates = findViewById(R.id.textViewCoordinates);
        buttonConnect = findViewById(R.id.buttonConnect);
        buttonRandomMove = findViewById(R.id.buttonRandomMove);
        textViewStatus = findViewById(R.id.textViewStatus);
        seekBarMouseSensitivity = findViewById(R.id.seekBarMouseSensitivity);
        seekBarClickSensitivity = findViewById(R.id.seekBarClickSensitivity);
        textViewMouseSensitivity = findViewById(R.id.textViewMouseSensitivity);
        textViewClickSensitivity = findViewById(R.id.textViewClickSensitivity);

        // 设置 WindowInsets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // 设置触摸板的触摸监听器
        touchpadView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();

                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        // 记录初始触摸点和时间
                        lastTouchX = event.getX();
                        lastTouchY = event.getY();
                        initialTouchX = lastTouchX;
                        initialTouchY = lastTouchY;
                        touchStartTime = System.currentTimeMillis();
                        // 移除调试日志以提高性能
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float currentX = event.getX();
                        float currentY = event.getY();

                        // 计算相对位移 (deltaX, deltaY)
                        float deltaX = currentX - lastTouchX;
                        float deltaY = currentY - lastTouchY;

                        // 更新上一次的触摸点
                        lastTouchX = currentX;
                        lastTouchY = currentY;

                        // 直接发送HID鼠标移动数据，跳过UI更新以减少延迟
                        if (isHidRegistered) {
                            sendHidMouseMovement(deltaX, deltaY);
                        }

                        return true;

                    case MotionEvent.ACTION_UP:
                        // 检测是否为点击（基于点击灵敏度）
                        if (isHidRegistered && isClickDetected()) {
                            sendHidMouseClick();
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        return true;

                    default:
                        return false;
                }
            }
        });

        // 设置滚轮的触摸监听器
        scrollWheelView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();

                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        lastScrollY = event.getY();
                        scrollAccumulator = 0;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float currentY = event.getY();
                        float deltaY = lastScrollY - currentY; // 向上为正，向下为负
                        
                        // 简化累积滚动值计算
                        scrollAccumulator += (int)(deltaY * mouseSensitivity);
                        
                        // 降低阈值，提高响应性
                        if (Math.abs(scrollAccumulator) >= 10) {
                            int scrollSteps = scrollAccumulator / 10;
                            scrollAccumulator = scrollAccumulator % 10;
                            
                            if (isHidRegistered && scrollSteps != 0) {
                                // 直接发送，移除不必要的方法调用
                                hidService.sendMouseReport((byte)0, (byte)0, (byte)0, (byte)scrollSteps);
                            }
                        }
                        
                        lastScrollY = currentY;
                        return true;

                    case MotionEvent.ACTION_UP:
                        scrollAccumulator = 0;
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        scrollAccumulator = 0;
                        return true;

                    default:
                        return false;
                }
            }
        });

        // 设置灵敏度控制
        setupSensitivityControls();

        // 蓝牙鼠标服务按钮的点击事件
        buttonConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isHidRegistered) {
                    startHidService();
                } else {
                    stopHidService();
                }
            }
        });

        // 随机滑动按钮的点击事件
        buttonRandomMove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isRandomMoving) {
                    startRandomMovement();
                } else {
                    stopRandomMovement();
                }
            }
        });

        // 检查蓝牙支持
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "设备不支持蓝牙", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 检查蓝牙权限
        checkBluetoothPermissions();
    }

    private void setupSensitivityControls() {
        // 鼠标移动灵敏度SeekBar
        seekBarMouseSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mouseSensitivity = progress;
                textViewMouseSensitivity.setText(String.valueOf(progress));
                Log.d(TAG, "鼠标灵敏度设置为: " + progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 点击灵敏度SeekBar
        seekBarClickSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                clickSensitivity = progress;
                textViewClickSensitivity.setText(String.valueOf(progress));
                Log.d(TAG, "点击灵敏度设置为: " + progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 设置初始值
        seekBarMouseSensitivity.setProgress(mouseSensitivity);
        seekBarClickSensitivity.setProgress(clickSensitivity);
        textViewMouseSensitivity.setText(String.valueOf(mouseSensitivity));
        textViewClickSensitivity.setText(String.valueOf(clickSensitivity));
    }

    // 检测是否为点击操作
    private boolean isClickDetected() {
        long touchDuration = System.currentTimeMillis() - touchStartTime;
        float totalMovement = Math.abs(lastTouchX - initialTouchX) + Math.abs(lastTouchY - initialTouchY);
        
        // 点击检测参数（基于点击灵敏度）
        int maxClickDuration = 200 + (10 - clickSensitivity) * 50; // 200ms-700ms
        float maxClickMovement = 20 + (10 - clickSensitivity) * 10; // 20-120像素
        
        boolean isClick = touchDuration <= maxClickDuration && totalMovement <= maxClickMovement;
        
        Log.d(TAG, "点击检测: 持续时间=" + touchDuration + "ms, 移动距离=" + totalMovement + 
              "px, 灵敏度=" + clickSensitivity + ", 判定为点击=" + isClick);
        
        return isClick;
    }

    // 更新坐标和滚轮显示
    private void updateCoordinatesDisplay(float deltaX, float deltaY, int scroll) {
        String coordinatesText = "触摸板: X: " + String.format("%.1f", deltaX) + 
                               ", Y: " + String.format("%.1f", deltaY) + 
                               " | 滚轮: " + scroll;
        
        if (isHidRegistered) {
            if (hidService != null && hidService.isConnected()) {
                coordinatesText += " [已连接]";
            } else {
                coordinatesText += " [等待连接]";
            }
        } else {
            coordinatesText += " [HID未注册]";
        }
        
        textViewCoordinates.setText(coordinatesText);
    }


    // 开始随机移动
    private void startRandomMovement() {
        if (isRandomMoving || !isHidRegistered || hidService == null || !hidService.isConnected()) {
            Toast.makeText(this, "请先连接蓝牙鼠标", Toast.LENGTH_SHORT).show();
            return;
        }

        isRandomMoving = true;
        buttonRandomMove.setText("停止随机滑动");
        
        // 获取WakeLock，防止息屏
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
            Log.d(TAG, "获取WakeLock，防止息屏");
        }
        
        randomMoveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                java.util.Random random = new java.util.Random();
                
                while (isRandomMoving) {
                    try {
                        // 生成随机的X和Y移动值 (-20到20像素)
                        int randomX = random.nextInt(41) - 20; // -20 到 20
                        int randomY = random.nextInt(41) - 20; // -20 到 20
                        
                        // 应用灵敏度调节
                        float deltaX = randomX * mouseSensitivity * 0.8f;
                        float deltaY = randomY * mouseSensitivity * 0.8f;
                        
                        // 确保最小移动阈值
                        float minMovement = 3.0f;
                        if (Math.abs(deltaX) < minMovement && Math.abs(deltaY) < minMovement) {
                            deltaX = deltaX >= 0 ? minMovement : -minMovement;
                            deltaY = deltaY >= 0 ? minMovement : -minMovement;
                        }
                        
                        // 限制移动范围
                        deltaX = Math.max(-127, Math.min(127, deltaX));
                        deltaY = Math.max(-127, Math.min(127, deltaY));
                        
                        // 发送鼠标移动数据（不点击）
                        if (isHidRegistered && hidService != null) {
                            hidService.sendMouseReport((byte)0, (byte)deltaX, (byte)deltaY);
                            // 移除UI更新和日志以提高性能
                        }
                        
                        // 随机间隔 (100-500ms)
                        Thread.sleep(random.nextInt(401) + 100);
                        
                    } catch (InterruptedException e) {
                        Log.d(TAG, "随机移动线程被中断");
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, "随机移动异常: " + e.getMessage());
                        break;
                    }
                }
            }
        });
        
        randomMoveThread.start();
        Log.d(TAG, "开始随机移动");
    }

    // 停止随机移动
    private void stopRandomMovement() {
        if (!isRandomMoving) {
            return;
        }

        isRandomMoving = false;
        buttonRandomMove.setText("随机滑动");
        
        // 释放WakeLock，恢复正常息屏
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "释放WakeLock，恢复正常息屏");
        }
        
        if (randomMoveThread != null && randomMoveThread.isAlive()) {
            randomMoveThread.interrupt();
            try {
                randomMoveThread.join(1000); // 等待最多1秒
            } catch (InterruptedException e) {
                Log.d(TAG, "等待随机移动线程结束时被中断");
            }
        }
        
        Log.d(TAG, "停止随机移动");
    }


    private void initializePermissionLaunchers() {
        // 权限请求启动器
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean allGranted = true;
                    for (Boolean granted : result.values()) {
                        if (!granted) {
                            allGranted = false;
                            break;
                        }
                    }
                    if (allGranted) {
                        checkBluetoothEnabled();
                    } else {
                        Toast.makeText(this, "需要蓝牙权限才能使用此功能", Toast.LENGTH_SHORT).show();
                    }
                }
        );

        // 蓝牙启用启动器
        bluetoothEnableLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        Toast.makeText(this, "蓝牙已启用", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "需要启用蓝牙才能使用此功能", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    private void checkBluetoothPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();
        
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) 
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(android.Manifest.permission.BLUETOOTH_CONNECT);
        }
        
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!permissionsNeeded.isEmpty()) {
            permissionLauncher.launch(permissionsNeeded.toArray(new String[0]));
        } else {
            checkBluetoothEnabled();
        }
    }

    @SuppressLint("MissingPermission")
    private void checkBluetoothEnabled() {
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            bluetoothEnableLauncher.launch(enableBtIntent);
        }
    }

    private void startHidService() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "请先启用蓝牙", Toast.LENGTH_SHORT).show();
            return;
        }

        updateConnectionStatus("正在启动HID服务...");
        buttonConnect.setEnabled(false);
        
        // 设置15秒超时
        new Thread(() -> {
            try {
                Thread.sleep(15000); // 15秒超时
                runOnUiThread(() -> {
                    if (!isHidRegistered && !buttonConnect.isEnabled()) {
                        Log.w(TAG, "HID服务启动超时，重置状态");
                        Toast.makeText(MainActivity.this, "HID服务启动超时，请重试", Toast.LENGTH_SHORT).show();
                        updateConnectionStatus("启动超时，请重试");
                        buttonConnect.setText("启动蓝牙鼠标服务");
                        buttonConnect.setEnabled(true);
                    }
                });
            } catch (InterruptedException e) {
                Log.d(TAG, "超时检查线程被中断");
            }
        }).start();
        
        hidService.startHidService();
    }

    private void stopHidService() {
        updateConnectionStatus("正在停止HID服务...");
        buttonConnect.setEnabled(false);
        hidService.stopHidService();
    }

    private void updateConnectionStatus(String status) {
        textViewStatus.setText(status);
    }

    // 发送HID鼠标移动数据
    private void sendHidMouseMovement(float deltaX, float deltaY) {
        if (!isHidRegistered || hidService == null) {
            return;
        }

        // 简化的灵敏度计算，减少浮点运算
        int sensitivity = mouseSensitivity;
        
        // 直接转换为字节值，减少计算
        int scaledDeltaX = (int)(deltaX * sensitivity);
        int scaledDeltaY = (int)(deltaY * sensitivity);
        
        // 限制范围
        scaledDeltaX = Math.max(-127, Math.min(127, scaledDeltaX));
        scaledDeltaY = Math.max(-127, Math.min(127, scaledDeltaY));
        
        // 简化的最小移动阈值
        if (Math.abs(scaledDeltaX) < 1 && Math.abs(scaledDeltaY) < 1) {
            return;
        }
        
        // 直接发送，移除日志以提高性能
        hidService.sendMouseReport((byte)0, (byte)scaledDeltaX, (byte)scaledDeltaY);
    }

    // 发送HID鼠标点击数据
    private void sendHidMouseClick() {
        if (!isHidRegistered || hidService == null) {
            return;
        }

        // 发送按下事件
        byte leftButtonPressed = 0x01; // 左键按下
        boolean success1 = hidService.sendMouseReport(leftButtonPressed, (byte)0, (byte)0);
        
        // 短暂延迟后发送释放事件
        new android.os.Handler().postDelayed(() -> {
            byte noButtons = 0x00; // 无按钮按下
            boolean success2 = hidService.sendMouseReport(noButtons, (byte)0, (byte)0);
            
            if (success1 && success2) {
                Log.d(TAG, "发送HID点击数据成功");
            } else {
                Log.w(TAG, "发送HID点击数据失败");
            }
        }, 50); // 50ms延迟
    }

    // HID服务回调方法
    @Override
    public void onServiceConnected() {
        Log.d(TAG, "HID服务已连接");
        runOnUiThread(() -> updateConnectionStatus("HID服务已连接"));
    }

    @Override
    public void onServiceDisconnected() {
        Log.d(TAG, "HID服务已断开");
        runOnUiThread(() -> {
            isHidRegistered = false;
            updateConnectionStatus("HID服务已断开");
            buttonConnect.setText("启动蓝牙鼠标服务");
            buttonConnect.setEnabled(true);
            buttonRandomMove.setEnabled(false);
            
            // 停止随机移动
            stopRandomMovement();
        });
    }

    @Override
    public void onAppRegistered() {
        Log.d(TAG, "HID应用已注册");
        runOnUiThread(() -> {
            isHidRegistered = true;
            updateConnectionStatus("蓝牙鼠标已就绪 - 等待电脑连接 bt_input");
            buttonConnect.setText("停止蓝牙鼠标服务");
            buttonConnect.setEnabled(true);
            Toast.makeText(MainActivity.this, "蓝牙鼠标已注册！\n1.在电脑蓝牙设置中搜索\"bt_input\"\n2.连接后即可使用触摸板", Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onAppUnregistered() {
        Log.d(TAG, "HID应用已注销");
        // 停止随机移动
        stopRandomMovement();
        runOnUiThread(() -> {
            isHidRegistered = false;
            updateConnectionStatus("蓝牙鼠标已停止");
            buttonConnect.setText("启动蓝牙鼠标服务");
            buttonConnect.setEnabled(true);
            buttonRandomMove.setEnabled(false);
        });
    }

    @Override
    public void onDeviceConnected() {
        Log.d(TAG, "设备已连接到电脑");
        runOnUiThread(() -> {
            updateConnectionStatus("已连接到电脑 - 可以使用触摸板了！");
            buttonRandomMove.setEnabled(true);
            Toast.makeText(MainActivity.this, "电脑已连接！现在可以使用触摸板控制鼠标", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onDeviceDisconnected() {
        Log.d(TAG, "设备已从电脑断开");
        // 停止随机移动
        stopRandomMovement();
        runOnUiThread(() -> {
            updateConnectionStatus("蓝牙鼠标已就绪 - 等待电脑连接 bt_input");
            buttonRandomMove.setEnabled(false);
            Toast.makeText(MainActivity.this, "电脑已断开连接", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onError(String error) {
        Log.e(TAG, "HID错误: " + error);
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, "HID错误: " + error, Toast.LENGTH_SHORT).show();
            updateConnectionStatus("错误: " + error);
            
            // 重置状态和按钮
            isHidRegistered = false;
            buttonConnect.setText("启动蓝牙鼠标服务");
            buttonConnect.setEnabled(true);
            buttonRandomMove.setEnabled(false);
            
            // 停止随机移动
            stopRandomMovement();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "Activity从后台返回");
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "Activity进入后台，自动断开蓝牙连接");
        
        // 停止随机移动
        stopRandomMovement();
        
        // 自动断开HID服务
        if (isHidRegistered && hidService != null) {
            stopHidService();
            Toast.makeText(this, "应用进入后台，已自动断开蓝牙连接", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 停止随机移动
        stopRandomMovement();
        
        // 确保释放WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "在onDestroy中释放WakeLock");
        }
        
        if (hidService != null) {
            hidService.stopHidService();
        }
    }
}