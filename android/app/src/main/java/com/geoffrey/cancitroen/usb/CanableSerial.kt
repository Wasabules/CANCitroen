package com.geoffrey.cancitroen.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Wrapper du CANable v2 (firmware slcan) en USB-Serial CDC-ACM.
 *
 * Cycle de vie typique :
 *   1. tryOpen()         — tente de trouver et ouvrir le device
 *      → si pas de permission USB, déclenche la popup système et retourne
 *        [Result.NeedsPermission]. Le caller (CanService) écoute le broadcast
 *        ACTION_USB_PERMISSION et rappelle tryOpen() quand l'utilisateur
 *        accorde la permission.
 *   2. initBus()         — envoie C\r, S4\r (125k), O\r
 *   3. startReadLoop()   — boucle de lecture en arrière-plan
 *   4. send()            — émission
 *   5. close()           — propre
 */
class CanableSerial(private val context: Context) {

    companion object {
        private const val TAG = "CanableSerial"
        const val ACTION_USB_PERMISSION = "com.geoffrey.cancitroen.USB_PERMISSION"
        private const val VID = 0x16D0
        private const val PID = 0x117E
    }

    sealed class OpenResult {
        object Success : OpenResult()
        object NoDevice : OpenResult()
        object NeedsPermission : OpenResult()
        data class Error(val cause: Throwable) : OpenResult()
    }

    /** Écrit par open/close (IO, receivers), lu par la read loop et send(). */
    @Volatile private var port: UsbSerialPort? = null
    private val parser = SlcanParser()
    /**
     * UNLIMITED : on ne veut pas perdre une transition de bouton volant si le
     * decoder ralentit ponctuellement (ex: write Room sur disque chargé).
     * À 125 kbps le bus est de toute façon limité à ~1700 frames/s.
     */
    private val frameChannel = Channel<CanFrame>(Channel.UNLIMITED)
    private var readJob: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    val framesFlow: Flow<CanFrame> = frameChannel.receiveAsFlow()

    /** Timestamp ms du dernier frame parsé — pour watchdog "bus silencieux". */
    @Volatile private var lastFrameMs: Long = 0L

    /** Frames reçues depuis la dernière ouverture (le watchdog distingue ainsi
     *  « le CANable parle » de « on vient juste de le rouvrir »). */
    @Volatile private var framesSinceOpen: Long = 0L

    fun hasReceivedSinceOpen(): Boolean = framesSinceOpen > 0

    /** Millisecondes depuis le dernier frame reçu, ou Long.MAX_VALUE si jamais. */
    fun millisSinceLastFrame(): Long =
        if (lastFrameMs == 0L) Long.MAX_VALUE
        else System.currentTimeMillis() - lastFrameMs

    fun tryOpen(): OpenResult {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
            .firstOrNull { d ->
                d.device.vendorId == VID && d.device.productId == PID
            } ?: return OpenResult.NoDevice

        if (!manager.hasPermission(driver.device)) {
            requestPermission(manager, driver)
            _connectionState.value = ConnectionState.AWAITING_PERMISSION
            return OpenResult.NeedsPermission
        }

        // Un second broadcast ATTACHED / permission ne doit pas ouvrir une
        // deuxième connexion et faire fuiter la première.
        port?.let { old -> try { old.close() } catch (_: Exception) {}; port = null }

        return try {
            val connection = manager.openDevice(driver.device)
                ?: return OpenResult.Error(IllegalStateException("openDevice() null"))
            val p = driver.ports[0]
            p.open(connection)
            p.setParameters(3_000_000, 8, UsbSerialPort.STOPBITS_1,
                            UsbSerialPort.PARITY_NONE)
            port = p
            Log.i(TAG, "Port ouvert : ${driver.device.deviceName}")
            _connectionState.value = ConnectionState.OPEN
            OpenResult.Success
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.ERROR
            OpenResult.Error(e)
        }
    }

    suspend fun initBus() = withContext(Dispatchers.IO) {
        val p = port ?: error("port pas ouvert")
        // Reset au cas où
        p.write("\rC\r".toByteArray(), 200)
        delay(100)
        // Bitrate 125 kbit/s
        p.write("S4\r".toByteArray(), 200)
        delay(50)
        // Open channel
        p.write("O\r".toByteArray(), 200)
        delay(50)
        _connectionState.value = ConnectionState.STREAMING
        Log.i(TAG, "Bus armé à 125 kbit/s")
    }

    fun startReadLoop(parentScope: CoroutineScope) {
        readJob?.cancel()
        // Reset le watchdog : sinon un précédent timestamp ancien ferait
        // déclencher le reconnect immédiatement à l'ouverture.
        lastFrameMs = System.currentTimeMillis()
        framesSinceOpen = 0L
        // Reset le parser : sinon un buffer ou un flag corruptUntilNextCr
        // hérité de la session précédente fait perdre la première frame.
        parser.reset()
        val p = port ?: return
        readJob = parentScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(1024)
            while (isActive) {
                try {
                    val n = p.read(buffer, 100)
                    if (n > 0) {
                        parser.feed(buffer, n) { frame ->
                            lastFrameMs = frame.timestampMs
                            framesSinceOpen++
                            frameChannel.trySend(frame)
                        }
                    }
                } catch (e: Exception) {
                    // CANable arraché / firmware crash → on sort de la boucle
                    // et on passe en ERROR. Le watchdog du CanService observe
                    // ce state et déclenche une reconnexion avec backoff.
                    Log.e(TAG, "Read loop error — bailing out", e)
                    _connectionState.value = ConnectionState.ERROR
                    break
                }
            }
        }
    }

    fun send(frame: CanFrame): Boolean = send(frame.id, frame.data, frame.extended)

    fun send(id: Int, data: ByteArray, extended: Boolean = false): Boolean {
        val p = port ?: return false
        return try {
            val line = SlcanParser.encodeFrame(id, data, extended)
            p.write(line.toByteArray(), 200)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Send error", e)
            false
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        readJob?.cancel()
        readJob = null
        try { port?.write("C\r".toByteArray(), 100) } catch (_: Exception) {}
        try { port?.close() } catch (_: Exception) {}
        port = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun requestPermission(manager: UsbManager, driver: UsbSerialDriver) {
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
        manager.requestPermission(driver.device, pi)
    }
}

enum class ConnectionState {
    DISCONNECTED,         // pas branché ou close() appelé
    AWAITING_PERMISSION,  // attente popup utilisateur
    OPEN,                 // port USB ouvert mais bus pas encore initialisé
    STREAMING,            // bus 125k armé, lecture active
    ERROR,                // erreur récente
}
