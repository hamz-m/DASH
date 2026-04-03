package com.example.navi

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.Autocomplete
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import okhttp3.*
import org.json.JSONObject
import java.util.UUID

class MainActivity : ComponentActivity(), OnMapReadyCallback {
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var receiverRegistered = false

    private val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {

            if (BluetoothDevice.ACTION_FOUND == intent.action) {

                val device: BluetoothDevice? =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

                device?.let {
                    if (!discoveredDevices.contains(it)) {
                        discoveredDevices.add(it)
                        println("Found: ${it.name} - ${it.address}")
                    }
                }
            }
        }
    }

    private var nextTurnLocation: LatLng? = null
    private var nextCommand: String = ""
    private var selectedDestination: LatLng? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRequest: LocationRequest

    private lateinit var mapView: MapView
    private var bluetoothSocket: BluetoothSocket? = null

    // CONNECT TO ESP32
    fun connectToESP32(deviceAddress: String) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                2
            )
            return
        }

        Thread {
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                val device: BluetoothDevice = adapter.getRemoteDevice(deviceAddress)
                val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

                bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
                bluetoothSocket?.connect()

                runOnUiThread {
                    println("Bluetooth Connected")
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun sendCommand(command: String) {
        try {
            bluetoothSocket?.outputStream?.write((command + "\n").toByteArray())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //  Initialize Places
        Places.initialize(applicationContext, "AIzaSyCEN94PWkSXos5XvbWebty6H7eI_vs8y7s")

        //  Root layout
        val rootLayout = android.widget.FrameLayout(this)

        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        rootLayout.addView(mapView)

        //  Search button
        val searchButton = Button(this)
        searchButton.text = "Search"
        searchButton.setOnClickListener { openSearch() }

        val searchParams = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        searchParams.topMargin = 100
        searchParams.leftMargin = 50

        rootLayout.addView(searchButton, searchParams)

        //  Start button
        val startButton = Button(this)
        startButton.text = "Start"

        startButton.setOnClickListener {

            if (selectedDestination != null) {

                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null) {

                        val userLocation = LatLng(location.latitude, location.longitude)

                        mapView.getMapAsync { googleMap ->
                            getRoute(userLocation, selectedDestination!!, googleMap)
                        }
                    }
                }

            } else {
                println("No destination selected")
            }
        }

        val startParams = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        startParams.topMargin = 200
        startParams.leftMargin = 50

        rootLayout.addView(startButton, startParams)

        // SCAN DEVICES BUTTON
        val scanButton = Button(this)
        scanButton.text = "Scan Devices"

        scanButton.setOnClickListener {
            scanDevices()

            // wait 5 sec then show list
            android.os.Handler(mainLooper).postDelayed({
                showScannedDevices()
            }, 5000)
        }

        val scanParams = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        scanParams.topMargin = 300   // below Start button
        scanParams.leftMargin = 50

        rootLayout.addView(scanButton, scanParams)

        setContentView(rootLayout)

        startLocationUpdates()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 100 && resultCode == RESULT_OK) {
            val place = Autocomplete.getPlaceFromIntent(data!!)
            val latLng = place.latLng!!
            handleDestination(latLng)
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1
            )
            return
        }

        googleMap.isMyLocationEnabled = true

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val userLocation = LatLng(location.latitude, location.longitude)
                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(userLocation, 17f))
            }
        }

        googleMap.setOnMapClickListener { latLng ->
            handleDestination(latLng)
        }
    }

    fun handleDestination(latLng: LatLng) {

        selectedDestination = latLng 

        mapView.getMapAsync { googleMap ->

            googleMap.clear()

            googleMap.addMarker(
                com.google.android.gms.maps.model.MarkerOptions()
                    .position(latLng)
                    .title("Destination")
            )
        }
    }

    fun openSearch() {

        val fields = listOf(
            Place.Field.NAME,
            Place.Field.LAT_LNG
        )

        val intent = Autocomplete.IntentBuilder(
            AutocompleteActivityMode.FULLSCREEN,
            fields
        ).build(this)

        startActivityForResult(intent, 100)
    }
    fun scanDevices() {

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            println("Bluetooth OFF")
            return
        }

        // Permission check
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_SCAN),
                3
            )
            return
        }

        discoveredDevices.clear()

        val filter = android.content.IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(receiver, filter)
        receiverRegistered = true

        bluetoothAdapter.startDiscovery()

        println("Scanning started...")
    }
    fun connectToDevice(device: BluetoothDevice) {

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                2
            )
            return
        }

        Thread {
            try {
                val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

                bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
                bluetoothSocket?.connect()

                runOnUiThread {
                    println("✅ Connected to ${device.name}")
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun showScannedDevices() {

        if (discoveredDevices.isEmpty()) {
            println("No devices found")
            return
        }

        val deviceNames = discoveredDevices.map {
            (it.name ?: "Unknown") + "\n" + it.address
        }.toTypedArray()

        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Select Device")

        builder.setItems(deviceNames) { _, which ->
            val device = discoveredDevices[which]
            connectToDevice(device)
        }

        builder.show()
    }

    fun startLocationUpdates() {

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationRequest = LocationRequest.create().apply {
            interval = 3000
            fastestInterval = 2000
            priority = Priority.PRIORITY_HIGH_ACCURACY
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return

                if (nextTurnLocation != null) {

                    val results = FloatArray(1)

                    android.location.Location.distanceBetween(
                        location.latitude,
                        location.longitude,
                        nextTurnLocation!!.latitude,
                        nextTurnLocation!!.longitude,
                        results
                    )

                    val distance = results[0]

                    println("Distance: $distance")

                    if (distance <= 20) {
                        println("🔥 SENDING: $nextCommand")
                        sendCommand(nextCommand)
                        nextTurnLocation = null
                    }
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                mainLooper
            )
        }
    }

    fun getRoute(start: LatLng, end: LatLng, googleMap: GoogleMap) {

        val client = OkHttpClient()

        val url =
            "https://api.openrouteservice.org/v2/directions/driving-car?api_key=eyJvcmciOiI1YjNjZTM1OTc4NTExMTAwMDFjZjYyNDgiLCJpZCI6ImViYWM2MjNmNjEwYzQyMWRhYTlkOWRiNzQ2MjNjMTlmIiwiaCI6Im11cm11cjY0In0=&start=${start.longitude},${start.latitude}&end=${end.longitude},${end.latitude}"

        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: java.io.IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {

                val body = response.body?.string() ?: return

                try {
                    val json = JSONObject(body)

                    val steps = json
                        .getJSONArray("features")
                        .getJSONObject(0)
                        .getJSONObject("properties")
                        .getJSONArray("segments")
                        .getJSONObject(0)
                        .getJSONArray("steps")

                    val coordinates = json
                        .getJSONArray("features")
                        .getJSONObject(0)
                        .getJSONObject("geometry")
                        .getJSONArray("coordinates")

                    if (steps.length() > 0) {

                        val step = steps.getJSONObject(0)
                        val instruction = step.getString("instruction")

                        val wayPoints = step.getJSONArray("way_points")
                        val index = wayPoints.getInt(0)

                        val point = coordinates.getJSONArray(index)

                        nextTurnLocation = LatLng(
                            point.getDouble(1),
                            point.getDouble(0)
                        )

                        nextCommand = when {
                            instruction.contains("left", true) -> "LEFT"
                            instruction.contains("right", true) -> "RIGHT"
                            else -> "STRAIGHT"
                        }

                        println("Stored: $nextCommand")
                    }

                    val path = ArrayList<LatLng>()

                    for (i in 0 until coordinates.length()) {
                        val point = coordinates.getJSONArray(i)
                        path.add(LatLng(point.getDouble(1), point.getDouble(0)))
                    }

                    runOnUiThread {
                        googleMap.addPolyline(
                            com.google.android.gms.maps.model.PolylineOptions()
                                .addAll(path)
                                .width(12f)
                                .color(android.graphics.Color.BLUE)
                        )
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        })
    }

    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onDestroy() { super.onDestroy(); mapView.onDestroy() }
}