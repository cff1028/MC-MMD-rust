package com.shiroha.mmdskin.compat.vr;

/** Input state shared by the Vivecraft radial screen and its hold-to-select handler. */
final class VrRadialPageState {
    private boolean mmdPage;
    private boolean awaitingHoldPress;
    private boolean keepOpenAfterNavigation;

    boolean isMmdPage() {
        return mmdPage;
    }

    void changePage(boolean holdMode) {
        mmdPage = !mmdPage;
        awaitingHoldPress = holdMode;
        keepOpenAfterNavigation = holdMode;
    }

    boolean consumeNavigationClose() {
        boolean keepOpen = keepOpenAfterNavigation;
        keepOpenAfterNavigation = false;
        return keepOpen;
    }

    boolean isAwaitingHoldPress() {
        return awaitingHoldPress;
    }

    boolean resumeHoldSelection(boolean showing, boolean hasController, boolean radialKeyDown) {
        if (!showing && hasController && radialKeyDown && awaitingHoldPress) {
            resetInteraction();
            return true;
        }
        return false;
    }

    void resetInteraction() {
        awaitingHoldPress = false;
        keepOpenAfterNavigation = false;
    }
}
