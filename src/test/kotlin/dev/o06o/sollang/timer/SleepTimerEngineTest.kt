package dev.o06o.sollang.timer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SleepTimerEngineTest {
    @Test
    fun `restore schedules a persisted timer and rounds remaining time up`() = runTest {
        val fixture = Fixture(nowMillis = 10_250L)
        fixture.store.timer = StoredSleepTimer(
            id = "timer-1",
            startedAtMillis = 10_000L,
            expiresAtMillis = 12_001L,
        )

        fixture.engine.restore()

        assertEquals(listOf("timer-1"), fixture.scheduler.scheduledIds)
        assertEquals(
            SleepTimerState.Running(timerId = "timer-1", remainingSeconds = 2),
            fixture.engine.state.value,
        )
    }

    @Test
    fun `duplicate alarm delivery stops playback exactly once`() = runTest {
        val fixture = Fixture()
        fixture.engine.setTimer(durationSeconds = 60)
        val timerId = assertIs<SleepTimerState.Running>(fixture.engine.state.value).timerId

        assertTrue(fixture.engine.onAlarm(timerId))
        assertFalse(fixture.engine.onAlarm(timerId))

        assertEquals(1, fixture.playback.stopCount)
        assertEquals(SleepTimerState.Idle, fixture.engine.state.value)
        assertNull(fixture.store.timer)
    }

    @Test
    fun `stale alarm cannot expire a replacement timer`() = runTest {
        val fixture = Fixture(ids = ArrayDeque(listOf("old", "new")))
        fixture.engine.setTimer(durationSeconds = 60)
        fixture.engine.setTimer(durationSeconds = 120)

        assertFalse(fixture.engine.onAlarm("old"))
        assertEquals(0, fixture.playback.stopCount)
        assertEquals(
            SleepTimerState.Running(timerId = "new", remainingSeconds = 120),
            fixture.engine.state.value,
        )
    }

    @Test
    fun `monotonic clock reset discards an invalid timer without stopping playback`() = runTest {
        val fixture = Fixture(nowMillis = 1_000L)
        fixture.store.timer = StoredSleepTimer(
            id = "previous-boot",
            startedAtMillis = 50_000L,
            expiresAtMillis = 60_000L,
        )

        fixture.engine.restore()

        assertNull(fixture.store.timer)
        assertEquals(0, fixture.playback.stopCount)
        assertEquals(SleepTimerState.Idle, fixture.engine.state.value)
    }
}

private class Fixture(
    nowMillis: Long = 1_000L,
    ids: ArrayDeque<String> = ArrayDeque(listOf("timer-1")),
) {
    val store = InMemoryTimerStore()
    val scheduler = RecordingAlarmScheduler()
    val playback = RecordingPlaybackStopper()
    val engine = SleepTimerEngine(
        store = store,
        alarmScheduler = scheduler,
        playbackStopper = playback,
        clock = MonotonicClock { nowMillis },
        idFactory = { ids.removeFirst() },
    )
}

private class InMemoryTimerStore : SleepTimerStore {
    var timer: StoredSleepTimer? = null

    override suspend fun read(): StoredSleepTimer? = timer

    override suspend fun write(timer: StoredSleepTimer) {
        this.timer = timer
    }

    override suspend fun clearIfMatches(timerId: String): Boolean {
        if (timer?.id != timerId) return false
        timer = null
        return true
    }
}

private class RecordingAlarmScheduler : AlarmScheduler {
    val scheduledIds = mutableListOf<String>()
    val cancelledIds = mutableListOf<String>()

    override suspend fun schedule(timer: StoredSleepTimer) {
        scheduledIds += timer.id
    }

    override suspend fun cancel(timerId: String) {
        cancelledIds += timerId
    }
}

private class RecordingPlaybackStopper : PlaybackStopper {
    var stopCount = 0

    override suspend fun stop() {
        stopCount++
    }
}

