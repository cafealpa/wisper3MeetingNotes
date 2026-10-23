package com.example.groqtranscribe

import org.junit.Assert.*
import org.junit.Test

class MeetingPresentationTest {
    @Test fun filenameTitleDoesNotExposeExtensionAndCompactTimesKeepHours() {
        assertEquals("주간회의 1022", meetingTitle("주간회의_1022.m4a"))
        assertEquals("회의.v2", meetingTitle("회의.v2.mp3"))
        assertEquals("대화 기록", meetingTitle(null))
        assertEquals("00:12", compactTimestamp(12.999))
        assertEquals("1:01:02", compactTimestamp(3662.0))
        assertEquals("00:00", compactTimestamp(Double.NaN))
        assertEquals("12분 38초", meetingDuration(758.0))
        assertNull(meetingDuration(null))
        assertNull(meetingDate(0))
    }
    @Test fun cancellationPreservesCompletedTranscriptAndStopsPendingSteps() {
        val state = MeetingProgress(transcript = StepStatus.COMPLETE, speakers = StepStatus.RUNNING, percent = 42)
        val ended = state.finished(cancelled = true)
        assertFalse(ended.running)
        assertEquals(StepStatus.COMPLETE, ended.transcript)
        assertEquals(StepStatus.CANCELLED, ended.speakers)
        assertEquals(StepStatus.CANCELLED, ended.alignment)
        assertEquals(42, ended.percent)
    }
    @Test fun failedAndSkippedStepsDoNotBecomeSuccess() {
        val failed = MeetingProgress(transcript = StepStatus.COMPLETE, speakers = StepStatus.FAILED).finished(false)
        assertEquals(StepStatus.FAILED, failed.alignment)
        val textOnly = MeetingProgress(transcript = StepStatus.COMPLETE, speakers = StepStatus.SKIPPED, alignment = StepStatus.SKIPPED).finished(false)
        assertEquals(StepStatus.SKIPPED, textOnly.speakers)
        assertEquals(StepStatus.SKIPPED, textOnly.alignment)
    }
}
