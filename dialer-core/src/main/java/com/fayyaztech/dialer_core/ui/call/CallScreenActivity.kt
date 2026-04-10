package com.fayyaztech.dialer_core.ui.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.fayyaztech.dialer_core.services.DefaultInCallService
import com.fayyaztech.dialer_core.services.FlipToSilenceHelper
import com.fayyaztech.dialer_core.services.ImsStatusHelper
import com.fayyaztech.dialer_core.services.IncomingCallPreferences
import com.fayyaztech.dialer_core.services.RejectSmsHelper
import com.fayyaztech.dialer_core.services.ReminderHelper
import com.fayyaztech.dialer_core.services.RingtoneHelper
import com.fayyaztech.dialer_core.services.SimSelectionHelper
import com.fayyaztech.dialer_core.services.TtyHelper
import com.fayyaztech.dialer_core.ui.theme.DefaultDialerTheme
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.TextButton
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.width
import androidx.compose.animation.with
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Message
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.fayyaztech.dialer_core.services.CallNoteHelper
import com.fayyaztech.dialer_core.ui.contacts.ContactDetailActivity

@OptIn(ExperimentalAnimationApi::class)
class CallScreenActivity : ComponentActivity() {

    private var currentCall: Call? = null
    private var isEndingCall = false
    private val phoneNumberState = mutableStateOf("Unknown")
    private val callStateState = mutableStateOf("Unknown")
    private val canConferenceState = mutableStateOf(false)
    private val canMergeState = mutableStateOf(false)
    private val canSwapState = mutableStateOf(false)
    private val isOnHoldState = mutableStateOf(false)
    private val callCountState = mutableStateOf(1)
    private val audioState = mutableStateOf<CallAudioState?>(null)
    private val handler = Handler(Looper.getMainLooper())
    private var showKeypad by mutableStateOf(false)
    private val snackbarHostState = mutableStateOf(androidx.compose.material3.SnackbarHostState())
    private var isReceiverRegistered = false
    private val allCallsState = mutableStateOf<List<Call>>(emptyList())

    // Flip-to-silence helper (accelerometer-based)
    private val flipHelper by lazy { FlipToSilenceHelper(this) }
    // Whether the ringer has been silenced by flipping the phone
    private var ringerSilenced by mutableStateOf(false)
    // Whether to show the reject-with-SMS bottom sheet
    private var showRejectSmsSheet by mutableStateOf(false)
    // Whether to show the remind-later bottom sheet
    private var showReminderSheet by mutableStateOf(false)

    // TTY mode state — driven by TtyHelper / DefaultInCallService
    private val ttyModeState = mutableStateOf(TtyHelper.TTY_MODE_OFF)
    // VoLTE / VoWiFi badge label (null = plain GSM call)
    private val networkBadgeState = mutableStateOf<String?>(null)
    // Whether the current call is an emergency call
    private val isEmergencyCallState = mutableStateOf(false)
    // SIM label for the current call (e.g. "SIM 1 – Carrier")
    private val simLabelState = mutableStateOf<String?>(null)

    private val audioStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DefaultInCallService.ACTION_AUDIO_STATE_CHANGED) {
                val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(DefaultInCallService.EXTRA_AUDIO_STATE, CallAudioState::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(DefaultInCallService.EXTRA_AUDIO_STATE)
                }
                audioState.value = state
                Log.d("CallScreenActivity", "Received audio state: $state")
            }
        }
    }

    companion object {
        const val EXTRA_CAN_CONFERENCE = "CAN_CONFERENCE"
        const val EXTRA_CAN_MERGE = "CAN_MERGE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set up window flags to show over lock screen and turn screen on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        phoneNumberState.value = intent.getStringExtra("PHONE_NUMBER") ?: "Unknown"
        callStateState.value = intent.getStringExtra("CALL_STATE") ?: "Unknown"
        // Read the available actions from the launching intent
        canConferenceState.value = intent.getBooleanExtra(EXTRA_CAN_CONFERENCE, false)
        canMergeState.value = intent.getBooleanExtra(EXTRA_CAN_MERGE, false)

        Log.d(
                "CallScreenActivity",
                "Received Intent - Number: ${phoneNumberState.value}, State: ${callStateState.value}"
        )

        // Get the current call from the in-call service
        currentCall = DefaultInCallService.currentCall

        // Populate supplemental call metadata
        refreshCallMetadata()

        // Register callback to monitor call state
        currentCall?.registerCallback(callCallback)

        // Acquire proximity wake lock by default for earpiece mode (unless speaker is already on)
        // This ensures screen turns off when phone is near face during calls
        if (callStateState.value.contains("Active", ignoreCase = true) ||
                        callStateState.value.contains("Dialing", ignoreCase = true) ||
                        callStateState.value.contains("Incoming", ignoreCase = true) ||
                        callStateState.value.contains("Ringing", ignoreCase = true)
        ) {
            acquireProximityWakeLock()
        }

        // Register audio state receiver
        val filter = android.content.IntentFilter(DefaultInCallService.ACTION_AUDIO_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(audioStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(audioStateReceiver, filter)
        }
        isReceiverRegistered = true

        setContent {
            DefaultDialerTheme {
                Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                ) {
                    CallScreen(
                            phoneNumber = phoneNumberState.value,
                            initialCallState = callStateState.value,
                            call = currentCall,
                            canConference = canConferenceState.value,
                            canMerge = canMergeState.value,
                            canSwap = canSwapState.value,
                            callCount = callCountState.value,
                            onAnswerCall = { answerCall() },
                            onRejectCall = { rejectCall() },
                            onRejectWithSms = { smsText -> rejectCall(smsText) },
                            onRemindLater = { delayMs ->
                                rejectCall()
                                ReminderHelper.schedule(
                                    this@CallScreenActivity,
                                    phoneNumberState.value,
                                    getContactName(phoneNumberState.value) ?: phoneNumberState.value,
                                    delayMs
                                )
                            },
                            ringerSilenced = ringerSilenced,
                            onEndCall = { endCall() },
                            onToggleMute = { toggleMute() },
                            onSetAudioRoute = { route -> setAudioRoute(route) },
                            audioState = audioState.value,
                            isOnHold = isOnHoldState.value,
                            onToggleHold = { toggleHold() },
                            onConference = { onConference() },
                             onMerge = { onMerge() },
                             onSwapCalls = { onSwapCalls() },
                             onSwapToCall = { targetCall -> onSwapToCall(targetCall) },
                             allCalls = allCallsState.value,
                            onAddCall = { onAddCall() },
                             onSendDtmf = { digit -> sendDtmf(digit) },
                             showKeypad = showKeypad,
                             onToggleKeypad = { showKeypad = !showKeypad },
                             getContactName = { number -> getContactName(number) },
                             onViewContact = { number ->
                                 val contactId = getContactIdForNumber(number)
                                 if (contactId != null) {
                                     val intent = Intent(
                                         this@CallScreenActivity,
                                         ContactDetailActivity::class.java
                                     ).apply {
                                         putExtra(ContactDetailActivity.EXTRA_CONTACT_ID, contactId)
                                     }
                                     startActivity(intent)
                                 } else {
                                     showSnackbar("No contact found for $number")
                                 }
                             },
                             snackbarHostState = snackbarHostState.value,
                             ttyMode = ttyModeState.value,
                             onSetTtyMode = { mode -> setTtyMode(mode) },
                             networkBadge = networkBadgeState.value,
                             isEmergencyCall = isEmergencyCallState.value,
                             simLabel = simLabelState.value
                     )
                }
            }
        }
    }

    // AudioManager for mute / speaker control
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // Proximity wake lock to turn screen off when phone is near face (earpiece mode)
    private var proximityWakeLock: PowerManager.WakeLock? = null

    // Audio focus helpers (used to ensure speakerphone changes take effect reliably)
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus: Boolean = false
    private val afChangeListener =
            AudioManager.OnAudioFocusChangeListener { focusChange ->
                // We don't need fine-grained handling, just log for diagnostics
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN ->
                            Log.d("CallScreenActivity", "Audio focus gained")
                    AudioManager.AUDIOFOCUS_LOSS -> Log.d("CallScreenActivity", "Audio focus lost")
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                            Log.d("CallScreenActivity", "Audio focus lost transient")
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                            Log.d("CallScreenActivity", "Audio focus lost transient (duck)")
                    else -> Log.d("CallScreenActivity", "Audio focus changed: $focusChange")
                }
            }

    private fun requestAudioFocusIfNeeded() {
        if (hasAudioFocus) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val aa =
                        AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()

                val req =
                        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                                .setAudioAttributes(aa)
                                .setAcceptsDelayedFocusGain(false)
                                .setOnAudioFocusChangeListener(afChangeListener)
                                .build()

                val status = audioManager.requestAudioFocus(req)
                audioFocusRequest = req
                hasAudioFocus = status == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                // If transient focus was not granted, try requesting a non-transient (longer) focus
                // as a fallback
                if (!hasAudioFocus) {
                    Log.d(
                            "CallScreenActivity",
                            "Transient audio focus denied, trying AUDIOFOCUS_GAIN"
                    )
                    val req2 =
                            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                                    .setAudioAttributes(aa)
                                    .setAcceptsDelayedFocusGain(false)
                                    .setOnAudioFocusChangeListener(afChangeListener)
                                    .build()
                    val status2 = audioManager.requestAudioFocus(req2)
                    if (status2 == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        audioFocusRequest = req2
                        hasAudioFocus = true
                    } else {
                        Log.w("CallScreenActivity", "AUDIOFOCUS_GAIN also denied on fallback (O+)")
                    }
                }
            } else {
                val status =
                        audioManager.requestAudioFocus(
                                afChangeListener,
                                AudioManager.STREAM_VOICE_CALL,
                                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                        )
                hasAudioFocus = status == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

                // fallback: if transient focus denied, request persistent focus
                if (!hasAudioFocus) {
                    Log.d(
                            "CallScreenActivity",
                            "Transient audio focus denied (pre-O), trying AUDIOFOCUS_GAIN"
                    )
                    val status2 =
                            audioManager.requestAudioFocus(
                                    afChangeListener,
                                    AudioManager.STREAM_VOICE_CALL,
                                    AudioManager.AUDIOFOCUS_GAIN
                            )
                    hasAudioFocus = status2 == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    if (!hasAudioFocus) {
                        Log.w("CallScreenActivity", "AUDIOFOCUS_GAIN denied (pre-O fallback)")
                    }
                }
            }
            Log.d("CallScreenActivity", "requestAudioFocusIfNeeded - granted=$hasAudioFocus")
            if (!hasAudioFocus) {
                // Provide more diagnostics to help understand why focus was denied on some devices
                try {
                    Log.w(
                            "CallScreenActivity",
                            "Audio focus denied — diagnostics: mode=${audioManager.mode}, speaker=${audioManager.isSpeakerphoneOn}, btSco=${audioManager.isBluetoothScoOn}, btA2dp=${audioManager.isBluetoothA2dpOn}, musicActive=${audioManager.isMusicActive}, voiceVol=${audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}"
                    )
                } catch (e: Exception) {
                    Log.w("CallScreenActivity", "Failed to print audio diagnostics", e)
                }
            }
        } catch (e: Exception) {
            Log.w("CallScreenActivity", "requestAudioFocusIfNeeded failed", e)
        }
    }

    private fun abandonAudioFocusIfNeeded() {
        if (!hasAudioFocus) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                audioManager.abandonAudioFocus(afChangeListener)
            }
        } catch (e: Exception) {
            Log.w("CallScreenActivity", "abandonAudioFocusIfNeeded failed", e)
        } finally {
            hasAudioFocus = false
            Log.d("CallScreenActivity", "abandonAudioFocusIfNeeded - released")
        }
    }

    /**
     * Acquire proximity wake lock to turn screen off when phone is near face. This prevents
     * accidental touches when using earpiece.
     */
    private fun acquireProximityWakeLock() {
        try {
            if (proximityWakeLock?.isHeld == true) {
                Log.d("CallScreenActivity", "Proximity wake lock already held")
                return
            }

            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            proximityWakeLock =
                    powerManager.newWakeLock(
                            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                            "CallScreenActivity::ProximityWakeLock"
                    )
            // Acquire with 10 minute timeout (typical call duration)
            proximityWakeLock?.acquire(10 * 60 * 1000L)
            Log.d(
                    "CallScreenActivity",
                    "Proximity wake lock acquired - screen will turn off when near face"
            )
        } catch (e: Exception) {
            Log.w("CallScreenActivity", "Failed to acquire proximity wake lock", e)
        }
    }

    /**
     * Release proximity wake lock to allow screen to stay on. Used when speaker is enabled or call
     * ends.
     */
    private fun releaseProximityWakeLock() {
        try {
            if (proximityWakeLock?.isHeld == true) {
                proximityWakeLock?.release()
                proximityWakeLock = null
                Log.d("CallScreenActivity", "Proximity wake lock released - screen can stay on")
            }
        } catch (e: Exception) {
            Log.w("CallScreenActivity", "Failed to release proximity wake lock", e)
        }
    }

    /**
     * Set speakerphone on/off in a robust way:
     * - ensures audio focus
     * - selects an appropriate audio mode for communications
     */
    private fun setSpeakerphoneOn(enabled: Boolean) {
        try {
            requestAudioFocusIfNeeded()

            // prefer MODE_IN_COMMUNICATION for in-app/telecom routing to allow speakerphone
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // If we couldn't get audio focus, attempt a more aggressive fallback to ensure routing
            if (!hasAudioFocus) {
                Log.d("CallScreenActivity", "Audio focus not granted — running speaker fallback")

                // If a BT SCO connection is active, stop it first to allow switching to loudspeaker
                try {
                    if (audioManager.isBluetoothScoOn) {
                        Log.d(
                                "CallScreenActivity",
                                "Bluetooth SCO ON — stopping SCO to force speaker routing"
                        )
                        audioManager.stopBluetoothSco()
                        audioManager.setBluetoothScoOn(false)
                    }
                } catch (e: Exception) {
                    Log.w("CallScreenActivity", "Failed toggling Bluetooth SCO during fallback", e)
                }

                // Ensure speaker property is set, then try alternate modes if needed
                audioManager.isSpeakerphoneOn = enabled
                if (audioManager.isSpeakerphoneOn != enabled) {
                    // try fallback to MODE_IN_CALL which some vendors expect
                    Log.d(
                            "CallScreenActivity",
                            "Speakerstate did not take — trying MODE_IN_CALL fallback"
                    )
                    try {
                        audioManager.mode = AudioManager.MODE_IN_CALL
                    } catch (_: Exception) {}
                    audioManager.isSpeakerphoneOn = enabled
                }
            } else {
                // normal path
                audioManager.isSpeakerphoneOn = enabled
            }

            Log.d(
                    "CallScreenActivity",
                    "setSpeakerphoneOn -> $enabled (mode=${audioManager.mode}) speaker=${audioManager.isSpeakerphoneOn} btSco=${audioManager.isBluetoothScoOn}"
            )
        } catch (e: Exception) {
            Log.w("CallScreenActivity", "Failed to toggle speakerphone", e)
        }
    }

    private val callCallback =
            object : Call.Callback() {
                override fun onStateChanged(call: Call?, state: Int) {
                    when (state) {
                        Call.STATE_ACTIVE -> {
                            callStateState.value = "Active"
                            // Refresh phone number when the call becomes active
                            refreshPhoneNumberFromCallOrIntent()
                            // Acquire proximity wake lock for active call (earpiece mode)
                            acquireProximityWakeLock()
                            isOnHoldState.value = false
                            updateCallCount()
                        }
                        Call.STATE_RINGING -> {
                            // Pocket mode: also acquire proximity lock for ringing state
                            if (IncomingCallPreferences.isPocketModeEnabled(this@CallScreenActivity)) {
                                acquireProximityWakeLock()
                            }
                        }
                        Call.STATE_HOLDING -> {
                            isOnHoldState.value = true
                            updateCallCount()
                        }
                        Call.STATE_DISCONNECTING -> {
                            Log.d("CallScreenActivity", "Call is disconnecting...")
                            callStateState.value = "Disconnecting..."
                            updateCallCount()
                        }
                        Call.STATE_DISCONNECTED -> {
                            val disconnectCause = call?.details?.disconnectCause
                            Log.d(
                                    "CallScreenActivity",
                                    "Call disconnected: ${disconnectCause?.reason}"
                            )
                            callStateState.value = "Disconnected"
                            updateCallCount()

                            // Only call endCall if we're not already finishing
                            if (!isEndingCall && !isFinishing) {
                                handler.postDelayed({ endCall() }, 100)
                            }
                        }
                    }
                }
            }

    private fun answerCall() {
        try {
            currentCall?.answer(0)
            callStateState.value = "Connecting..."
            try {
                // ensure we have audio focus and prefer communication mode so speaker routing works
                // reliably
                requestAudioFocusIfNeeded()
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

                // Acquire proximity wake lock for earpiece mode (default)
                acquireProximityWakeLock()
            } catch (e: Exception) {
                Log.w("CallScreenActivity", "Unable to set audio mode to IN_CALL", e)
            }
        } catch (e: Exception) {
            Log.e("CallScreenActivity", "Failed to answer call", e)
        }
    }

    private fun rejectCall(smsText: String? = null) {
        if (isEndingCall || isFinishing) return

        flipHelper.unregister()
        RingtoneHelper.stopRinging()
        
        // If there are other calls, don't finish yet
        val otherCalls = DefaultInCallService.getAllCalls().filter { 
            it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING && it != currentCall
        }
        
        Log.d("CallScreenActivity", "Rejecting call. Other calls: ${otherCalls.size}")
        
        var rejected = false
        // Try to reject the call with optional SMS
        try {
            currentCall?.let {
                it.reject(smsText != null, smsText)
                Log.d("CallScreenActivity", "Call.reject(sms=$smsText) called")
                rejected = true
            }
        } catch (e: Exception) {
            Log.e("CallScreenActivity", "Failed to reject call", e)
        }

        // If reject failed, try disconnect as fallback
        if (!rejected) {
            try {
                currentCall?.disconnect()
                Log.d("CallScreenActivity", "Call.disconnect() called as fallback in rejectCall")
            } catch (e: Exception) {
                Log.e("CallScreenActivity", "Failed fallback disconnect in rejectCall", e)
            }
        }

        if (otherCalls.isEmpty()) {
            isEndingCall = true
            Log.d("CallScreenActivity", "Rejecting last call, finishing activity")
            handler.postDelayed({
                if (!isFinishing) finish()
            }, 200)
        } else {
            Log.d("CallScreenActivity", "Rejected call but ${otherCalls.size} calls remain. Staying.")
            updateCallCount()
        }
    }

    private fun endCall() {
        if (isEndingCall || isFinishing) return
        
        // Exclude the current call from others check to allow ending it even if it's the only one
        val otherCalls = DefaultInCallService.getAllCalls().filter { 
            it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING && it != currentCall
        }

        Log.d("CallScreenActivity", "endCall requested. Other calls: ${otherCalls.size}")

        // Try multiple methods to disconnect the call
        var disconnected = false

        // Method 1: Disconnect current call
        try {
            currentCall?.let {
                it.disconnect()
                Log.d("CallScreenActivity", "Call.disconnect() called on currentCall")
                disconnected = true
            }
        } catch (e: Exception) {
            Log.e("CallScreenActivity", "Failed to disconnect currentCall", e)
        }

        // Method 2: Disconnect all calls from service
        if (!disconnected) {
            try {
                val allCalls = DefaultInCallService.getAllCalls()
                allCalls.forEach { call ->
                    try {
                        call.disconnect()
                        Log.d("CallScreenActivity", "Call.disconnect() called on service call")
                    } catch (e: Exception) {
                        Log.e("CallScreenActivity", "Failed to disconnect service call", e)
                    }
                }
                if (allCalls.isNotEmpty()) {
                    disconnected = true
                }
            } catch (e: Exception) {
                Log.e("CallScreenActivity", "Failed to get calls from service", e)
            }
        }

        // Method 3: Use TelecomManager as last resort
        if (!disconnected) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val telecomManager =
                            getSystemService(Context.TELECOM_SERVICE) as?
                                    android.telecom.TelecomManager
                    telecomManager?.endCall()
                    Log.d("CallScreenActivity", "TelecomManager.endCall() called")
                    disconnected = true
                }
            } catch (e: Exception) {
                Log.e("CallScreenActivity", "Failed to end call via TelecomManager", e)
            }
        }

        if (otherCalls.isEmpty()) {
            isEndingCall = true
            Log.d("CallScreenActivity", "All calls ended, finishing activity")
            // Reset audio settings when ending call
            try {
                audioManager.mode = AudioManager.MODE_NORMAL
                @Suppress("DEPRECATION") audioManager.isSpeakerphoneOn = false
                audioManager.isMicrophoneMute = false
                abandonAudioFocusIfNeeded()
                releaseProximityWakeLock()
            } catch (e: Exception) {
                Log.w("CallScreenActivity", "Failed to reset audio settings on finish", e)
            }

            // Give the disconnect a moment to process before finishing
            handler.postDelayed(
                    {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            finishAndRemoveTask()
                        } else {
                            finish()
                        }
                    },
                    200
            )
        } else {
            Log.d("CallScreenActivity", "Call ended but ${otherCalls.size} calls remain. Staying open.")
            // If the current call ended, we might want to switch focus to another remaining call
            handler.postDelayed({ 
                val nextCall = DefaultInCallService.getAllCalls().firstOrNull { 
                    it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING 
                }
                if (nextCall != null && nextCall != currentCall) {
                    Log.d("CallScreenActivity", "Switching focus to next available call")
                    onSwapToCall(nextCall)
                } else {
                    updateCallCount()
                }
            }, 300)
        }
    }

    private fun toggleMute() {
        try {
            // Ask the InCallService to toggle microphone mute. AudioManager used from
            // InCallService is the reliable place to perform telecom audio routing and
            // microphone toggles.
            val newMuted = !audioManager.isMicrophoneMute
            DefaultInCallService.muteCall(newMuted)
            Log.d("CallScreenActivity", "Requested mute -> $newMuted")
        } catch (e: Exception) {
            Log.e("CallScreenActivity", "Mute failed", e)
        }
    }

    private fun toggleSpeaker() {
        try {
            @Suppress("DEPRECATION") val currentSpeakerState = audioManager.isSpeakerphoneOn
            val newState = !currentSpeakerState

            // Use InCallService's setSpeaker which will use setAudioRoute() properly
            DefaultInCallService.setSpeaker(newState)

            // Manage proximity wake lock based on speaker state
            if (newState) {
                // Speaker ON - release proximity lock to keep screen on
                releaseProximityWakeLock()
            } else {
                // Speaker OFF (earpiece) - acquire proximity lock to turn screen off near face
                acquireProximityWakeLock()
            }

            @Suppress("DEPRECATION")
            Log.d(
                    "CallScreenActivity",
                    "Requested speaker -> $newState (actual=${audioManager.isSpeakerphoneOn})"
            )
        } catch (e: Exception) {
            Log.e("CallScreenActivity", "Speaker toggle failed", e)
        }
    }

    private fun setAudioRoute(route: Int) {
        try {
            Log.d("CallScreenActivity", "Setting audio route to: $route")
            DefaultInCallService.setAudioRoute(route)
            
            // Manage proximity wake lock based on route
            if (route == CallAudioState.ROUTE_SPEAKER) {
                // Speaker ON - release proximity lock to keep screen on
                releaseProximityWakeLock()
            } else {
                // Earpiece/Bluetooth - acquire proximity lock to turn screen off near face
                acquireProximityWakeLock()
            }
        } catch (e: Exception) {
            Log.e("CallScreenActivity", "Failed to set audio route", e)
        }
    }

    private fun toggleHold() {
        try {
            if (isOnHoldState.value) {
                currentCall?.unhold()
                Log.d("CallScreenActivity", "Requested unhold")
            } else {
                currentCall?.hold()
                Log.d("CallScreenActivity", "Requested hold")
            }
        } catch (e: Exception) {
            Log.e("CallScreenActivity", "Hold toggle failed", e)
        }
    }

    private fun sendDtmf(digit: Char) {
        try {
            currentCall?.playDtmfTone(digit)
            handler.postDelayed({ currentCall?.stopDtmfTone() }, 100)
            Log.d("CallScreenActivity", "Sent DTMF: $digit")
        } catch (e: Exception) {
            Log.e("CallScreenActivity", "DTMF failed", e)
        }
    }

    private fun getContactName(phoneNumber: String): String? {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val uri =
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI
                        .buildUpon()
                        .appendPath(phoneNumber)
                        .build()

        var contactName: String? = null
        val cursor: Cursor? =
                contentResolver.query(
                        uri,
                        arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                        null,
                        null,
                        null
                )

        cursor?.use {
            if (it.moveToFirst()) {
                contactName = it.getString(0)
            }
        }

        return contactName
    }

    /** Update the call count and determine if merge option should be available */
    private fun updateCallCount() {
        handler.post {
            try {
                val calls = DefaultInCallService.getAllCalls()
                allCallsState.value = calls
                val totalCalls = calls.size
                callCountState.value = maxOf(totalCalls, 1)

                // Check if merge is actually available based on proper conditions
                canMergeState.value = canMergeCalls()
                
                // Check if swap is available
                canSwapState.value = canSwapCalls()

                Log.d(
                        "CallScreenActivity",
                        "Call updates - total: ${calls.size}, canMerge: ${canMergeState.value}, canSwap: ${canSwapState.value}"
                )
                // Refresh TTY/network/SIM/emergency info whenever call state changes
                refreshCallMetadata()
            } catch (e: Exception) {
                Log.w("CallScreenActivity", "Failed to get call count: ${e.message}")
                callCountState.value = 1
                canMergeState.value = false
                canSwapState.value = false
            }
        }
    }

    /**
     * Validates all conditions required for merging calls:
     * 1. There must be exactly 2 calls
     * 2. One call must be STATE_ACTIVE and the other STATE_HOLDING
     * 3. The active call must have CAPABILITY_MERGE_CONFERENCE
     */
    private fun canMergeCalls(): Boolean {
        try {
            val calls = DefaultInCallService.getAllCalls()
            
            // Condition 1: At least 2 calls
            if (calls.size < 2) {
                Log.d("CallScreenActivity", "canMergeCalls: Need at least 2 calls, have ${calls.size}")
                return false
            }
            
            // Condition 2: Find active and holding calls
            val activeCall = calls.find { it.state == Call.STATE_ACTIVE }
            val holdingCall = calls.find { it.state == Call.STATE_HOLDING }
            
            // Must have at least one active and one holding call to merge
            if (activeCall == null || holdingCall == null) {
                Log.d(
                    "CallScreenActivity",
                    "canMergeCalls: Missing required states - active: ${activeCall != null}, holding: ${holdingCall != null}"
                )
                return false
            }
            
            // Active call must have merge capability (some carriers might not show this, but we check anyway)
            val hasMergeCapability = activeCall.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE)
            Log.d(
                "CallScreenActivity",
                "canMergeCalls: Active call merge capability: $hasMergeCapability"
            )

            return hasMergeCapability
        } catch (e: Exception) {
            Log.w("CallScreenActivity", "canMergeCalls failed: ${e.message}", e)
            return false
        }
    }

    /**
     * Validates conditions for swapping calls:
     * 1. At least two calls exist
     * 2. At least one call is STATE_HOLDING
     */
    private fun canSwapCalls(): Boolean {
        try {
            val calls = DefaultInCallService.getAllCalls()
            
            // Condition 1: At least 2 calls
            if (calls.size < 2) {
                Log.d("CallScreenActivity", "canSwapCalls: Only ${calls.size} call(s), need at least 2")
                return false
            }
            
            // Condition 2: At least one holding call
            val hasHoldingCall = calls.any { it.state == Call.STATE_HOLDING }
            Log.d("CallScreenActivity", "canSwapCalls: Has holding call: $hasHoldingCall")
            
            return hasHoldingCall
        } catch (e: Exception) {
            Log.w("CallScreenActivity", "canSwapCalls failed: ${e.message}", e)
            return false
        }
    }


    /**
     * Refreshes TTY mode, VoLTE/VoWiFi badge, emergency flag, and SIM label.
     * Called from onCreate, onNewIntent, and updateCallCount.
     */
    private fun refreshCallMetadata() {
        handler.post {
            // TTY mode
            ttyModeState.value = DefaultInCallService.refreshTtyMode()
            // Network badge (VoLTE / VoWiFi / 5G)
            networkBadgeState.value = ImsStatusHelper.getNetworkBadgeLabel(this)
            // Emergency call detection
            isEmergencyCallState.value = DefaultInCallService.isEmergencyCall(currentCall)
            // SIM label from SimSelectionHelper
            simLabelState.value = resolveSimLabel()
            Log.d(
                "CallScreenActivity",
                "refreshCallMetadata: tty=${TtyHelper.modeName(ttyModeState.value)}, " +
                "badge=${networkBadgeState.value}, emergency=${isEmergencyCallState.value}, " +
                "sim=${simLabelState.value}"
            )
        }
    }

    /** Resolves a short SIM label (e.g. "SIM 1 – Carrier") for the current call. */
    private fun resolveSimLabel(): String? {
        val simId = DefaultInCallService.currentCallSimId ?: return null
        val sims = SimSelectionHelper.getAvailableSims(this)
        val match = sims.firstOrNull { it.phoneAccountHandle?.id == simId } ?: return null
        val slot = match.slotIndex + 1
        return "SIM $slot – ${match.displayName}"
    }

    /** Requests a TTY mode change and refreshes state. */
    private fun setTtyMode(mode: Int) {
        DefaultInCallService.setTtyMode(mode)
        ttyModeState.value = mode
    }

    /**
     * Ensures phoneNumberState is populated. Prefer the number from the active Call's handle
     * (schemeSpecificPart) and fall back to the launching intent extra if needed.
     */
    private fun refreshPhoneNumberFromCallOrIntent() {
        val numberFromCall = currentCall?.details?.handle?.schemeSpecificPart
        if (!numberFromCall.isNullOrBlank()) {
            if (phoneNumberState.value != numberFromCall) {
                phoneNumberState.value = numberFromCall
                Log.d("CallScreenActivity", "Using number from Call details: $numberFromCall")
            }
            return
        }

        // If call doesn't provide a number, fall back to intent extra if available
        val numberFromIntent = intent.getStringExtra("PHONE_NUMBER")
        if (!numberFromIntent.isNullOrBlank() && phoneNumberState.value != numberFromIntent) {
            phoneNumberState.value = numberFromIntent
            Log.d("CallScreenActivity", "Using number from Intent: $numberFromIntent")
        }
    }

    override fun onResume() {
        super.onResume()
        // Register flip-to-silence if enabled and call is ringing
        val isRinging = callStateState.value.contains("Incoming", ignoreCase = true) ||
                callStateState.value.contains("Ringing", ignoreCase = true)
        if (isRinging && IncomingCallPreferences.isFlipToSilenceEnabled(this)) {
            flipHelper.register(onSilenced = {
                ringerSilenced = true
                RingtoneHelper.stopRinging()
            })
        }
    }

    override fun onPause() {
        super.onPause()
        flipHelper.unregister()
    }

    override fun onDestroy() {
        Log.d("CallScreenActivity", "onDestroy - cleaning up resources")
        
        // ensure audio is returned to normal when the activity is destroyed
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION") audioManager.isSpeakerphoneOn = false
            audioManager.isMicrophoneMute = false
            // Ensure we release audio focus when activity is destroyed
            abandonAudioFocusIfNeeded()
            // Ensure we release proximity wake lock when activity is destroyed
            releaseProximityWakeLock()
        } catch (e: Exception) {
            Log.w("CallScreenActivity", "Failed to reset audio manager on destroy", e)
        }
        
        try {
            currentCall?.unregisterCallback(callCallback)
            if (isReceiverRegistered) {
                unregisterReceiver(audioStateReceiver)
                isReceiverRegistered = false
            }
        } catch (e: Exception) {
            Log.w("CallScreenActivity", "Failed to unregister callbacks", e)
        }
        
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Important: update the activity's intent

        // Handle new intent if activity is already running
        val newPhoneNumber = intent.getStringExtra("PHONE_NUMBER") ?: "Unknown"
        val newCallState = intent.getStringExtra("CALL_STATE") ?: "Unknown"
        val newCanConference = intent.getBooleanExtra(EXTRA_CAN_CONFERENCE, false)
        val newCanMerge = intent.getBooleanExtra(EXTRA_CAN_MERGE, false)

        Log.d("CallScreenActivity", "onNewIntent - Number: $newPhoneNumber, State: $newCallState")

        // Update states which will trigger recomposition (phone number is set from call/details
        // when available)
        callStateState.value = newCallState
        canConferenceState.value = newCanConference
        canMergeState.value = newCanMerge

        // Update the UI with new call information
        val previousCall = currentCall
        currentCall = DefaultInCallService.currentCall
        
        // CRITICAL: Immediately update the phone number and state variables for the new call
        refreshPhoneNumberFromCallOrIntent()
        callStateState.value = newCallState
        
        if (previousCall != currentCall) {
            previousCall?.unregisterCallback(callCallback)
            currentCall?.registerCallback(callCallback)
            Log.d("CallScreenActivity", "Switched currentCall from $previousCall to $currentCall")
        }

        updateCallCount()
        refreshCallMetadata()

        // Reset ending flag if we are getting a new valid call
        isEndingCall = false
        
        // Show snackbar if we are switching to a new incoming/active call
        if (newCallState.contains("Incoming", ignoreCase = true) || newCallState.contains("Dialing", ignoreCase = true)) {
            showSnackbar("Focusing on new call: $newPhoneNumber")
        }
    }

    // Conference action — delegates to merged onMerge() which performs the actual Telecom operation.
    private fun onConference() {
        Log.d(
                "CallScreenActivity",
                "Conference action requested — canConference=${canConferenceState.value}"
        )
        if (!canConferenceState.value) {
            showSnackbar("Conference not available for this call")
            return
        }
        // Real conference uses the same path as merge: Call.conference(holdingCall)
        onMerge()
    }

    private fun onMerge() {
        Log.d("CallScreenActivity", "Merge/conference requested")
        
        lifecycleScope.launch {
            try {
                val calls = DefaultInCallService.getAllCalls()
                val activeCall = calls.firstOrNull { it.state == Call.STATE_ACTIVE }
                val holdingCall = calls.firstOrNull { it.state == Call.STATE_HOLDING }
                
                if (activeCall == null || holdingCall == null) {
                    showSnackbar("Need an active and a held call to merge")
                    return@launch
                }
                
                // Perform conference
                try {
                    activeCall.conference(holdingCall)
                    showSnackbar("Merging calls...")
                } catch (e: Exception) {
                    Log.e("CallScreenActivity", "Primary conference command failed", e)
                    // Fallback: try unholding both first (some carriers require this)
                    try {
                        activeCall.unhold()
                        holdingCall.unhold()
                        delay(300)
                        activeCall.conference(holdingCall)
                    } catch (e2: Exception) {
                        Log.e("CallScreenActivity", "Fallback conference failed", e2)
                        showSnackbar("Merge failed: ${e.message}")
                    }
                }
                
                Log.d("CallScreenActivity", "Merge/conference request sent")
            } catch (e: Exception) {
                Log.e("CallScreenActivity", "Failed to perform merge", e)
                showSnackbar("Merge failed: ${e.message}")
            }
        }
    }

    private fun onSwapCalls() {
        Log.d("CallScreenActivity", "Swap calls requested")
        
        if (!canSwapCalls()) {
            showSnackbar("Swap not available")
            return
        }
        
        try {
            val calls = DefaultInCallService.getAllCalls()
            val activeCall = calls.find { it.state == Call.STATE_ACTIVE }
            val holdingCall = calls.find { it.state == Call.STATE_HOLDING }

            // Reliable sequence: Hold active, then unhold the target.
            if (activeCall != null && holdingCall != null) {
                showSnackbar("Swapping to ${phoneNumberState.value}...")
                
                // 1. Hold active first
                activeCall.hold()
                
                // 2. Small delay before unholding the next one to avoid telecom racing
                handler.postDelayed({
                    holdingCall.unhold()
                    
                    // 3. Update focus AFTER unhold request
                    currentCall = holdingCall
                    DefaultInCallService.currentCall = holdingCall
                    refreshPhoneNumberFromCallOrIntent()
                    updateCallCount()
                }, 300)
            } else if (holdingCall != null) {
                currentCall = holdingCall
                DefaultInCallService.currentCall = holdingCall
                refreshPhoneNumberFromCallOrIntent()
                updateCallCount()
                
                showSnackbar("Resuming ${phoneNumberState.value}...")
                holdingCall.unhold()
            }
            
            Log.d("CallScreenActivity", "Swap request sent")
        } catch (e: Exception) {
            Log.e("CallScreenActivity", "Failed to swap calls", e)
            showSnackbar("Swap failed")
        }
    }


    private fun onSwapToCall(targetCall: Call) {
        if (targetCall == currentCall && targetCall.state == Call.STATE_ACTIVE) {
            showSnackbar("Call is already active")
            return
        }

        Log.d("CallScreenActivity", "Swapping to specific call: $targetCall")
        try {
            val calls = DefaultInCallService.getAllCalls()
            val activeCall = calls.find { it.state == Call.STATE_ACTIVE }
            
            // 1. Hold active call if it's not the target
            if (activeCall != null && activeCall != targetCall) {
                activeCall.hold()
                Log.d("CallScreenActivity", "Holding active call before swap")
            }
            
            // 2. Delay unhold to let telecom process the hold
            handler.postDelayed({
                targetCall.unhold()
                
                // 3. Update UI focus
                currentCall = targetCall
                DefaultInCallService.currentCall = targetCall
                refreshPhoneNumberFromCallOrIntent()
                updateCallCount()
                
                showSnackbar("Selected: ${phoneNumberState.value}")
            }, 300)

        } catch (e: Exception) {
            Log.e("CallScreenActivity", "Failed to swap to call", e)
            showSnackbar("Swap failed")
        }
    }

    private fun onAddCall() {
        Log.d("CallScreenActivity", "Add call action requested")
        try {
            // Hold the active call first — the system requires the current call to be on hold
            // before a second call can be placed.
            currentCall?.hold()
            Log.d("CallScreenActivity", "Current call placed on hold before adding new call")

            // Small delay so the hold state is processed by Telecom before the dialer opens
            handler.postDelayed({
                try {
                    val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(dialIntent)
                    showSnackbar("Current call on hold — dial a new number")
                } catch (e: Exception) {
                    Log.e("CallScreenActivity", "Failed to open dialer for add call", e)
                    // Restore: unhold if we couldn't open the dialer
                    currentCall?.unhold()
                    showSnackbar("Could not open dialer")
                }
            }, 350)
        } catch (e: Exception) {
            Log.e("CallScreenActivity", "Failed to hold call before add call", e)
            showSnackbar("Could not add call")
        }
    }

    /**
     * Looks up the Android contacts DB for [phoneNumber] and returns the matching
     * contact ID, or null if no contact is found or READ_CONTACTS is not granted.
     */
    private fun getContactIdForNumber(phoneNumber: String): Long? {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) !=
                PackageManager.PERMISSION_GRANTED) return null
        return try {
            val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI
                .buildUpon().appendPath(phoneNumber).build()
            contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null, null, null
            )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        } catch (e: Exception) {
            Log.w("CallScreenActivity", "getContactIdForNumber failed: ${e.message}")
            null
        }
    }

    private fun showSnackbar(message: String) {
        lifecycleScope.launch {
            try {
                snackbarHostState.value.showSnackbar(message)
            } catch (e: Exception) {
                Log.w("CallScreenActivity", "Failed to show snackbar: ${e.message}")
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return if (hours > 0) {
        String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", minutes, secs)
    }
}

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CallScreen(
    phoneNumber: String,
    initialCallState: String,
    call: Call?,
    onAnswerCall: () -> Unit,
    onRejectCall: () -> Unit,
    onRejectWithSms: (String) -> Unit = {},
    onRemindLater: (Long) -> Unit = {},
    ringerSilenced: Boolean = false,
    onEndCall: () -> Unit,
    onToggleMute: () -> Unit,
    onSetAudioRoute: (Int) -> Unit,
    audioState: CallAudioState?,
    isOnHold: Boolean = false,
    onToggleHold: () -> Unit = {},
    canConference: Boolean = false,
    canMerge: Boolean = false,
    canSwap: Boolean = false,
    callCount: Int = 1,
    onConference: () -> Unit = {},
    onMerge: () -> Unit = {},
    onSwapCalls: () -> Unit = {},
    onSwapToCall: (Call) -> Unit = {},
    allCalls: List<Call> = emptyList(),
    onAddCall: () -> Unit = {},
    onSendDtmf: (Char) -> Unit = {},
    showKeypad: Boolean,
    onToggleKeypad: () -> Unit,
    getContactName: (String) -> String?,
    onViewContact: (String) -> Unit = {},
    snackbarHostState: androidx.compose.material3.SnackbarHostState = remember { androidx.compose.material3.SnackbarHostState() },
    // TTY mode (one of TtyHelper.TTY_MODE_*)
    ttyMode: Int = TtyHelper.TTY_MODE_OFF,
    onSetTtyMode: (Int) -> Unit = {},
    // VoLTE / VoWiFi badge label, null = plain GSM
    networkBadge: String? = null,
    // Whether this is an emergency call
    isEmergencyCall: Boolean = false,
    // SIM label for the active call slot, e.g. "SIM 1 – Carrier"
    simLabel: String? = null
) {
    val coroutineScope = rememberCoroutineScope()
    var callState by remember(call) { mutableStateOf(initialCallState) }
    var elapsedTime by remember(call) { mutableLongStateOf(0L) }
    var isActive by remember(call) {
        mutableStateOf(initialCallState.contains("Active", ignoreCase = true))
    }
    var isMuted by remember { mutableStateOf(false) }
    var showAudioRouteMenu by remember { mutableStateOf(false) }
    var showSwapMenu by remember { mutableStateOf(false) }
    var showTtyMenu by remember { mutableStateOf(false) }
    // Reject-with-SMS sheet state
    var showRejectSmsSheet by remember { mutableStateOf(false) }
    val rejectSmsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Remind-later sheet state
    var showReminderSheet by remember { mutableStateOf(false) }
    val reminderSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Notes sheet state
    val context = LocalContext.current
    var showNotesSheet by remember { mutableStateOf(false) }
    val notesSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var noteText by remember(phoneNumber) { mutableStateOf(CallNoteHelper.getNote(context, phoneNumber)) }
    
    // Determine current audio route and available routes
    val currentRoute = audioState?.route ?: CallAudioState.ROUTE_EARPIECE
    val supportedRoutes = audioState?.supportedRouteMask ?: (CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER)
    
    val isSpeakerOn = currentRoute == CallAudioState.ROUTE_SPEAKER
    val isBluetoothOn = currentRoute == CallAudioState.ROUTE_BLUETOOTH
    
    var isRinging by remember(call) {
        mutableStateOf(
            initialCallState.contains("Incoming", ignoreCase = true) ||
            initialCallState.contains("Ringing", ignoreCase = true)
        )
    }

    // Resolved number and contact name
    val resolvedNumber = call?.details?.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: phoneNumber
    val contactName = remember(resolvedNumber) { getContactName(resolvedNumber) }
    val displayName = if (!contactName.isNullOrBlank() && resolvedNumber != "Unknown") contactName else resolvedNumber

    // Monitor call state
    DisposableEffect(call) {
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call?, state: Int) {
                when (state) {
                    Call.STATE_ACTIVE -> {
                        callState = "Active"
                        isActive = true
                        isRinging = false
                    }
                    Call.STATE_DISCONNECTED -> {
                        val disconnectCause = call?.details?.disconnectCause
                        Log.d("CallScreen", "Disconnected: ${disconnectCause?.reason}")
                        onEndCall()
                    }
                    Call.STATE_RINGING -> {
                        isRinging = true
                        isActive = false
                    }
                }
            }
        }
        call?.registerCallback(callback)
        onDispose { call?.unregisterCallback(callback) }
    }

    // Timer effect for call duration
    LaunchedEffect(isActive) {
        while (isActive) {
            delay(1000)
            elapsedTime += 1
        }
    }

    // Modern color scheme
    val backgroundColor = Color(0xFF0A0F1C) // Dark blue background
    val surfaceColor = Color(0xFF1A2138) // Card/button background
    val primaryColor = Color(0xFF4A6FFF) // Blue accent
    val successColor = Color(0xFF2ECC71) // Green
    val dangerColor = Color(0xFFFF4757) // Red
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A0F1C),
                        Color(0xFF141B2D)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top section - Contact info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Animated avatar circle
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    primaryColor.copy(alpha = 0.3f),
                                    primaryColor.copy(alpha = 0.1f)
                                )
                            ),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .background(
                                color = primaryColor,
                                shape = CircleShape
                            )
                            .border(
                                width = 4.dp,
                                color = primaryColor.copy(alpha = 0.3f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (contactName != null && contactName.isNotBlank()) {
                            val initial = contactName.first().uppercase()
                            Text(
                                text = initial,
                                style = MaterialTheme.typography.displayLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Contact",
                                modifier = Modifier.size(48.dp),
                                tint = Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Contact name with animation
                AnimatedContent(
                    targetState = displayName,
                    transitionSpec = {
                        fadeIn() with fadeOut() using SizeTransform(false)
                    }
                ) { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }

                // Phone number
                if (resolvedNumber != displayName && resolvedNumber != "Unknown") {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = resolvedNumber,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF8F9BB3),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Call state chip
                Box(
                    modifier = Modifier
                        .background(
                            color = when {
                                isRinging -> successColor.copy(alpha = 0.2f)
                                isActive -> primaryColor.copy(alpha = 0.2f)
                                else -> Color(0xFF8F9BB3).copy(alpha = 0.2f)
                            },
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = callState,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = when {
                            isRinging -> successColor
                            isActive -> primaryColor
                            else -> Color(0xFF8F9BB3)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Emergency call banner
                if (isEmergencyCall) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = Color(0xFFFF4757).copy(alpha = 0.2f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "EMERGENCY CALL",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF4757)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // VoLTE / VoWiFi / 5G badge + SIM label row
                if (networkBadge != null || simLabel != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (networkBadge != null) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = Color(0xFF4A6FFF).copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = networkBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF4A6FFF)
                                )
                            }
                        }
                        if (simLabel != null) {
                            Text(
                                text = simLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF8F9BB3)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // TTY mode indicator + picker button (shown during active calls)
                if (isActive || isOnHold) {
                    Box {
                        TextButton(onClick = { showTtyMenu = true }) {
                            val ttyLabel = TtyHelper.modeName(ttyMode)
                            Text(
                                text = if (ttyMode == TtyHelper.TTY_MODE_OFF) "TTY: Off" else "TTY: $ttyLabel",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (ttyMode == TtyHelper.TTY_MODE_OFF) Color(0xFF8F9BB3) else Color(0xFF4A6FFF)
                            )
                        }
                        DropdownMenu(
                            expanded = showTtyMenu,
                            onDismissRequest = { showTtyMenu = false },
                            modifier = Modifier.background(Color(0xFF1A2138))
                        ) {
                            TtyHelper.allModes.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            TtyHelper.modeName(mode),
                                            color = if (mode == ttyMode) Color(0xFF4A6FFF) else Color.White
                                        )
                                    },
                                    onClick = {
                                        onSetTtyMode(mode)
                                        showTtyMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Call duration with pulse animation
                if (isActive) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = successColor,
                                    shape = CircleShape
                                )
                                .animatePulse()
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = formatDuration(elapsedTime),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            color = successColor
                        )
                    }
                }
            }

            // Bottom section - Call controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = surfaceColor,
                        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                    )
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Show answer/reject for incoming calls
                if (isRinging) {
                    // Ringer-silenced indicator
                    if (ringerSilenced) {
                        Text(
                            text = "Ringer silenced",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF8F9BB3),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Reject button
                        ModernCallButton(
                            onClick = onRejectCall,
                            icon = Icons.Default.CallEnd,
                            iconColor = Color.White,
                            backgroundColor = dangerColor,
                            size = 72.dp,
                            label = "Decline",
                            iconSize = 32.dp
                        )

                        // Answer button
                        ModernCallButton(
                            onClick = onAnswerCall,
                            icon = Icons.Default.Call,
                            iconColor = Color.White,
                            backgroundColor = successColor,
                            size = 72.dp,
                            label = "Answer",
                            iconSize = 32.dp
                        )
                    }

                    // Second row: Reject with SMS + Remind later
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Reject with SMS
                        ModernCallButton(
                            onClick = { showRejectSmsSheet = true },
                            icon = Icons.Default.Message,
                            iconColor = Color(0xFF8F9BB3),
                            backgroundColor = Color.Transparent,
                            size = 48.dp,
                            label = "Message",
                            iconSize = 22.dp,
                            showBackground = false
                        )

                        // Remind later
                        ModernCallButton(
                            onClick = { showReminderSheet = true },
                            icon = Icons.Default.AccessTime,
                            iconColor = Color(0xFF8F9BB3),
                            backgroundColor = Color.Transparent,
                            size = 48.dp,
                            label = "Remind Me",
                            iconSize = 22.dp,
                            showBackground = false
                        )
                    }

                    // Reject with SMS bottom sheet
                    if (showRejectSmsSheet) {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val templates = remember { RejectSmsHelper.getTemplates(context) }
                        ModalBottomSheet(
                            onDismissRequest = { showRejectSmsSheet = false },
                            sheetState = rejectSmsSheetState,
                            containerColor = Color(0xFF1A2138)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Reply with message",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                                templates.forEach { template ->
                                    TextButton(
                                        onClick = {
                                            showRejectSmsSheet = false
                                            onRejectWithSms(template)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = template,
                                            color = Color(0xFFCDD5E0),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }

                    // Remind later bottom sheet
                    if (showReminderSheet) {
                        ModalBottomSheet(
                            onDismissRequest = { showReminderSheet = false },
                            sheetState = reminderSheetState,
                            containerColor = Color(0xFF1A2138)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Remind me to call back",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                                listOf(
                                    "In 5 minutes" to 5 * 60 * 1000L,
                                    "In 15 minutes" to 15 * 60 * 1000L,
                                    "In 1 hour" to 60 * 60 * 1000L
                                ).forEach { (label, delayMs) ->
                                    TextButton(
                                        onClick = {
                                            showReminderSheet = false
                                            onRemindLater(delayMs)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = label,
                                            color = Color(0xFFCDD5E0),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }
                } else {
                    // Primary call control buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Mute button
                        ModernCallButton(
                            onClick = {
                                isMuted = !isMuted
                                onToggleMute()
                            },
                            icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            iconColor = if (isMuted) Color.White else Color(0xFF8F9BB3),
                            backgroundColor = if (isMuted) dangerColor else surfaceColor.copy(alpha = 0.5f),
                            size = 56.dp,
                            label = if (isMuted) "Muted" else "Mute",
                            iconSize = 24.dp
                        )

                        // Keypad button
                        ModernCallButton(
                            onClick = onToggleKeypad,
                            icon = Icons.Default.Dialpad,
                            iconColor = if (showKeypad) primaryColor else Color(0xFF8F9BB3),
                            backgroundColor = if (showKeypad) primaryColor.copy(alpha = 0.2f) else surfaceColor.copy(alpha = 0.5f),
                            size = 56.dp,
                            label = "Keypad",
                            iconSize = 24.dp
                        )

                        // Audio route button
                        Box {
                            ModernCallButton(
                                onClick = {
                                    val hasBluetooth = (supportedRoutes and CallAudioState.ROUTE_BLUETOOTH) != 0
                                    if (hasBluetooth) {
                                        showAudioRouteMenu = true
                                    } else {
                                        val newRoute = if (isSpeakerOn) CallAudioState.ROUTE_WIRED_OR_EARPIECE else CallAudioState.ROUTE_SPEAKER
                                        onSetAudioRoute(newRoute)
                                    }
                                },
                                icon = if (isBluetoothOn) Icons.Default.BluetoothAudio else Icons.Default.VolumeUp,
                                iconColor = when {
                                    isBluetoothOn -> Color.White
                                    isSpeakerOn -> primaryColor
                                    else -> Color(0xFF8F9BB3)
                                },
                                backgroundColor = when {
                                    isBluetoothOn || isSpeakerOn -> primaryColor.copy(alpha = 0.2f)
                                    else -> surfaceColor.copy(alpha = 0.5f)
                                },
                                size = 56.dp,
                                label = when {
                                    isBluetoothOn -> "BT"
                                    isSpeakerOn -> "Speaker"
                                    else -> "Audio"
                                },
                                iconSize = 24.dp
                            )

                            DropdownMenu(
                                expanded = showAudioRouteMenu,
                                onDismissRequest = { showAudioRouteMenu = false },
                                modifier = Modifier.background(surfaceColor)
                            ) {
                                if ((supportedRoutes and CallAudioState.ROUTE_BLUETOOTH) != 0) {
                                    DropdownMenuItem(
                                        text = { 
                                            Text("Bluetooth", color = Color.White) 
                                        },
                                        onClick = {
                                            onSetAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
                                            showAudioRouteMenu = false
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.BluetoothAudio, 
                                                contentDescription = null,
                                                tint = primaryColor
                                            )
                                        },
                                        modifier = Modifier.background(surfaceColor)
                                    )
                                }
                                if ((supportedRoutes and CallAudioState.ROUTE_SPEAKER) != 0) {
                                    DropdownMenuItem(
                                        text = { 
                                            Text("Speaker", color = Color.White) 
                                        },
                                        onClick = {
                                            onSetAudioRoute(CallAudioState.ROUTE_SPEAKER)
                                            showAudioRouteMenu = false
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.VolumeUp, 
                                                contentDescription = null,
                                                tint = primaryColor
                                            )
                                        },
                                        modifier = Modifier.background(surfaceColor)
                                    )
                                }
                                if ((supportedRoutes and CallAudioState.ROUTE_EARPIECE) != 0 || 
                                    (supportedRoutes and CallAudioState.ROUTE_WIRED_HEADSET) != 0) {
                                    DropdownMenuItem(
                                        text = { 
                                            Text("Phone", color = Color.White) 
                                        },
                                        onClick = {
                                            onSetAudioRoute(CallAudioState.ROUTE_WIRED_OR_EARPIECE)
                                            showAudioRouteMenu = false
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Phone, 
                                                contentDescription = null,
                                                tint = primaryColor
                                            )
                                        },
                                        modifier = Modifier.background(surfaceColor)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Secondary action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Hold button
                        ModernCallButton(
                            onClick = onToggleHold,
                            icon = Icons.Default.Pause,
                            iconColor = if (isOnHold) Color.White else Color(0xFF8F9BB3),
                            backgroundColor = if (isOnHold) primaryColor.copy(alpha = 0.5f) else Color.Transparent,
                            size = 40.dp,
                            label = if (isOnHold) "Resume" else "Hold",
                            iconSize = 20.dp,
                            showBackground = isOnHold
                        )

                        // Add Call button
                        ModernCallButton(
                            onClick = onAddCall,
                            icon = Icons.Default.Person,
                            iconColor = Color(0xFF8F9BB3),
                            backgroundColor = Color.Transparent,
                            size = 40.dp,
                            label = "Add Call",
                            iconSize = 20.dp,
                            showBackground = false
                        )

                        // Merge button (when available)
                        if (canMerge) {
                            ModernCallButton(
                                onClick = onMerge,
                                icon = Icons.Default.Call,
                                iconColor = Color(0xFF8F9BB3),
                                backgroundColor = Color.Transparent,
                                size = 40.dp,
                                label = "Merge",
                                iconSize = 20.dp,
                                showBackground = false
                            )
                        }

                        // Swap button (when available)
                        if (canSwap) {
                            Box {
                                ModernCallButton(
                                    onClick = {
                                        if (allCalls.size >= 2) {
                                            showSwapMenu = true
                                        } else {
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar("No other calls to swap to")
                                            }
                                        }
                                    },
                                    icon = Icons.Default.SwapVert,
                                    iconColor = Color(0xFF8F9BB3),
                                    backgroundColor = Color.Transparent,
                                    size = 40.dp,
                                    label = "Swap",
                                    iconSize = 20.dp,
                                    showBackground = false
                                )

                                DropdownMenu(
                                    expanded = showSwapMenu,
                                    onDismissRequest = { showSwapMenu = false },
                                    modifier = Modifier.background(surfaceColor)
                                ) {
                                    allCalls.forEach { remoteCall ->
                                        val number = remoteCall.details.handle?.schemeSpecificPart ?: "Unknown"
                                        val name = getContactName(number) ?: number
                                        val status = when (remoteCall.state) {
                                            Call.STATE_ACTIVE -> "Active"
                                            Call.STATE_HOLDING -> "On Hold"
                                            Call.STATE_DIALING -> "Dialing"
                                            Call.STATE_RINGING -> "Ringing"
                                            else -> "Unknown"
                                        }

                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(name, color = if (remoteCall.state == Call.STATE_ACTIVE) primaryColor else Color.White)
                                                    Text(status, style = MaterialTheme.typography.bodySmall, color = Color(0xFF8F9BB3))
                                                }
                                            },
                                            onClick = {
                                                onSwapToCall(remoteCall)
                                                showSwapMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Notes button — opens a note-taking sheet tied to this number
                        ModernCallButton(
                            onClick = { showNotesSheet = true },
                            icon = Icons.Default.Edit,
                            iconColor = if (noteText.isNotBlank()) primaryColor else Color(0xFF8F9BB3),
                            backgroundColor = if (noteText.isNotBlank()) primaryColor.copy(alpha = 0.15f) else Color.Transparent,
                            size = 40.dp,
                            label = "Notes",
                            iconSize = 20.dp,
                            showBackground = noteText.isNotBlank()
                        )

                        // Contact button — opens the full contact record
                        ModernCallButton(
                            onClick = { onViewContact(resolvedNumber) },
                            icon = Icons.Default.Person,
                            iconColor = Color(0xFF8F9BB3),
                            backgroundColor = Color.Transparent,
                            size = 40.dp,
                            label = "Contact",
                            iconSize = 20.dp,
                            showBackground = false
                        )
                    }

                    // Notes bottom sheet
                    if (showNotesSheet) {
                        ModalBottomSheet(
                            onDismissRequest = { showNotesSheet = false },
                            sheetState = notesSheetState,
                            containerColor = Color(0xFF1A2138)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                                Text(
                                    text = "Call Notes",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                                OutlinedTextField(
                                    value = noteText,
                                    onValueChange = { updated ->
                                        noteText = updated
                                        CallNoteHelper.saveNote(context, resolvedNumber, updated)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp),
                                    placeholder = {
                                        Text(
                                            "Type a note about this call…",
                                            color = Color(0xFF8F9BB3)
                                        )
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = primaryColor,
                                        unfocusedBorderColor = Color(0xFF3A4A6B),
                                        cursorColor = primaryColor
                                    )
                                )
                                if (noteText.isNotBlank()) {
                                    TextButton(
                                        onClick = {
                                            CallNoteHelper.clearNote(context, resolvedNumber)
                                            noteText = ""
                                        }
                                    ) {
                                        Text("Clear note", color = Color(0xFF8F9BB3))
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // End call button with glow effect
                    Box(
                        modifier = Modifier
                            .shadow(
                                elevation = 16.dp,
                                shape = CircleShape,
                                ambientColor = dangerColor,
                                spotColor = dangerColor.copy(alpha = 0.5f)
                            )
                    ) {
                        FloatingActionButton(
                            onClick = onEndCall,
                            modifier = Modifier.size(80.dp),
                            containerColor = dangerColor,
                            shape = CircleShape
                        ) {
                            Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = "End Call",
                                modifier = Modifier.size(36.dp),
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }

        // Snackbar Host for feedback
        androidx.compose.material3.SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)
        )

        // Modern keypad overlay
        if (showKeypad) {
            ModernKeypad(
                onSendDtmf = onSendDtmf,
                onClose = onToggleKeypad,
                backgroundColor = surfaceColor
            )
        }
    }
}

@Composable
fun ModernCallButton(
    onClick: () -> Unit,
    icon: ImageVector,
    iconColor: Color,
    backgroundColor: Color,
    size: Dp,
    label: String,
    iconSize: Dp,
    showBackground: Boolean = true
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(size)
                .then(
                    if (showBackground) {
                        Modifier.background(
                            color = backgroundColor,
                            shape = CircleShape
                        )
                    } else {
                        Modifier
                    }
                )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(iconSize),
                tint = iconColor
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF8F9BB3)
        )
    }
}

@Composable
fun ModernKeypad(
    onSendDtmf: (Char) -> Unit,
    onClose: () -> Unit,
    backgroundColor: Color
) {
    var pressedDigits by remember { mutableStateOf("") }
    val keypadButtons = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("*", "0", "#")
    )

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .padding(16.dp),
        color = Color.Transparent,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Keypad",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Digits display
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(
                        color = backgroundColor,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (pressedDigits.isEmpty()) "Enter digits..." else pressedDigits,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Keypad grid
            keypadButtons.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    row.forEach { digit ->
                        KeypadButton(
                            digit = digit,
                            onClick = {
                                pressedDigits += digit
                                onSendDtmf(digit[0])
                            },
                            modifier = Modifier.size(80.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Clear button
            if (pressedDigits.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(
                    onClick = { pressedDigits = "" },
                    modifier = Modifier.padding(8.dp)
                ) {
                    Text(
                        text = "Clear",
                        color = Color(0xFF8F9BB3),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
fun KeypadButton(
    digit: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(
                color = Color(0xFF2A3152),
                shape = CircleShape
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = digit,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            if (digit == "1") {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8F9BB3)
                )
            }
        }
    }
}

// Animation modifier for pulse effect
fun Modifier.animatePulse(): Modifier = composed {
    val infiniteTransition = rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        )
    )
    this.alpha(alpha)
}

