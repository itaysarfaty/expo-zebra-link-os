package expo.modules.zebralinkos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.zebra.sdk.comm.BluetoothConnection
import com.zebra.sdk.comm.Connection
import com.zebra.sdk.comm.ConnectionException
import com.zebra.sdk.printer.ZebraPrinter
import com.zebra.sdk.printer.ZebraPrinterFactory
import com.zebra.sdk.printer.PrinterStatus
import com.zebra.sdk.printer.discovery.BluetoothDiscoverer
import com.zebra.sdk.printer.discovery.DiscoveredPrinter
import com.zebra.sdk.printer.discovery.DiscoveredPrinterBluetooth
import com.zebra.sdk.printer.discovery.DiscoveryHandler
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.interfaces.permissions.PermissionsResponseListener
import expo.modules.interfaces.permissions.PermissionsStatus
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

private class BluetoothConnectionPool {
    private data class PooledConnection(
        val macAddress: String,
        val lock: ReentrantLock,
        @Volatile var connection: BluetoothConnection?,
        @Volatile var lastUsedAtMs: Long
    )

    private val connectionOpenRetries: Int = 2
    private val connectionOpenRetryDelayMs: Long = 150L
    private val connectionIdleTimeoutMs: Long = 30_000L

    private val connections = ConcurrentHashMap<String, PooledConnection>()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    fun <T> withConnection(macAddress: String, task: (Connection) -> T): T {
        val pooled = connections.computeIfAbsent(macAddress) {
            PooledConnection(
                macAddress = macAddress,
                lock = ReentrantLock(),
                connection = null,
                lastUsedAtMs = System.currentTimeMillis()
            )
        }

        pooled.lock.lock()
        try {
            var conn = pooled.connection
            if (conn == null || !isConnectionAlive(conn)) {
                safeClose(conn)
                conn = BluetoothConnection(macAddress)
                openWithRetry(conn)
                pooled.connection = conn
            }

            pooled.lastUsedAtMs = System.currentTimeMillis()
            val result = task(conn)
            pooled.lastUsedAtMs = System.currentTimeMillis()
            scheduleIdleClose(pooled)
            return result
        } catch (e: Exception) {
            safeClose(pooled.connection)
            pooled.connection = null
            connections.remove(macAddress)
            throw e
        } finally {
            pooled.lock.unlock()
        }
    }

    private fun isConnectionAlive(connection: BluetoothConnection): Boolean {
        return try {
            connection.isConnected
        } catch (_: Exception) {
            false
        }
    }

    private fun openWithRetry(connection: BluetoothConnection) {
        var last: Exception? = null
        repeat(connectionOpenRetries) { attempt ->
            try {
                connection.open()
                return
            } catch (e: Exception) {
                last = e
                safeClose(connection)
                if (attempt < connectionOpenRetries - 1) {
                    try { Thread.sleep(connectionOpenRetryDelayMs) } catch (_: InterruptedException) {}
                }
            }
        }
        throw last ?: ConnectionException("Failed to open connection")
    }

    private fun scheduleIdleClose(pooled: PooledConnection) {
        val mac = pooled.macAddress
        scheduler.schedule({
            val current = connections[mac] ?: return@schedule
            if (!current.lock.tryLock()) {
                scheduleIdleClose(current)
                return@schedule
            }
            try {
                val idleMs = System.currentTimeMillis() - current.lastUsedAtMs
                if (idleMs >= connectionIdleTimeoutMs) {
                    safeClose(current.connection)
                    current.connection = null
                    connections.remove(mac)
                } else {
                    scheduleIdleClose(current)
                }
            } finally {
                current.lock.unlock()
            }
        }, connectionIdleTimeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun safeClose(connection: Connection?) {
        try {
            connection?.close()
        } catch (_: Exception) {}
    }
}

class ExpoZebraLinkOSModule : Module() {

    companion object {
        // Error codes
        private const val ERROR_NO_CONTEXT = "NO_CONTEXT"
        private const val ERROR_NO_PERMISSIONS_MANAGER = "NO_PERMISSIONS_MANAGER"
        private const val ERROR_BLUETOOTH_DISABLED = "BLUETOOTH_DISABLED"
        private const val ERROR_BLUETOOTH_PERMISSION_DENIED = "BLUETOOTH_PERMISSION_DENIED"
        private const val ERROR_NO_ACTIVE_DISCOVERY = "NO_ACTIVE_DISCOVERY"
        private const val ERROR_DISCOVERY_ALREADY_FINISHED = "DISCOVERY_ALREADY_FINISHED"
        private const val ERROR_DISCOVERY_ERROR = "DISCOVERY_ERROR"
        private const val ERROR_CONNECTION_EXCEPTION = "CONNECTION_EXCEPTION"
        
        private const val ERROR_PRINT_ERROR = "PRINT_ERROR"
        private const val ERROR_PAIRING_FAILED = "PAIRING_FAILED"
        private const val ERROR_UNPAIRING_FAILED = "UNPAIRING_FAILED"
        private const val ERROR_OPERATION_TIMEOUT = "OPERATION_TIMEOUT"
        private const val ERROR_STATUS_ERROR = "STATUS_ERROR"

        // Error messages
        private const val MSG_NO_CONTEXT = "React context is not available"
        private const val MSG_NO_PERMISSIONS_MANAGER = "Permissions manager is not available"
        private const val MSG_BLUETOOTH_DISABLED =
            "Bluetooth is not enabled. Please enable Bluetooth in device settings."
        private const val MSG_BLUETOOTH_PERMISSION_DENIED =
            "Bluetooth permissions were denied. Please enable Bluetooth permissions in your device settings."
        private const val MSG_BLUETOOTH_PERMISSION_REQUIRED =
            "Bluetooth permissions are required but were not granted"
        private const val MSG_NO_ACTIVE_DISCOVERY = "There is no active discovery to cancel"
        private const val MSG_DISCOVERY_ALREADY_FINISHED = "The discovery has already finished"
        private const val MSG_CONNECTION_FAILED = "Failed to establish connection to printer"
        private const val MSG_PAIRING_FAILED = "Failed to pair with device"
        private const val MSG_UNPAIRING_FAILED = "Failed to unpair device"
        private const val MSG_OPERATION_TIMEOUT = "Operation timed out"

        // Other constants
        private const val DISCOVERY_TIMEOUT_MS = 60_000L
        private const val PAIRING_TIMEOUT_MS = 60_000L
    }

    // Represents a single active discovery session, only one supported at a time
    private data class DiscoverySession(
        val handler: Handler,
        val timeoutRunnable: Runnable,
        val discoveryHandler: DiscoveryHandler,
        val promise: Promise,
        val foundPrinters: MutableList<Map<String, Any>>,
        var finished: Boolean
    )

    private var currentDiscovery: DiscoverySession? = null

    // Simple per-device Bluetooth connection pool with serialized access per MAC address
    private val connectionPool = BluetoothConnectionPool()

    // Response builders
    private fun successResponse(data: Any? = null): Map<String, Any> {
        return if (data != null) {
            mapOf("success" to true, "data" to data)
        } else {
            mapOf("success" to true)
        }
    }

    private fun errorResponse(code: String, message: String): Map<String, Any> {
        return mapOf(
            "success" to false,
            "error" to mapOf("code" to code, "message" to message)
        )
    }

    // Bluetooth utilities
    private fun isBluetoothEnabled(context: Context): Boolean {
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return bluetoothManager.adapter.isEnabled
    }

    private fun hasBluetoothPermissions(context: Context): Boolean {
        val bluetoothPerms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermissions(
                context,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            hasPermissions(
                context,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }

        val locationPerm = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        return bluetoothPerms && locationPerm
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasPermissions(context: Context, vararg permissions: String): Boolean {
        return permissions.all { hasPermission(context, it) }
    }

    private fun getRequiredBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    // Permission handling
    private fun requestPermissionsAndExecute(promise: Promise, onPermissionsGranted: () -> Unit) {
        val permissionsManager = appContext.permissions
        if (permissionsManager == null) {
            promise.resolve(errorResponse(ERROR_NO_PERMISSIONS_MANAGER, MSG_NO_PERMISSIONS_MANAGER))
            return
        }

        val permissions = getRequiredBluetoothPermissions()

        val listener = PermissionsResponseListener { permissionsResponse ->
            val allGranted = permissions.all { permission ->
                permissionsResponse[permission]?.status == PermissionsStatus.GRANTED
            }

            if (allGranted) {
                onPermissionsGranted()
            } else {
                promise.resolve(
                    errorResponse(
                        ERROR_BLUETOOTH_PERMISSION_DENIED,
                        MSG_BLUETOOTH_PERMISSION_DENIED
                    )
                )
            }
        }

        permissionsManager.askForPermissions(listener, *permissions)
    }

    // Validation and setup
    private fun validateContextAndBluetooth(promise: Promise): Context? {
        val reactContext = appContext.reactContext
        if (reactContext == null) {
            promise.resolve(errorResponse(ERROR_NO_CONTEXT, MSG_NO_CONTEXT))
            return null
        }

        if (!isBluetoothEnabled(reactContext)) {
            promise.resolve(errorResponse(ERROR_BLUETOOTH_DISABLED, MSG_BLUETOOTH_DISABLED))
            return null
        }

        return reactContext
    }

    private fun executeWithPermissions(
        reactContext: Context,
        promise: Promise,
        operation: () -> Unit
    ) {
        if (!hasBluetoothPermissions(reactContext)) {
            requestPermissionsAndExecute(promise, operation)
        } else {
            operation()
        }
    }

    override fun definition() = ModuleDefinition {
        Name("ExpoZebraLinkOS")
        Events("onPrinterFound")

        AsyncFunction("discoverBluetoothPrinters") { timeoutMs: Double?, promise: Promise ->
            val reactContext = validateContextAndBluetooth(promise) ?: return@AsyncFunction
            executeWithPermissions(reactContext, promise) {
                val effectiveTimeoutMs = timeoutMs?.toLong() ?: DISCOVERY_TIMEOUT_MS
                performDiscovery(reactContext, effectiveTimeoutMs, promise)
            }
        }

        AsyncFunction("cancelDiscovery") { promise: Promise ->
            val session = currentDiscovery
            when {
                session == null -> {
                    promise.resolve(
                        errorResponse(
                            ERROR_NO_ACTIVE_DISCOVERY,
                            MSG_NO_ACTIVE_DISCOVERY
                        )
                    )
                }

                session.finished -> {
                    promise.resolve(
                        errorResponse(
                            ERROR_DISCOVERY_ALREADY_FINISHED,
                            MSG_DISCOVERY_ALREADY_FINISHED
                        )
                    )
                }

                else -> {
                    cancelDiscoverySession(session)
                    promise.resolve(successResponse())
                }
            }
        }

        AsyncFunction("printZPLViaBluetooth") { macAddress: String, zpl: String, promise: Promise ->
            val reactContext = validateContextAndBluetooth(promise) ?: return@AsyncFunction
            executeWithPermissions(reactContext, promise) {
                performPrint(macAddress, zpl, promise)
            }
        }

        AsyncFunction("pairBluetoothPrinter") { macAddress: String, promise: Promise ->
            val reactContext = validateContextAndBluetooth(promise) ?: return@AsyncFunction
            executeWithPermissions(reactContext, promise) {
                performPair(macAddress, reactContext, promise)
            }
        }

        AsyncFunction("unPairBluetoothPrinter") { macAddress: String, promise: Promise ->
            val reactContext = validateContextAndBluetooth(promise) ?: return@AsyncFunction
            executeWithPermissions(reactContext, promise) {
                performUnpair(macAddress, reactContext, promise)
            }
        }

        AsyncFunction("getPairedBluetoothDevices") { promise: Promise ->
            val reactContext = validateContextAndBluetooth(promise) ?: return@AsyncFunction
            executeWithPermissions(reactContext, promise) {
                val bluetoothManager = reactContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = bluetoothManager.adapter
                val result = mutableListOf<Map<String, Any>>()

                try {
                    val devices = adapter?.bondedDevices ?: emptySet()
                    for (device in devices) {
                        try {
                            val info = mutableMapOf<String, Any>()
                            info["address"] = device.address
                            val name = device.name
                            if (!name.isNullOrEmpty()) {
                                info["friendlyName"] = name
                            }
                            info["paired"] = true
                            result.add(info)
                        } catch (_: SecurityException) {
                            // Ignore devices we cannot query
                        }
                    }
                    promise.resolve(successResponse(result))
                } catch (_: SecurityException) {
                    promise.resolve(
                        errorResponse(
                            ERROR_BLUETOOTH_PERMISSION_DENIED,
                            MSG_BLUETOOTH_PERMISSION_REQUIRED
                        )
                    )
                }
            }
        }

        AsyncFunction("getBluetoothPrinterStatus") { macAddress: String, promise: Promise ->
            val reactContext = validateContextAndBluetooth(promise) ?: return@AsyncFunction
            executeWithPermissions(reactContext, promise) {
                performGetPrinterStatus(macAddress, promise)
            }
        }
    }

    private fun cancelDiscoverySession(session: DiscoverySession) {
        session.finished = true
        session.handler.removeCallbacks(session.timeoutRunnable)
        session.discoveryHandler.discoveryFinished()
        currentDiscovery = null
    }

    private fun performGetPrinterStatus(macAddress: String, promise: Promise) {
        Thread {
            try {
                val data = connectionPool.withConnection(macAddress) { connection ->
                    if (!connection.isConnected) {
                        throw ConnectionException(MSG_CONNECTION_FAILED)
                    }
                    val printer: ZebraPrinter = ZebraPrinterFactory.getInstance(connection)
                    val status: PrinterStatus = printer.currentStatus
                     status
                }
                promise.resolve(successResponse(data))
            } catch (e: Exception) {
                val response = when (e) {
                    is ConnectionException -> errorResponse(
                        ERROR_CONNECTION_EXCEPTION,
                        e.message ?: "Connection exception while getting status"
                    )
                    is SecurityException -> errorResponse(
                        ERROR_BLUETOOTH_PERMISSION_DENIED,
                        MSG_BLUETOOTH_PERMISSION_REQUIRED
                    )
                    else -> errorResponse(
                        ERROR_STATUS_ERROR,
                        e.message ?: "Unknown error while getting status"
                    )
                }
                promise.resolve(response)
            }
        }.start()
    }

    private fun performDiscovery(reactContext: Context, discoveryTimeoutMs: Long, promise: Promise) {
        val foundPrinters = mutableListOf<Map<String, Any>>()
        var finished = false
        val pairedAddresses = getPairedDeviceAddresses(reactContext)

        val handler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (!finished) {
                currentDiscovery?.discoveryHandler?.discoveryFinished()
            }
        }

        handler.postDelayed(timeoutRunnable, discoveryTimeoutMs)

        val discoveryHandler = createDiscoveryHandler(
            foundPrinters,
            handler,
            timeoutRunnable,
            promise,
            { finished },
            { finished = true },
            pairedAddresses
        )

        currentDiscovery = DiscoverySession(
            handler = handler,
            timeoutRunnable = timeoutRunnable,
            discoveryHandler = discoveryHandler,
            promise = promise,
            foundPrinters = foundPrinters,
            finished = finished
        )

        executeDiscovery(reactContext, discoveryHandler, promise, handler, timeoutRunnable) {
            finished = true
        }
    }

    private fun createDiscoveryHandler(
        foundPrinters: MutableList<Map<String, Any>>,
        handler: Handler,
        timeoutRunnable: Runnable,
        promise: Promise,
        isFinished: () -> Boolean,
        setFinished: () -> Unit,
        pairedAddresses: Set<String>
    ): DiscoveryHandler {
        return object : DiscoveryHandler {
            override fun foundPrinter(printer: DiscoveredPrinter?) {
                printer?.let {
                    val printerInfo = createPrinterInfo(it, pairedAddresses)
                    foundPrinters.add(printerInfo)
                    handler.post {
                        this@ExpoZebraLinkOSModule.sendEvent("onPrinterFound", printerInfo)
                    }
                }
            }

            override fun discoveryFinished() {
                if (isFinished()) return
                setFinished()
                handler.removeCallbacks(timeoutRunnable)
                currentDiscovery = null
                promise.resolve(successResponse(foundPrinters))
            }

            override fun discoveryError(message: String?) {
                Log.e("ZebraDiscovery", "Discovery error: $message")
                if (isFinished()) return
                setFinished()
                handler.removeCallbacks(timeoutRunnable)
                currentDiscovery = null
                promise.resolve(
                    errorResponse(
                        ERROR_DISCOVERY_ERROR,
                        message ?: "Unknown discovery error"
                    )
                )
            }
        }
    }

    private fun createPrinterInfo(printer: DiscoveredPrinter, pairedAddresses: Set<String>): Map<String, Any> {
        val info = mutableMapOf<String, Any>()
        info["address"] = printer.address
        (printer as? DiscoveredPrinterBluetooth)?.friendlyName?.let { name ->
            if (name.isNotEmpty()) info["friendlyName"] = name
        }
        info["paired"] = pairedAddresses.contains(printer.address.uppercase())
        return info
    }

    private fun executeDiscovery(
        reactContext: Context,
        discoveryHandler: DiscoveryHandler,
        promise: Promise,
        handler: Handler,
        timeoutRunnable: Runnable,
        setFinished: () -> Unit
    ) {
        Thread {
            try {
                BluetoothDiscoverer.findPrinters(reactContext, discoveryHandler)
            } catch (e: Exception) {
                handleDiscoveryException(e, promise, handler, timeoutRunnable, setFinished)
            }
        }.start()
    }

    private fun handleDiscoveryException(
        e: Exception,
        promise: Promise,
        handler: Handler,
        timeoutRunnable: Runnable,
        setFinished: () -> Unit
    ) {
        setFinished()
        handler.removeCallbacks(timeoutRunnable)

        val response = when (e) {
            is ConnectionException -> errorResponse(
                ERROR_CONNECTION_EXCEPTION,
                e.message ?: "Connection exception during discovery"
            )

            is SecurityException -> errorResponse(
                ERROR_BLUETOOTH_PERMISSION_DENIED,
                MSG_BLUETOOTH_PERMISSION_REQUIRED
            )

            else -> errorResponse(
                ERROR_DISCOVERY_ERROR,
                e.message ?: "Unknown error during discovery"
            )
        }

        handler.post { promise.resolve(response) }
    }

    private fun performPrint(macAddress: String, zpl: String, promise: Promise) {
        Thread {
            try {
                connectionPool.withConnection(macAddress) { connection ->
                    if (!connection.isConnected) {
                        throw ConnectionException(MSG_CONNECTION_FAILED)
                    }
                    connection.write(zpl.toByteArray())
                }
                promise.resolve(successResponse())
            } catch (e: Exception) {
                promise.resolve(createPrintErrorResponse(e))
            }
        }.start()
    }

    private fun createPrintErrorResponse(e: Exception): Map<String, Any> {
        return when (e) {
            is ConnectionException -> errorResponse(
                ERROR_CONNECTION_EXCEPTION,
                e.message ?: "Connection exception while printing"
            )

            is SecurityException -> errorResponse(
                ERROR_BLUETOOTH_PERMISSION_DENIED,
                MSG_BLUETOOTH_PERMISSION_REQUIRED
            )

            else -> errorResponse(
                ERROR_PRINT_ERROR,
                e.message ?: "Unknown error while printing"
            )
        }
    }

    private fun getPairedDeviceAddresses(context: Context): Set<String> {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        return try {
            val devices = adapter?.bondedDevices ?: emptySet()
            devices.map { it.address.uppercase() }.toSet()
        } catch (_: SecurityException) {
            emptySet()
        }
    }

    private fun performPair(macAddress: String, context: Context, promise: Promise) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        val device = try {
            adapter.getRemoteDevice(macAddress)
        } catch (e: IllegalArgumentException) {
            promise.resolve(errorResponse(ERROR_PAIRING_FAILED, MSG_PAIRING_FAILED))
            return
        }

        try {
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                promise.resolve(successResponse())
                return
            }
        } catch (_: SecurityException) {
            promise.resolve(errorResponse(ERROR_BLUETOOTH_PERMISSION_DENIED, MSG_BLUETOOTH_PERMISSION_REQUIRED))
            return
        }

        val handler = Handler(Looper.getMainLooper())
        var finished = false

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (dev?.address?.equals(device.address, ignoreCase = true) != true) return

                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                if (finished) return
                when (bondState) {
                    BluetoothDevice.BOND_BONDED -> {
                        finished = true
                        try {
                            context.unregisterReceiver(this)
                        } catch (_: IllegalArgumentException) {}
                        handler.removeCallbacksAndMessages(null)
                        promise.resolve(successResponse())
                    }
                    BluetoothDevice.BOND_NONE -> {
                        // Pairing failed or was canceled
                        finished = true
                        try {
                            context.unregisterReceiver(this)
                        } catch (_: IllegalArgumentException) {}
                        handler.removeCallbacksAndMessages(null)
                        promise.resolve(errorResponse(ERROR_PAIRING_FAILED, MSG_PAIRING_FAILED))
                    }
                }
            }
        }

        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))

        val timeout = Runnable {
            if (finished) return@Runnable
            finished = true
            try {
                context.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {}
            promise.resolve(errorResponse(ERROR_OPERATION_TIMEOUT, MSG_OPERATION_TIMEOUT))
        }
        handler.postDelayed(timeout, PAIRING_TIMEOUT_MS)

        try {
            val started = device.createBond()
            if (!started) {
                if (!finished) {
                    finished = true
                    try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
                    handler.removeCallbacksAndMessages(null)
                    promise.resolve(errorResponse(ERROR_PAIRING_FAILED, MSG_PAIRING_FAILED))
                }
            }
        } catch (e: SecurityException) {
            if (!finished) {
                finished = true
                try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
                handler.removeCallbacksAndMessages(null)
                promise.resolve(errorResponse(ERROR_BLUETOOTH_PERMISSION_DENIED, MSG_BLUETOOTH_PERMISSION_REQUIRED))
            }
        }
    }

    private fun performUnpair(macAddress: String, context: Context, promise: Promise) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        val device = try {
            adapter.getRemoteDevice(macAddress)
        } catch (e: IllegalArgumentException) {
            promise.resolve(errorResponse(ERROR_UNPAIRING_FAILED, MSG_UNPAIRING_FAILED))
            return
        }

        try {
            if (device.bondState == BluetoothDevice.BOND_NONE) {
                promise.resolve(successResponse())
                return
            }
        } catch (_: SecurityException) {
            promise.resolve(errorResponse(ERROR_BLUETOOTH_PERMISSION_DENIED, MSG_BLUETOOTH_PERMISSION_REQUIRED))
            return
        }

        val handler = Handler(Looper.getMainLooper())
        var finished = false

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (dev?.address?.equals(device.address, ignoreCase = true) != true) return

                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                if (finished) return
                when (bondState) {
                    BluetoothDevice.BOND_NONE -> {
                        finished = true
                        try { context.unregisterReceiver(this) } catch (_: IllegalArgumentException) {}
                        handler.removeCallbacksAndMessages(null)
                        promise.resolve(successResponse())
                    }
                    BluetoothDevice.BOND_BONDED -> {
                        // Still bonded after an unpair attempt => failure case
                        // Ignore here; we'll rely on timeout if nothing changes
                    }
                }
            }
        }

        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))

        val timeout = Runnable {
            if (finished) return@Runnable
            finished = true
            try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
            promise.resolve(errorResponse(ERROR_OPERATION_TIMEOUT, MSG_OPERATION_TIMEOUT))
        }
        handler.postDelayed(timeout, PAIRING_TIMEOUT_MS)

        try {
            val method = device.javaClass.getMethod("removeBond")
            val result = method.invoke(device) as? Boolean ?: false
            if (!result) {
                // If immediate failure, clean up and fail. A broadcast may still arrive but we guard with 'finished'.
                if (!finished) {
                    finished = true
                    try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
                    handler.removeCallbacksAndMessages(null)
                    promise.resolve(errorResponse(ERROR_UNPAIRING_FAILED, MSG_UNPAIRING_FAILED))
                }
            }
        } catch (e: Exception) {
            if (!finished) {
                finished = true
                try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
                handler.removeCallbacksAndMessages(null)
                val response = if (e is SecurityException) {
                    errorResponse(ERROR_BLUETOOTH_PERMISSION_DENIED, MSG_BLUETOOTH_PERMISSION_REQUIRED)
                } else {
                    errorResponse(ERROR_UNPAIRING_FAILED, MSG_UNPAIRING_FAILED)
                }
                promise.resolve(response)
            }
        }
    }
}