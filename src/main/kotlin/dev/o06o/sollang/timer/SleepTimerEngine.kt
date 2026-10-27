package dev.o06o.sollang.timer

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class StoredSleepTimer(
    val id: String,
    val startedAtMillis: Long,
    val expiresAtMillis: Long,
)

sealed interface SleepTimerState {
    data object Idle : SleepTimerState

    data class Running(
        val timerId: String,
        val remainingSeconds: Int,
    ) : SleepTimerState
}

sealed interface SleepTimerEvent {
    data class Started(
        val timerId: String,
        val configuredSeconds: Int,
    ) : SleepTimerEvent

    data class Ended(
        val timerId: String,
        val reason: EndReason,
    ) : SleepTimerEvent
}

enum class EndReason {
    EXPIRED,
    CANCELLED,
    REPLACED,
    CLOCK_RESET,
}

fun interface MonotonicClock {
    fun nowMillis(): Long
}

interface SleepTimerStore {
    suspend fun read(): StoredSleepTimer?

    suspend fun write(timer: StoredSleepTimer)

    suspend fun clearIfMatches(timerId: String): Boolean
}

interface AlarmScheduler {
    suspend fun schedule(timer: StoredSleepTimer)

    suspend fun cancel(timerId: String)
}

fun interface PlaybackStopper {
    suspend fun stop()
}

/**
 * Deterministic core extracted from a production Android sleep timer.
 * Android-specific AlarmManager, DataStore and Media3 adapters stay outside this class.
 */
class SleepTimerEngine(
    private val store: SleepTimerStore,
    private val alarmScheduler: AlarmScheduler,
    private val playbackStopper: PlaybackStopper,
    private val clock: MonotonicClock,
    private val idFactory: () -> String,
) {
    private val mutationMutex = Mutex()
    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Idle)
    private val _events = MutableSharedFlow<SleepTimerEvent>(extraBufferCapacity = 8)

    val state = _state.asStateFlow()
    val events = _events.asSharedFlow()

    suspend fun setTimer(durationSeconds: Int) = mutationMutex.withLock {
        require(durationSeconds > 0) { "durationSeconds must be positive" }

        store.read()?.let { current ->
            alarmScheduler.cancel(current.id)
            if (store.clearIfMatches(current.id)) {
                _events.emit(SleepTimerEvent.Ended(current.id, EndReason.REPLACED))
            }
        }

        val now = clock.nowMillis()
        val timer = StoredSleepTimer(
            id = idFactory(),
            startedAtMillis = now,
            expiresAtMillis = now + durationSeconds * MILLIS_PER_SECOND,
        )
        store.write(timer)
        alarmScheduler.schedule(timer)
        updateRunningState(timer, now)
        _events.emit(SleepTimerEvent.Started(timer.id, durationSeconds))
    }

    suspend fun cancel() = mutationMutex.withLock {
        val timer = store.read() ?: return@withLock
        alarmScheduler.cancel(timer.id)
        if (store.clearIfMatches(timer.id)) {
            _state.value = SleepTimerState.Idle
            _events.emit(SleepTimerEvent.Ended(timer.id, EndReason.CANCELLED))
        }
    }

    suspend fun restore() = mutationMutex.withLock {
        val timer = store.read() ?: run {
            _state.value = SleepTimerState.Idle
            return@withLock
        }
        val now = clock.nowMillis()

        if (timer.startedAtMillis > now) {
            alarmScheduler.cancel(timer.id)
            if (store.clearIfMatches(timer.id)) {
                _state.value = SleepTimerState.Idle
                _events.emit(SleepTimerEvent.Ended(timer.id, EndReason.CLOCK_RESET))
            }
            return@withLock
        }

        if (timer.expiresAtMillis <= now) {
            expireLocked(timer.id)
        } else {
            alarmScheduler.schedule(timer)
            updateRunningState(timer, now)
        }
    }

    suspend fun onTick() = mutationMutex.withLock {
        val timer = store.read() ?: run {
            _state.value = SleepTimerState.Idle
            return@withLock
        }
        val now = clock.nowMillis()
        if (timer.expiresAtMillis <= now) {
            expireLocked(timer.id)
        } else {
            updateRunningState(timer, now)
        }
    }

    suspend fun onAlarm(timerId: String): Boolean = mutationMutex.withLock {
        expireLocked(timerId)
    }

    private suspend fun expireLocked(timerId: String): Boolean {
        val timer = store.read()
        if (timer?.id != timerId || !store.clearIfMatches(timerId)) return false

        alarmScheduler.cancel(timerId)
        playbackStopper.stop()
        _state.value = SleepTimerState.Idle
        _events.emit(SleepTimerEvent.Ended(timerId, EndReason.EXPIRED))
        return true
    }

    private fun updateRunningState(timer: StoredSleepTimer, now: Long) {
        val remainingMillis = (timer.expiresAtMillis - now).coerceAtLeast(0L)
        val remainingSeconds = (
            (remainingMillis + MILLIS_PER_SECOND - 1L) / MILLIS_PER_SECOND
            ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        _state.value = SleepTimerState.Running(timer.id, remainingSeconds)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}

