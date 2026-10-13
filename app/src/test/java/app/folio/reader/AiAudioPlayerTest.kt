package app.folio.reader

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

/** Fake hardware separates submitted, rendered and committed-for-future-presentation PCM. */
class AiAudioPlayerTest {
    private class Track : AiPcmTrack {
        val submitted = AtomicInteger()
        val head = AtomicInteger()
        val playCalls = AtomicInteger()
        val pauseCalls = AtomicInteger()
        @Volatile var rate = 1f
        override fun setPlaybackRate(rate: Float) { this.rate=rate }
        @Volatile var stamp: AiPcmTimestamp? = null
        @Volatile var safetyFrames = 0
        @Volatile var playing = false
        @Volatile var released = false
        override fun play() { check(!released); playCalls.incrementAndGet(); playing = true }
        override fun pause() { pauseCalls.incrementAndGet(); playing = false }
        override fun write(samples: FloatArray, offset: Int, count: Int): Int {
            check(!released)
            submitted.addAndGet(count)
            return count
        }
        override fun playedFrames() = head.get().toLong()
        override fun timestamp() = stamp
        override fun fallbackSafetyFrames() = safetyFrames
        override fun release() { released = true }
    }
    private class Events : AiAudioPlayer.Listener {
        val starts = CopyOnWriteArrayList<String>()
        val positions = CopyOnWriteArrayList<Pair<String, Int>>()
        val completed = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<String>()
        override fun onStarted(utteranceId: String) { starts.add(utteranceId) }
        override fun onPosition(utteranceId: String, playedSamples: Int, sampleRate: Int, totalSamples: Int) {
            positions.add(utteranceId to playedSamples)
        }
        override fun onCompleted(utteranceId: String) { completed.add(utteranceId) }
        override fun onError(utteranceId: String, message: String) { errors.add(message) }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("Timed out waiting for the playback worker", condition())
    }

    @Test fun submittingTheWholeClipDoesNotStartOrCompleteIt() {
        val track = Track()
        val events = Events()
        val worker = Executors.newSingleThreadExecutor()
        val player = AiAudioPlayer(events, { track }, { it() }, worker)
        try {
            player.play(AiAudioClip(FloatArray(1000) { .1f }), "first")
            await { track.submitted.get() == 1000 }
            assertTrue(events.starts.isEmpty())
            assertTrue(events.positions.isEmpty())
            assertTrue(events.completed.isEmpty())
            assertEquals(0, player.state.value.playedSamples)
            track.head.set(123)
            await { events.positions.any { it.second == 123 } }
            assertEquals(listOf("first"), events.starts.toList())
            assertTrue(events.completed.isEmpty())
            track.head.set(1000)
            await { events.completed.size == 1 }
            assertEquals("first" to 1000, events.positions.last())
            assertEquals(AiPlaybackPhase.COMPLETE, player.state.value.phase)
            assertFalse("Natural completion keeps the output track alive", track.released)
            assertTrue(events.errors.isEmpty())
        } finally { player.close(); worker.awaitTermination(3, TimeUnit.SECONDS) }
        assertTrue(track.released)
    }

    @Test fun pauseAndFreshCallbackIdResumeTheSameAudioAtItsActualSampleOffset() {
        val track = Track()
        val events = Events()
        val worker = Executors.newSingleThreadExecutor()
        val player = AiAudioPlayer(events, { track }, { it() }, worker)
        try {
            player.play(AiAudioClip(FloatArray(1200) { .1f }), "old", 200)
            await { track.submitted.get() == 1000 }
            track.head.set(150)
            await { events.starts.contains("old") }
            assertEquals(350, player.pause())
            assertFalse(track.playing)
            assertEquals(AiPlaybackPhase.PAUSED, player.state.value.phase)
            assertEquals(350, player.state.value.playedSamples)
            assertTrue(player.resume("fresh"))
            assertTrue(track.playing)
            track.head.set(400)
            await { events.positions.contains("fresh" to 600) }
            assertTrue(events.starts.contains("fresh"))
            assertEquals(1000, track.submitted.get())
            track.head.set(1000)
            await { events.completed.contains("fresh") }
            assertFalse(events.completed.contains("old"))
        } finally { player.close(); worker.awaitTermination(3, TimeUnit.SECONDS) }
    }

    @Test fun stopSuppressesAlreadyQueuedCallbacksAndReleasesHardware() {
        val track = Track()
        val events = Events()
        val delivery = ConcurrentLinkedQueue<() -> Unit>()
        val worker = Executors.newSingleThreadExecutor()
        val player = AiAudioPlayer(events, { track }, { delivery.add(it) }, worker)
        try {
            player.play(AiAudioClip(FloatArray(1000) { .1f }), "stopped")
            await { track.submitted.get() == 1000 }
            track.head.set(250)
            await { delivery.size >= 2 }
            assertEquals(250, player.stop())
            await { track.released }
            while (true) (delivery.poll() ?: break).invoke()
            assertTrue(events.starts.isEmpty())
            assertTrue(events.positions.isEmpty())
            assertTrue(events.completed.isEmpty())
            assertFalse(player.resume("late"))
            assertEquals(250, player.state.value.playedSamples)
        } finally { player.close(); worker.awaitTermination(3, TimeUnit.SECONDS) }
    }

    @Test fun futureCommittedFramesCannotCompleteOrAppendTheNextPassageEarly() {
        val track = Track()
        val events = Events()
        val time = AtomicLong(1_000_000_000)
        val worker = Executors.newSingleThreadExecutor()
        val player = AiAudioPlayer(events, { track }, { it() }, worker, time::get)
        try {
            player.play(AiAudioClip(FloatArray(1000) { .1f }), "first")
            await { track.submitted.get() == 1000 }
            track.stamp = AiPcmTimestamp(1000, 1_100_000_000)
            track.head.set(1000) // Everything rendered; final frames are committed 100ms ahead.
            Thread.sleep(70)
            assertTrue(events.completed.isEmpty())
            assertEquals(0, player.state.value.playedSamples)
            assertFalse(player.append(AiAudioClip(FloatArray(600) { .1f }), "too-early"))
            time.set(1_090_000_000)
            await { events.starts.contains("first") }
            assertTrue(events.completed.isEmpty())
            assertEquals(760, player.pause())
            time.set(1_500_000_000)
            assertEquals("Pause freezes the checkpoint despite time advancing", 760, player.pause())
            assertTrue(player.resume("resumed"))
            track.stamp = AiPcmTimestamp(1000, time.get())
            await { events.completed.contains("resumed") }
            assertFalse(events.completed.contains("first"))
        } finally { player.close(); worker.awaitTermination(3, TimeUnit.SECONDS) }
    }

    @Test fun naturalHandoffUsesOneUninterruptedTrackAndPerPassageOffsetsAtBothRates() {
        for (rate in listOf(24000, 44100)) {
            val track = Track()
            val events = Events()
            val factoryCalls = AtomicInteger()
            val worker = Executors.newSingleThreadExecutor()
            val player = AiAudioPlayer(events, { factoryCalls.incrementAndGet(); track }, { it() }, worker)
            try {
                player.play(AiAudioClip(FloatArray(1200) { .1f }, rate), "first", 200)
                await { track.submitted.get() == 1000 }
                track.head.set(1000)
                await { events.completed.contains("first") }
                assertEquals(0, track.pauseCalls.get())
                assertFalse(track.released)
                assertTrue(player.append(AiAudioClip(FloatArray(800) { .2f }, rate), "next", 100))
                await { track.submitted.get() == 1700 }
                assertEquals(1, factoryCalls.get())
                assertEquals(1, track.playCalls.get())
                assertEquals(0, track.pauseCalls.get())
                track.head.set(1250) // 1000 prior frames +250 frames from the new passage.
                await { events.positions.contains("next" to 350) }
                assertEquals(350, player.pause())
                assertTrue(player.resume("next-resumed"))
                track.head.set(1700)
                await { events.completed.contains("next-resumed") }
                assertEquals(800, events.positions.last().second)
                assertFalse(player.append(AiAudioClip(FloatArray(100), if (rate == 24000) 44100 else 24000), "other-rate"))
            } finally { player.close(); worker.awaitTermination(3, TimeUnit.SECONDS) }
        }
    }

    @Test fun seekDisposesOldTrackBeforeCreatingReplacementAndDropsItsCallbacks() {
        val old = Track()
        val fresh = Track()
        val events = Events()
        val delivery = ConcurrentLinkedQueue<() -> Unit>()
        val created = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        val player = AiAudioPlayer(events, {
            if (created.incrementAndGet() == 1) old else {
                assertTrue("Two AudioTracks must never overlap on replacement", old.released)
                fresh
            }
        }, { delivery.add(it) }, worker)
        try {
            player.play(AiAudioClip(FloatArray(1000) { .1f }), "old")
            await { old.submitted.get() == 1000 }
            old.head.set(250)
            await { delivery.size >= 2 }
            player.play(AiAudioClip(FloatArray(800) { .2f }), "seek", 300)
            await { fresh.submitted.get() == 500 }
            fresh.head.set(500)
            await { player.state.value.phase == AiPlaybackPhase.COMPLETE }
            while (true) (delivery.poll() ?: break).invoke()
            assertFalse(events.starts.contains("old"))
            assertFalse(events.completed.contains("old"))
            assertTrue(events.completed.contains("seek"))
            assertEquals("seek" to 800, events.positions.last())
        } finally { player.close(); worker.awaitTermination(3, TimeUnit.SECONDS) }
    }

    @Test fun timestampRegressionAndUnavailableFallbackCannotInventPresentedFrames() {
        val clock = AiPresentationClock(24000)
        assertEquals(200L, clock.position(1000, 1000, AiPcmTimestamp(200, 1_000_000_000), 1_000_000_000, 2400))
        assertEquals(200L, clock.position(1000, 1000, AiPcmTimestamp(900, 900_000_000), 1_000_000_000, 2400))
        assertEquals(200L, clock.position(1000, 1000, AiPcmTimestamp(2000, 1_000_000_000), 1_000_000_000, 2400))
        val fallback = AiPresentationClock(44100)
        assertEquals(0L, fallback.position(1000, 1000, null, 1_000_000_000, 4410))
        assertEquals(0L, fallback.position(1000, 1000, null, 1_099_000_000, 4410))
        assertEquals(1000L, fallback.position(1000, 1000, null, 1_100_000_000, 4410))
    }
    @Test fun drainedQueueCannotExtrapolateAnOldTimestampIntoNewSpeech() {
        for (rate in listOf(24000, 44100)) {
            val clock = AiPresentationClock(rate)
            assertEquals(1000L, clock.position(1000, 1000, AiPcmTimestamp(1000, 1_000_000_000), 1_000_000_000, rate / 4))
            // Queue was empty for 200 ms. Its old timestamp must not consume the new 700 frames.
            assertEquals(1000L, clock.position(1700, 1700, AiPcmTimestamp(1000, 1_000_000_000), 1_200_000_000, rate / 4))
            assertEquals(1000L, clock.position(1700, 1700, AiPcmTimestamp(1000, 1_000_000_000), 1_400_000_000, rate / 4))
            assertEquals(1700L, clock.position(1700, 1700, AiPcmTimestamp(1700, 1_450_000_000), 1_450_000_000, rate / 4))
        }
    }
    @Test fun routeOrUnderrunResetRequiresANewAnchorAndRespectsWirelessFallback() {
        val clock = AiPresentationClock(24000)
        assertEquals(200L, clock.position(1000, 1000, AiPcmTimestamp(200, 1_000_000_000), 1_000_000_000, 6000))
        clock.resetTimestamp(1_010_000_000)
        assertEquals(200L, clock.position(1000, 1000, AiPcmTimestamp(200, 1_000_000_000), 1_020_000_000, 24000))
        assertEquals(200L, clock.position(1000, 1000, null, 1_999_000_000, 24000))
        assertEquals(1000L, clock.position(1000, 1000, null, 2_020_000_000, 24000))
    }

    @Test fun timestamplessFifoSubmitsNextBeforeCompleteAndKeepsPresentationCallbacksOrdered() {
        for (rate in listOf(24000, 44100)) for (guardMillis in listOf(250L, 1000L)) {
            val track = Track().apply { safetyFrames = (rate * guardMillis / 1000).toInt() }
            val events = Events(); val time = AtomicLong(1_000_000_000)
            val worker = Executors.newSingleThreadExecutor()
            val player = AiAudioPlayer(events, { track }, { it() }, worker, time::get)
            val next = AiAudioClip(FloatArray(800) { .2f }, rate)
            try {
                player.play(AiAudioClip(FloatArray(1000) { .1f }, rate), "first")
                assertTrue(player.queueNext(next))
                assertTrue("The same queued clip is idempotent", player.queueNext(next))
                await { track.submitted.get() == 1800 }
                assertTrue("Prefetched PCM is written before current presentation completes", events.completed.isEmpty())
                track.head.set(1000)
                Thread.sleep(50)
                assertTrue(events.completed.isEmpty()); assertTrue(events.starts.isEmpty())
                time.addAndGet(guardMillis * 1_000_000)
                await { events.completed.contains("first") }
                assertEquals(listOf("first"), events.starts.toList())
                assertFalse(track.released); assertEquals(1, track.playCalls.get())
                assertTrue(player.append(next, "next"))
                track.head.set(1300)
                Thread.sleep(50)
                assertFalse(events.starts.contains("next"))
                time.addAndGet(guardMillis * 1_000_000)
                await { events.positions.contains("next" to 300) }
                assertEquals(listOf("first", "next"), events.starts.toList())
                assertEquals("Activation must not submit duplicate PCM", 1800, track.submitted.get())
                track.head.set(1800); Thread.sleep(50)
                assertFalse(events.completed.contains("next"))
                time.addAndGet(guardMillis * 1_000_000)
                await { events.completed.contains("next") }
                assertEquals(listOf("first", "next"), events.completed.toList())
                assertTrue(events.errors.isEmpty())
            } finally { player.close(); worker.awaitTermination(3, TimeUnit.SECONDS) }
        }
    }
    @Test fun pauseWithQueuedPcmDropsFutureAndRebuildsOnlyCurrentUnheardSamples() {
        val old = Track(); val fresh = Track(); val created = AtomicInteger()
        val events = Events(); val delivery = ConcurrentLinkedQueue<() -> Unit>()
        val worker = Executors.newSingleThreadExecutor()
        val player = AiAudioPlayer(events, {
            if (created.incrementAndGet() == 1) old else { assertTrue(old.released); fresh }
        }, { delivery.add(it) }, worker)
        try {
            player.play(AiAudioClip(FloatArray(1000) { .1f }), "first")
            assertTrue(player.queueNext(AiAudioClip(FloatArray(800) { .2f })))
            await { old.submitted.get() == 1800 }
            old.head.set(250); await { delivery.size >= 2 }
            assertEquals(250, player.pause())
            await { old.released }
            assertTrue(player.resume("resumed"))
            await { fresh.submitted.get() == 750 }
            assertEquals("Resume includes no previously queued future PCM", 750, fresh.submitted.get())
            fresh.head.set(750); await { player.state.value.phase == AiPlaybackPhase.COMPLETE }
            while (true) (delivery.poll() ?: break).invoke()
            assertEquals(listOf("resumed"), events.starts.toList())
            assertEquals(listOf("resumed"), events.completed.toList())
            assertEquals("resumed" to 1000, events.positions.last())
        } finally { player.close(); worker.awaitTermination(3, TimeUnit.SECONDS) }
    }
    @Test fun replaceDiscardsQueuedFutureAndRejectsMismatchedActivation() {
        val old = Track(); val fresh = Track(); val created = AtomicInteger()
        val events = Events(); val worker = Executors.newSingleThreadExecutor()
        val player = AiAudioPlayer(events, {
            if (created.incrementAndGet() == 1) old else { assertTrue(old.released); fresh }
        }, { it() }, worker)
        val next = AiAudioClip(FloatArray(800) { .2f })
        try {
            player.play(AiAudioClip(FloatArray(1000) { .1f }), "first")
            assertTrue(player.queueNext(next)); await { old.submitted.get() == 1800 }
            old.head.set(1000); await { events.completed.contains("first") }
            assertFalse(player.append(AiAudioClip(next.samples.copyOf()), "wrong-clip"))
            assertFalse(player.append(next, "wrong-offset", 1))
            player.play(AiAudioClip(FloatArray(500) { .1f }), "seek", 100)
            await { fresh.submitted.get() == 400 }
            fresh.head.set(400); await { events.completed.contains("seek") }
            assertFalse(events.starts.contains("wrong-clip")); assertFalse(events.starts.contains("wrong-offset"))
            assertTrue(events.errors.isEmpty())
        } finally { player.close(); worker.awaitTermination(3, TimeUnit.SECONDS) }
    }

    @Test fun queuedContinuationUsesRemainingFramesAfterAResumedClipOffset() {
        val track = Track(); val events = Events(); val worker = Executors.newSingleThreadExecutor()
        val player = AiAudioPlayer(events, { track }, { it() }, worker)
        val next = AiAudioClip(FloatArray(800) { .2f })
        try {
            player.play(AiAudioClip(FloatArray(1200) { .1f }), "resumed", 200)
            assertTrue(player.queueNext(next)); await { track.submitted.get() == 1800 }
            track.head.set(1000); await { events.completed.contains("resumed") }
            assertEquals("resumed" to 1200, events.positions.last())
            assertTrue(player.append(next, "next"))
            track.head.set(1300); await { events.positions.contains("next" to 300) }
            assertEquals(300, player.pause())
            assertTrue(player.resume("next-resumed"))
            track.head.set(1800); await { events.completed.contains("next-resumed") }
            assertEquals("next-resumed" to 800, events.positions.last())
            assertEquals(1800, track.submitted.get())
        } finally { player.close(); worker.awaitTermination(3, TimeUnit.SECONDS) }
    }

    @Test fun livePitchPreservingRateChangesKeepSourceSamplesAndDoNotResubmitPcm() {
        val track=Track(); val events=Events(); val time=AtomicLong(1_000_000_000)
        val worker=Executors.newSingleThreadExecutor()
        val player=AiAudioPlayer(events,{track},{it()},worker,time::get)
        try {
            val samples=FloatArray(48000) { if(it%2==0) .1f else -.1f }
            assertTrue(player.setPlaybackRate(1.5f))
            player.play(AiAudioClip(samples),"speech",1000)
            await { track.submitted.get()==47000 }; assertEquals(1.5f,track.rate,0f)
            track.head.set(1200); track.stamp=AiPcmTimestamp(1200,time.get())
            await { events.positions.contains("speech" to 2200) }
            val submitted=track.submitted.get()
            assertTrue(player.setPlaybackRate(2f)); assertEquals(2f,track.rate,0f)
            assertEquals(1,track.playCalls.get()); assertEquals(0,track.pauseCalls.get())
            time.addAndGet(100_000_000); track.stamp=AiPcmTimestamp(1300,time.get()); track.head.set(2000)
            await { player.state.value.playedSamples>=2300 }
            assertEquals(submitted,track.submitted.get())
            assertEquals(2f,player.state.value.playbackRate,0f)
            val before=player.pause(); assertTrue(before>=2300)
            assertTrue(player.setPlaybackRate(.75f)); assertFalse(track.playing)
            assertTrue(player.resume("resumed")); assertEquals(.75f,track.rate,0f)
            assertEquals(submitted,track.submitted.get())
        } finally { player.close(); worker.awaitTermination(3,TimeUnit.SECONDS) }
    }
    @Test fun speedAwareClockExtrapolatesSourceFramesAndResetsAtRateChanges() {
        val clock=AiPresentationClock(24000)
        clock.setPlaybackRate(1.5f,1_000_000_000)
        assertEquals(1000L,clock.position(10000,10000,AiPcmTimestamp(1000,1_000_000_000),1_000_000_000,6000))
        assertEquals(4600L,clock.position(10000,10000,AiPcmTimestamp(1000,1_000_000_000),1_100_000_000,6000))
        clock.setPlaybackRate(.75f,1_100_000_000)
        assertEquals(4600L,clock.position(10000,10000,AiPcmTimestamp(1000,1_000_000_000),1_150_000_000,6000))
        assertEquals(6000L,clock.position(10000,10000,AiPcmTimestamp(6000,1_150_000_000),1_150_000_000,6000))
        assertEquals(7800L,clock.position(10000,10000,AiPcmTimestamp(6000,1_150_000_000),1_250_000_000,6000))
    }
    @Test fun multipleShortClipsEnterOneFifoBeforeMetadataHandoffsAndStayBounded() {
        val track=Track(); val events=Events(); val worker=Executors.newSingleThreadExecutor()
        val player=AiAudioPlayer(events,{track},{it()},worker)
        try {
            player.play(AiAudioClip(FloatArray(100) { .1f }),"first")
            val clips=List(AiAudioPlayer.MAX_QUEUED_PASSAGES) { AiAudioClip(FloatArray(100) { .1f }) }
            clips.forEach { assertTrue(player.queueNext(it)) }
            assertFalse(player.queueNext(AiAudioClip(FloatArray(100) { .1f })))
            await { track.submitted.get()==3300 }
            assertTrue(events.completed.isEmpty())
            track.head.set(100); await { events.completed.contains("first") }
            for((index,clip) in clips.withIndex()) {
                val id="queued:$index"
                assertTrue(player.append(clip,id)); track.head.set(200+index*100)
                await { events.completed.contains(id) }
                assertEquals(id to 100,events.positions.last())
            }
            assertEquals(1,track.playCalls.get()); assertEquals(3300,track.submitted.get())
            assertEquals(33,events.completed.size); assertTrue(events.errors.isEmpty())
        } finally { player.close(); worker.awaitTermination(3,TimeUnit.SECONDS) }
    }

}
