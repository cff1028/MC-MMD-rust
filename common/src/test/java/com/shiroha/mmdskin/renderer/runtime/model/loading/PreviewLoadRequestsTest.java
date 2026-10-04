package com.shiroha.mmdskin.renderer.runtime.model.loading;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PreviewLoadRequestsTest {
    @Test void closingBeforeLoadCompletionRetainsOwnershipUntilSafeToReclaim() {
        var requests = new PreviewLoadRequests();
        requests.requested("preview", 0);
        List<String> attempts = new ArrayList<>();
        requests.expire(1_999_999_999L, key -> { fail("Still within reuse grace period"); return true; });
        requests.expire(2_000_000_000L, key -> { attempts.add(key); return false; });
        requests.expire(3_000_000_000L, key -> { attempts.add(key); return true; });
        requests.expire(4_000_000_000L, key -> { fail("Already reclaimed"); return true; });
        assertEquals(List.of("preview", "preview"), attempts);
    }

    @Test void reopenRefreshesLeaseAndFinalizedModelStopsPendingCleanup() {
        var requests = new PreviewLoadRequests();
        requests.requested("preview", 0);
        requests.requested("preview", 1_500_000_000L);
        requests.expire(2_000_000_000L, key -> { fail("Open preview cannot expire"); return true; });
        requests.resolved("preview");
        requests.expire(10_000_000_000L, key -> { fail("Cache now owns the finalized model"); return true; });
    }

    @Test void reloadRemovesOnlyItsOwnPendingLeases() {
        var requests = new PreviewLoadRequests();
        requests.requested("modelA_player", 0);
        requests.requested("modelB_other", 0);
        requests.removeMatching(key -> key.endsWith("_player"));
        List<String> remaining = new ArrayList<>();
        requests.expire(2_000_000_000L, key -> { remaining.add(key); return true; });
        assertEquals(List.of("modelB_other"), remaining);
    }
}
