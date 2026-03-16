package com.tuannghiav.uwbassetmanagement.view

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tuannghiav.uwbassetmanagement.BuildConfig
import com.tuannghiav.uwbassetmanagement.R
import com.tuannghiav.uwbassetmanagement.databinding.ViewBinding
import com.tuannghiav.uwbassetmanagement.presenter.PresenterImpl
import com.tuannghiav.uwbassetmanagement.presenter.positioning.AccelerationData
import com.tuannghiav.uwbassetmanagement.presenter.positioning.LocationData
import com.tuannghiav.uwbassetmanagement.presenter.positioning.OrientationData
import com.tuannghiav.uwbassetmanagement.presenter.recording.InputData
import com.tuannghiav.uwbassetmanagement.utils.StringUtil

class ViewImpl : AppCompatActivity(), MainScreenContract.View,
    RecordingFixedPositionDialogListener {

    private lateinit var presenter: MainScreenContract.Presenter
    private lateinit var binding: ViewBinding

    private var recordingDetailsData: InputData? = null

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val BLUETOOTH_ENABLE_REQUEST_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ViewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        presenter = PresenterImpl(applicationContext, this)

        setOnClickListeners()
    }

    override fun onStart() {
        super.onStart()
        presenter.start()
    }

    override fun onPause() {
        super.onPause()
        presenter.stop()
        enableConnectButton(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        presenter.stop()
        enableConnectButton(true)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == BLUETOOTH_ENABLE_REQUEST_CODE) {
            // Check if Bluetooth is now enabled after user responded to the enable prompt
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter?.isEnabled == true) {
                // Bluetooth is now enabled, proceed with connection
                presenter.onBluetoothPermissionGranted()
            } else {
                showMessage("Bluetooth must be enabled to connect")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                // Permissions granted, proceed with Bluetooth check
                checkBluetoothAndLocationEnabled()
            } else {
                // Permissions denied
                val deniedPermissions = permissions.filterIndexed { index, _ ->
                    grantResults.getOrNull(index) != PackageManager.PERMISSION_GRANTED
                }
                showMessage("Bluetooth permissions required. Please grant: ${deniedPermissions.joinToString(", ")}")
                presenter.onBluetoothPermissionDenied()
            }
        }
    }

    private fun checkBluetoothAndLocationEnabled() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        // Check if Bluetooth is available
        if (bluetoothAdapter == null) {
            showMessage("Bluetooth is not supported on this device")
            presenter.onBluetoothPermissionDenied()
            return
        }

        // Check if Bluetooth is enabled
        if (!bluetoothAdapter.isEnabled) {
            // Request to enable Bluetooth
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, BLUETOOTH_ENABLE_REQUEST_CODE)
            return
        }

        // For Android 10/11 (API 29-30), also check if Location is enabled
        // BLE scanning may require Location service on these versions
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val isLocationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if (!isLocationEnabled) {
                // Show dialog to enable Location
                AlertDialog.Builder(this)
                    .setTitle("Location Required")
                    .setMessage("Location service is required for BLE scanning on Android 10/11. Please enable Location.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        showMessage("Location must be enabled for BLE scanning")
                        presenter.onBluetoothPermissionDenied()
                    }
                    .setCancelable(false)
                    .show()
                return
            }
        }

        // All checks passed, proceed with Bluetooth connection
        presenter.onBluetoothPermissionGranted()
    }

    private fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31+)
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-11 (API 29-30)
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            // Android 9 and below
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = getRequiredPermissions()
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = getRequiredPermissions()
        val permissionsToRequest = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest, PERMISSION_REQUEST_CODE)
        } else {
            // All permissions already granted
            checkBluetoothAndLocationEnabled()
        }
    }

    override fun onBluetoothPermissionGranted() {
        // This will be called from Presenter after permissions are confirmed
        presenter.onConnectClicked()
    }

    override fun onBluetoothPermissionDenied() {
        showMessage("Bluetooth permissions are required to connect")
    }

    override fun showRecordingOptionsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Do you want to record position data?")
            .setMessage("Data will be saved to the following device directory:\n\n" +
                    "/Android/data/${BuildConfig.APPLICATION_ID}/files/Documents/")
            .setPositiveButton("YES"){_, _ ->
                showRecordingDetailsDialog()
            }
            .setNegativeButton("NO"){_, _ ->
                presenter.onRegularDataTransferStart()
            }
            .create()
            .show()
    }

    override fun showRecordingDetailsDialog() {
        AlertDialog.Builder(this)
            .setTitle("What kind of recording would you like to start?")
            .setMessage("Do you want to record data at a fixed position or during a movement?")
            .setPositiveButton("Movement"){_, _ ->
                // By setting 'recordingDetailsData' to null, we signalize the presenter that a recording of a movement is about to start
                // since for a movement no more necessary information is needed.
                recordingDetailsData = null
                prepareViewForRecording()
            }
            .setNegativeButton("Fixed Position"){_, _ ->
                val recordingFixedPositionDialog = RecordingFixedPositionDialog()
                recordingFixedPositionDialog.show(supportFragmentManager, "View")
            }
            .create()
            .show()
    }

    override fun showRecordStopScreen() {
        binding.recordStartButton.visibility = View.GONE
        binding.recordStopButton.visibility = View.VISIBLE
    }

    override fun dismissRecordStopScreen() {
        binding.recordStopButton.visibility = View.GONE
        binding.startButton.visibility = View.VISIBLE
        binding.disconnectButton.visibility = View.VISIBLE
    }

    override fun onFileDataEntered(x: String, y: String, z: String, direction: String, timePeriod: Long) {
        /*start_button.visibility = View.GONE
        disconnect_button.visibility = View.GONE
        record_start_button.visibility = View.VISIBLE*/
        // By setting 'recordingDetailsData' to actual recording details data, we signalize the presenter that a recording of a fixed position is about to start.
        recordingDetailsData = InputData(x, y, z, direction, timePeriod)
        prepareViewForRecording()
    }

    override fun showUWBPosition(uwbLocationData: LocationData) {
        this.runOnUiThread {
            binding.uwbXPosition.text = "UWB X: ${StringUtil.inEuropeanNotation(uwbLocationData.xPos)} m"
            binding.uwbYPosition.text = "UWB Y: ${StringUtil.inEuropeanNotation(uwbLocationData.yPos)} m"
            binding.uwbZPosition.text = "UWB Z: ${StringUtil.inEuropeanNotation(uwbLocationData.zPos)} m"
        }
    }

    override fun showFilteredPosition(filteredLocationData: LocationData) {
        this.runOnUiThread {
            binding.filteredXPosition.text = "Filter X: ${StringUtil.inEuropeanNotation(filteredLocationData.xPos)} m"
            binding.filteredYPosition.text = "Filter Y: ${StringUtil.inEuropeanNotation(filteredLocationData.yPos)} m"
            binding.filteredZPosition.text = "Filter Z: ${StringUtil.inEuropeanNotation(filteredLocationData.zPos)} m"
        }
    }

    override fun showAcceleration(accData: AccelerationData) {
        this.runOnUiThread {
            binding.xAcc.text = "X Acc: ${StringUtil.inEuropeanNotation(accData.xAcc)}"
            binding.yAcc.text = "Y Acc: ${StringUtil.inEuropeanNotation(accData.yAcc)}"
            binding.zAcc.text = "Z Acc: ${StringUtil.inEuropeanNotation(accData.zAcc)}"
            if (accData.xAcc > 0) binding.xAcc.setTextColor(Color.GREEN) else if (accData.xAcc < 0) binding.xAcc.setTextColor(Color.RED) else binding.xAcc.setTextColor(Color.GRAY)
            if (accData.yAcc > 0) binding.yAcc.setTextColor(Color.GREEN) else if (accData.yAcc < 0) binding.yAcc.setTextColor(Color.RED) else binding.yAcc.setTextColor(Color.GRAY)
            if (accData.zAcc > 0) binding.zAcc.setTextColor(Color.GREEN) else if (accData.zAcc < 0) binding.zAcc.setTextColor(Color.RED) else binding.zAcc.setTextColor(Color.GRAY)
        }
    }

    override fun showOrientation(orientationData: OrientationData) {
        this.runOnUiThread {
            binding.yaw.text = "Yaw: ${StringUtil.inEuropeanNotation(orientationData.yaw)}"
            binding.pitch.text = "Pitch: ${StringUtil.inEuropeanNotation(orientationData.pitch)}"
            binding.roll.text = "Roll: ${StringUtil.inEuropeanNotation(orientationData.roll)}"
        }
    }

    override fun showCompassDirection(direction: String) {
        this.runOnUiThread {
            binding.compassDirection.text = "Direction: $direction"
        }
    }

    override fun enableConnectButton(enabled: Boolean) {
        this.runOnUiThread {
            if (enabled) {
                binding.connectButton.visibility = View.VISIBLE
                binding.startButton.visibility = View.GONE
                binding.disconnectButton.visibility = View.GONE
                binding.stopButton.visibility = View.GONE
            } else {
                binding.connectButton.visibility = View.GONE
            }
        }
    }

    override fun swapStartButton(start: Boolean) {
        this.runOnUiThread {
            if (start) {
                binding.stopButton.visibility = View.GONE
                binding.startButton.visibility = View.VISIBLE
                binding.disconnectButton.visibility = View.VISIBLE
            } else {
                binding.startButton.visibility = View.GONE
                binding.disconnectButton.visibility = View.GONE
                binding.stopButton.visibility = View.VISIBLE
            }
        }
    }

    override fun showMessage(message: String?) {
        this.runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun setPresenter(presenter: MainScreenContract.Presenter) {
        this.presenter = presenter
    }

    private fun prepareViewForRecording(){
        binding.startButton.visibility = View.GONE
        binding.disconnectButton.visibility = View.GONE
        binding.recordStartButton.visibility = View.VISIBLE
    }

    private fun setOnClickListeners() {
        binding.connectButton.setOnClickListener {
            // Check and request permissions before connecting
            if (hasRequiredPermissions()) {
                checkBluetoothAndLocationEnabled()
            } else {
                requestBluetoothPermissions()
            }
        }

        binding.startButton.setOnClickListener {
            presenter.onStartClicked()
        }

        binding.stopButton.setOnClickListener {
            presenter.onStopClicked()
        }

        binding.disconnectButton.setOnClickListener {
            presenter.onDisconnectClicked()
        }

        binding.recordStartButton.setOnClickListener {
            presenter.onRecordingDataTransferStart(recordingDetailsData)
        }

        binding.recordStopButton.setOnClickListener {
            presenter.onRecordStopClicked()
        }
    }
}
