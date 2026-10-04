package com.shiroha.mmdskin.compat.vr.mirror;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class SodiumMirrorBuildQueueTest {
    @Test void initialAndDirtyBuildsAreAcceptedWithoutIntroducingSortWork() {
        assertTrue(SodiumMirrorBuildQueue.isGeometryUpdate("INITIAL_BUILD"));
        assertTrue(SodiumMirrorBuildQueue.isGeometryUpdate("REBUILD"));
        assertTrue(SodiumMirrorBuildQueue.isGeometryUpdate("IMPORTANT_REBUILD"));
        assertFalse(SodiumMirrorBuildQueue.isGeometryUpdate("SORT"));
        assertFalse(SodiumMirrorBuildQueue.isGeometryUpdate("IMPORTANT_SORT"));
        assertFalse(SodiumMirrorBuildQueue.isGeometryUpdate(null));
    }

    @Test void repeatedEyesDeduplicateAndShareTickBudget() {
        var queue = new SodiumMirrorBuildQueue<Object>();
        List<Object> sections = new ArrayList<>();
        for (int i = 0; i < 9; i++) { Object section = new Object(); sections.add(section); queue.offer(section); queue.offer(section); }
        List<Object> submitted = new ArrayList<>();
        assertEquals(9, queue.pendingCount());
        assertEquals(4, queue.drain(7, () -> 0L, section -> true, submitted::add));
        assertEquals(0, queue.drain(7, () -> 1L, section -> true, submitted::add));
        assertEquals(4, queue.drain(8, () -> 2L, section -> true, submitted::add));
        assertEquals(1, queue.drain(9, () -> 3L, section -> true, submitted::add));
        assertEquals(sections, submitted);
    }

    @Test void alreadyBuiltBusyDisposedOrAlreadyQueuedRequestsDoNotConsumeBudget() {
        var queue = new SodiumMirrorBuildQueue<String>();
        for (String state : List.of("built", "busy", "disposed", "alreadyQueued", "missing")) queue.offer(state);
        List<String> submitted = new ArrayList<>();
        assertEquals(1, queue.drain(1, () -> 0L, "missing"::equals, submitted::add));
        assertEquals(List.of("missing"), submitted);
        assertEquals(0, queue.pendingCount());
        queue.offer("busy");
        assertEquals(1, queue.drain(1, () -> 1L, section -> true, submitted::add));
    }

    @Test void slowSubmissionStopsUntilNextTickWithoutLosingRequests() {
        var queue = new SodiumMirrorBuildQueue<String>();
        queue.offer("first"); queue.offer("second");
        AtomicLong clock = new AtomicLong();
        List<String> submitted = new ArrayList<>();
        assertEquals(1, queue.drain(4, clock::get, section -> true, section -> { submitted.add(section); clock.set(2_000_000L); }));
        assertEquals(1, queue.pendingCount());
        assertEquals(1, queue.drain(5, clock::get, section -> true, submitted::add));
        assertEquals(List.of("first", "second"), submitted);
    }

    @Test void requestBacklogIsBoundedWhileMainViewIsNotUpdating() {
        var queue = new SodiumMirrorBuildQueue<Object>();
        List<Object> keepAlive = new ArrayList<>();
        for (int i = 0; i < 5000; i++) { Object section = new Object(); keepAlive.add(section); queue.offer(section); }
        assertEquals(4096, queue.pendingCount());
    }
}
