package com.example.anhtuan.myapplication;

import android.app.Activity;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.UUID;

/**
 * Origin by Dave Smith, Double Encore Inc.
 * Use, change and edit by Anh Tuan Dinh, Nguyen Quang Toan Phong, Tran Xuan Huy under the open-source regulations
 * MainActivity
 */
public class MainActivity extends Activity implements BluetoothAdapter.LeScanCallback {
    private static final String TAG = "BluetoothGattActivity";

    private static final String DEVICE_NAME = "SensorTag";
    double[] acclerometer = new double[3];
    float[] gyroscope = new float[3];
    private float timestamp;
    final long currentTimestamp = System.nanoTime();
    float delta = 0;
    private static final float NS2S = 1.0f / 1000000000.0f;
    /* Accelerometer Service */
    private static final UUID ACCELERO_SERVICE = UUID.fromString("f000aa10-0451-4000-b000-000000000000");
    private static final UUID ACCELERO_DATA_CHAR = UUID.fromString("f000aa11-0451-4000-b000-000000000000");
    private static final UUID ACCELERO_CONFIG_CHAR = UUID.fromString("f000aa12-0451-4000-b000-000000000000");
    /* Barometric Pressure Service */
    private static final UUID GYROSCOPE_SERVICE = UUID.fromString("f000aa50-0451-4000-b000-000000000000");
    private static final UUID GYROSCOPE_DATA_CHAR = UUID.fromString("f000aa51-0451-4000-b000-000000000000");
    private static final UUID GYROSCOPE_CONFIG_CHAR = UUID.fromString("f000aa52-0451-4000-b000-000000000000");

    /* Client Configuration Descriptor */
    private static final UUID CONFIG_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");


    private BluetoothAdapter mBluetoothAdapter;
    private SparseArray<BluetoothDevice> mDevices;

    private BluetoothGatt mConnectedGatt;

    public TextView mGyroscope, mAccelero, mLinear, mDisplacement;
    public String tempData, humiData, presData;
    //public String filename = "My Data";


    private ProgressDialog mProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.activity_main);
        setProgressBarIndeterminate(true);

        /*
         * We are going to display the results in some text fields
         */
        mAccelero = (TextView) findViewById(R.id.text_accelero);
        mGyroscope = (TextView) findViewById(R.id.text_gyroscope);
        mLinear = (TextView) findViewById(R.id.text_linear);
        mDisplacement = (TextView)findViewById(R.id.linearDisplacement);


        /*
         * Bluetooth in Android 4.3 is accessed via the BluetoothManager, rather than
         * the old static BluetoothAdapter.getInstance()
         */
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        mBluetoothAdapter = manager.getAdapter();

        mDevices = new SparseArray<BluetoothDevice>();

        /*
         * A progress dialog will be needed while the connection process is
         * taking place
         */
        mProgress = new ProgressDialog(this);
        mProgress.setIndeterminate(true);
        mProgress.setCancelable(false);




    }

    @Override
    protected void onResume() {
        super.onResume();
        /*
         * We need to enforce that Bluetooth is first enabled, and take the
         * user to settings to enable it if they have not done so.
         */
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            //Bluetooth is disabled
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivity(enableBtIntent);
            finish();
            return;
        }

        /*
         * Check for Bluetooth LE Support.  In production, our manifest entry will keep this
         * from installing on these devices, but this will allow test devices or other
         * sideloads to report whether or not the feature exists.
         */
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "No LE Support.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        clearDisplayValues();
    }

    @Override
    protected void onPause() {
        super.onPause();
        //Make sure dialog is hidden
        mProgress.dismiss();
        //Cancel any scans in progress
        mHandler.removeCallbacks(mStopRunnable);
        mHandler.removeCallbacks(mStartRunnable);
        mBluetoothAdapter.stopLeScan(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        //Disconnect from any active tag connection
        if (mConnectedGatt != null) {
            mConnectedGatt.disconnect();
            mConnectedGatt = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Add the "scan" option to the menu
        getMenuInflater().inflate(R.menu.menu_main, menu);
        //Add any device elements we've discovered to the overflow menu
        for (int i=0; i < mDevices.size(); i++) {
            BluetoothDevice device = mDevices.valueAt(i);
            menu.add(0, mDevices.keyAt(i), 0, device.getName());
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_scan:
                mDevices.clear();
                startScan();
                return true;
            default:
                //Obtain the discovered device to connect with
                BluetoothDevice device = mDevices.get(item.getItemId());
                Log.i(TAG, "Connecting to "+device.getName());
                /*
                 * Make a connection with the device using the special LE-specific
                 * connectGatt() method, passing in a callback for GATT events
                 */
                mConnectedGatt = device.connectGatt(this, false, mGattCallback);
                //Display progress UI
                mHandler.sendMessage(Message.obtain(null, MSG_PROGRESS, "Connecting to "+device.getName()+"..."));
                return super.onOptionsItemSelected(item);
        }
    }

    private void clearDisplayValues() {
        mAccelero.setText("--------------------");
        mLinear.setText("--------------------");
        mGyroscope.setText("-------------------");
        mDisplacement.setText("-------------------");
        //mAccelero.setText("---");
        //mRead.setText("---");
    }


    private Runnable mStopRunnable = new Runnable() {
        @Override
        public void run() {
            stopScan();
        }
    };
    private Runnable mStartRunnable = new Runnable() {
        @Override
        public void run() {
            startScan();
        }
    };

    private void startScan() {
        mBluetoothAdapter.startLeScan(this);
        setProgressBarIndeterminateVisibility(true);

        mHandler.postDelayed(mStopRunnable, 2500);
    }

    private void stopScan() {
        mBluetoothAdapter.stopLeScan(this);
        setProgressBarIndeterminateVisibility(false);
    }

    /* BluetoothAdapter.LeScanCallback */

    @Override
    public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
        Log.i(TAG, "New LE Device: " + device.getName() + " @ " + rssi);
        /*
         * We are looking for SensorTag devices only, so validate the name
         * that each device reports before adding it to our collection
         */
        if (DEVICE_NAME.equals(device.getName())) {
            mDevices.put(device.hashCode(), device);
            //Update the overflow menu
            invalidateOptionsMenu();
        }
    }

    /*
     * In this callback, we've created a bit of a state machine to enforce that only
     * one characteristic be read or written at a time until all of our sensors
     * are enabled and we are registered to get notifications.
     */
    private BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        /* State Machine Tracking */
        private int mState = 0;

        private void reset() { mState = 0; }

        private void advance() { mState++; }

        /*
         * Send an enable command to each sensor by writing a configuration
         * characteristic.  This is specific to the SensorTag to keep power
         * low by disabling sensors you aren't using.
         */
        private void enableNextSensor(BluetoothGatt gatt) {
            BluetoothGattCharacteristic characteristic;
            switch (mState) {
                case 0:
                    Log.d(TAG, "Enabling accelerometer ");
                    characteristic = gatt.getService(ACCELERO_SERVICE)
                            .getCharacteristic(ACCELERO_CONFIG_CHAR);
                    characteristic.setValue(new byte[] {0x01});
                    break;
                /*case 1:
                    Log.d(TAG, "Enabling pressure");
                    characteristic = gatt.getService(PRESSURE_SERVICE)
                            .getCharacteristic(PRESSURE_CONFIG_CHAR);
                    characteristic.setValue(new byte[] {0x01});
                    break;*/

                case 1:
                    Log.d(TAG, "Enabling gyroscope");
                    characteristic = gatt.getService(GYROSCOPE_SERVICE)
                            .getCharacteristic(GYROSCOPE_CONFIG_CHAR);
                    characteristic.setValue(new byte[] {0x07});

                    Log.d(TAG, "Gyroscope enable");
                    break;
                default:
                    mHandler.sendEmptyMessage(MSG_DISMISS);
                    Log.i(TAG, "All Sensors Enabled");
                    return;
            }

            gatt.writeCharacteristic(characteristic);
        }

        /*
         * Read the data characteristic's value for each sensor explicitly
         */
        private void readNextSensor(BluetoothGatt gatt) {
            BluetoothGattCharacteristic characteristic;
            switch (mState) {
                case 0:
                    Log.d(TAG, "Reading accelerometer");
                    characteristic = gatt.getService(ACCELERO_SERVICE)
                            .getCharacteristic(ACCELERO_DATA_CHAR);
                    break;
                /*case 1:
                    Log.d(TAG, "Reading pressure");
                    characteristic = gatt.getService(PRESSURE_SERVICE)
                            .getCharacteristic(PRESSURE_DATA_CHAR);
                    break;*/

                case 1:
                    Log.d(TAG, "Reading Gyroscope");
                    characteristic = gatt.getService(GYROSCOPE_SERVICE)
                            .getCharacteristic(GYROSCOPE_DATA_CHAR);

                    break;
                default:
                    mHandler.sendEmptyMessage(MSG_DISMISS);
                    Log.i(TAG, "All Sensors Enabled");
                    return;
            }

            gatt.readCharacteristic(characteristic);
        }

        /*
         * Enable notification of changes on the data characteristic for each sensor
         * by writing the ENABLE_NOTIFICATION_VALUE flag to that characteristic's
         * configuration descriptor.
         */
        private void setNotifyNextSensor(BluetoothGatt gatt) {
            BluetoothGattCharacteristic characteristic;
            switch (mState) {
                case 0:
                    Log.d(TAG, "Set notify accelerometer");
                    characteristic = gatt.getService(ACCELERO_SERVICE)
                            .getCharacteristic(ACCELERO_DATA_CHAR);
                    break;
                case 1:
                    Log.d(TAG, "Set notify Gyroscope");
                    characteristic = gatt.getService(GYROSCOPE_SERVICE)
                            .getCharacteristic(GYROSCOPE_DATA_CHAR);

                    break;
                default:
                    mHandler.sendEmptyMessage(MSG_DISMISS);
                    Log.i(TAG, "All Sensors Enabled");
                    return;
            }

            //Enable local notifications
            gatt.setCharacteristicNotification(characteristic, true);
            //Enabled remote notifications
            BluetoothGattDescriptor desc = characteristic.getDescriptor(CONFIG_DESCRIPTOR);
            desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(desc);
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG, "Connection State Change: "+status+" -> "+connectionState(newState));
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                /*
                 * Once successfully connected, we must next discover all the services on the
                 * device before we can read and write their characteristics.
                 */
                gatt.discoverServices();
                mHandler.sendMessage(Message.obtain(null, MSG_PROGRESS, "Discovering Services..."));
            } else if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_DISCONNECTED) {
                /*
                 * If at any point we disconnect, send a message to clear the weather values
                 * out of the UI
                 */
                mHandler.sendEmptyMessage(MSG_CLEAR);
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                /*
                 * If there is a failure at any stage, simply disconnect
                 */
                gatt.disconnect();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "Services Discovered: "+status);
            mHandler.sendMessage(Message.obtain(null, MSG_PROGRESS, "Enabling Sensors..."));
            /*
             * With services discovered, we are going to reset our state machine and start
             * working through the sensors we need to enable
             */
            reset();
            enableNextSensor(gatt);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            //For each read, pass the data up to the UI thread to update the display
            mHandler.sendMessage(Message.obtain(null, MSG_FUSION, characteristic));
            if (ACCELERO_DATA_CHAR.equals(characteristic.getUuid())) {
                mHandler.sendMessage(Message.obtain(null, MSG_ACCELERO, characteristic));
            }
            /*if (PRESSURE_CAL_CHAR.equals(characteristic.getUuid())) {
                mHandler.sendMessage(Message.obtain(null, MSG_PRESSURE_CAL, characteristic));
            }*/
            if (GYROSCOPE_DATA_CHAR.equals(characteristic.getUuid())) {
                mHandler.sendMessage(Message.obtain(null, MSG_GYROSCOPE, characteristic));
            }

            //After reading the initial value, next we enable notifications
            setNotifyNextSensor(gatt);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            //After writing the enable flag, next we read the initial value
            readNextSensor(gatt);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            /*
             * After notifications are enabled, all updates from the device on characteristic
             * value changes will be posted here.  Similar to read, we hand these up to the
             * UI thread to update the display.
             */
            //if (timestamp != 0) {
            mHandler.sendMessage(Message.obtain(null, MSG_FUSION, characteristic));
                if (ACCELERO_DATA_CHAR.equals(characteristic.getUuid())) {
                    mHandler.sendMessage(Message.obtain(null, MSG_ACCELERO, characteristic));
                }
            /*if (PRESSURE_CAL_CHAR.equals(characteristic.getUuid())) {
                mHandler.sendMessage(Message.obtain(null, MSG_PRESSURE_CAL, characteristic));
            }*/
                if (GYROSCOPE_DATA_CHAR.equals(characteristic.getUuid())) {
                    mHandler.sendMessage(Message.obtain(null, MSG_GYROSCOPE, characteristic));
                }
                //delta = (currentTimestamp - timestamp)*NS2S;
            //}
            //timestamp = currentTimestamp;
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            //Once notifications are enabled, we move to the next sensor and start over with enable
            advance();
            enableNextSensor(gatt);
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            Log.d(TAG, "Remote RSSI: "+rssi);
        }

        private String connectionState(int status) {
            switch (status) {
                case BluetoothProfile.STATE_CONNECTED:
                    return "Connected";
                case BluetoothProfile.STATE_DISCONNECTED:
                    return "Disconnected";
                case BluetoothProfile.STATE_CONNECTING:
                    return "Connecting";
                case BluetoothProfile.STATE_DISCONNECTING:
                    return "Disconnecting";
                default:
                    return String.valueOf(status);
            }
        }
    };

    /*
     * We have a Handler to process event results on the main thread
     */
    private static final int MSG_GYROSCOPE = 103;
    private static final int MSG_FUSION = 102;
    private static final int MSG_ACCELERO = 101;
    //private static final int MSG_PRESSURE_CAL = 103;
    private static final int MSG_PROGRESS = 201;
    private static final int MSG_DISMISS = 202;
    private static final int MSG_CLEAR = 301;
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            BluetoothGattCharacteristic characteristic;
            switch (msg.what) {

                case MSG_GYROSCOPE:
                    characteristic = (BluetoothGattCharacteristic) msg.obj;
                    if (characteristic.getValue() == null) {
                        Log.w(TAG, "Error obtaining gyroscope value");
                        return;
                    }
                   updateGyroscopeValues(characteristic);
                    break;
                case MSG_ACCELERO:
                    characteristic = (BluetoothGattCharacteristic) msg.obj;
                    if (characteristic.getValue() == null) {
                        Log.w(TAG, "Error obtaining accelerometer value");
                        return;
                    }
                    updateAccelerometerValues(characteristic);
                    break;
                case MSG_FUSION:
                    characteristic = (BluetoothGattCharacteristic) msg.obj;
                    if (characteristic.getValue() == null) {
                        Log.w(TAG, "Error obtaining cal value");
                        return;
                    }
                    updateLinearAcceleration();
                    break;
                case MSG_PROGRESS:
                    mProgress.setMessage((String) msg.obj);
                    if (!mProgress.isShowing()) {
                        mProgress.show();
                    }
                    break;
                case MSG_DISMISS:
                    mProgress.hide();
                    break;
                case MSG_CLEAR:
                    clearDisplayValues();
                    break;
            }
        }
    };

    public void updateLinearAcceleration() {
        double[] gravity = new double[3];
        double alpha = 0.8;
        gravity[0] = alpha * gravity[0] + (1 - alpha) * acclerometer[0];
        gravity[1] = alpha * gravity[0] + (1 - alpha) * acclerometer[1];
        gravity[2] = alpha * gravity[0] + (1 - alpha) * acclerometer[2];
        double[] linearAcceleration = new double[3];
        linearAcceleration[0] = acclerometer[0] - gravity[0];
        linearAcceleration[1] = acclerometer[1] - gravity[1];
        linearAcceleration[2] = acclerometer[2] - gravity[2];
        double[] displacement = new double[3];
        displacement[0] = 0.5*linearAcceleration[0];
        displacement[1] = 0.5*linearAcceleration[1];
        displacement[2] = 0.5*linearAcceleration[2];

        mLinear.setText(String.format("x: %.1f%n y: %.1f%n z: %.1f%n",linearAcceleration[0],linearAcceleration[1],linearAcceleration[2]));
        mDisplacement.setText(String.format("x: %.1f%n y: %.1f%n z: %.1f%n",displacement[0],displacement[1],displacement[2]));
    }

    /* Methods to extract sensor data and update the UI */

    private void updateGyroscopeValues(BluetoothGattCharacteristic characteristic) {
        gyroscope[0] = shortSignedAtOffset(characteristic, 2) * (500f / 65536f);
        gyroscope[1] = shortSignedAtOffset(characteristic, 0) * (500f / 65536f) * -1;
        gyroscope[2] = shortSignedAtOffset(characteristic, 4) * (500f / 65536f);

        mGyroscope.setText(String.format("x: %.1f%n y: %.1f%n z: %.1f%n",gyroscope[0],gyroscope[1],gyroscope[2]));
    }
    private void updateAccelerometerValues(BluetoothGattCharacteristic characteristic){

        //if (timestamp != 0){

        //}
        //double acclerometer = 100; //SensorTagData.extractAccelerometer(characteristic);
        Integer x = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_SINT8, 0);
        Integer y = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_SINT8, 1);
        Integer z = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_SINT8, 2) * -1;

        acclerometer[0]= x /16.0;
        acclerometer[1]= y /16.0;
        acclerometer[2]= z /16.0;
        //for (int i=0; i<acclerometer.length;i++){
            mAccelero.setText(String.format("x: %.1f%n y: %.1f%n z: %.1f%n dt = %f", acclerometer[0], acclerometer[1],acclerometer[2], delta));
        //}
        //mAccelero.setText(toString()+acclerometer[1]);
    }

    private static Integer shortSignedAtOffset(BluetoothGattCharacteristic c, int offset) {
        Integer lowerByte = c.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, offset);
        Integer upperByte = c.getIntValue(BluetoothGattCharacteristic.FORMAT_SINT8, offset + 1); // Note: interpret MSB as signed.

        return (upperByte << 8) + lowerByte;
    }

    /*private int[] mPressureCals;
    private void updatePressureCals(BluetoothGattCharacteristic characteristic) {
        mPressureCals = SensorTagData.extractCalibrationCoefficients(characteristic);
    }

    private void updatePressureValue(BluetoothGattCharacteristic characteristic) {
        if (mPressureCals == null) return;
        double pressure = SensorTagData.extractBarometer(characteristic, mPressureCals);
        double temp = SensorTagData.extractBarTemperature(characteristic, mPressureCals);

        mTemperature.setText(String.format("%.1f\u00B0C", temp));
        mPressure.setText(String.format("%.2f", pressure));
    }*/
    
    /*public void save(View view){
        tempData = mTemperature.getText().toString();
        humiData = mHumidity.getText().toString();
        presData = mPressure.getText().toString();
        try {
           FileOutputStream fOut = openFileOutput(filename,MODE_WORLD_READABLE);
           fOut.write(tempData.getBytes());
           fOut.write(humiData.getBytes());
           fOut.write(presData.getBytes());
           fOut.close();
           Toast.makeText(getBaseContext(),"file saved",
           Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
           // TODO Auto-generated catch block
           e.printStackTrace();
        }
     }
    
    public void read(View view){
        try{
           FileInputStream fin = openFileInput(filename);
           int c;
           String temp1="";
           String temp2="";
           String temp3="";
           while( (c = fin.read()) != -1){
              temp1 = temp1 + Character.toString((char)c);
              //temp2 = temp2 + Character.toString((char)c);
              //temp3 = temp3 + Character.toString((char)c);
           }
           mRead.setText(temp1);
           //mHumidity.setText(temp2);
           //mPressure.setText(temp3);
           Toast.makeText(getBaseContext(),"file read",
           Toast.LENGTH_SHORT).show();

        }catch(Exception e){

        }
        }*/

}
