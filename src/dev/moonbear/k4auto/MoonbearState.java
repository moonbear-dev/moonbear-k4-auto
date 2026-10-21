package dev.moonbear.k4auto;

/**
 * Process-wide snapshot of the voice session state.
 *
 * The Car App Library screen runs in the same process as MoonbearCarAudioService,
 * so the car UI reads this to render live status without IPC.
 */
public final class MoonbearState {

    public static volatile String state = MoonbearCarAudioService.STATE_IDLE;
    public static volatile String headline = "Ready";
    public static volatile String subline = "Tap to talk";

    private MoonbearState() {}

    public static void publish(String newState, String newHeadline, String newSubline) {
        if (newState != null) state = newState;
        if (newHeadline != null) headline = newHeadline;
        if (newSubline != null) subline = newSubline;
    }

    public static boolean isListening() {
        return MoonbearCarAudioService.STATE_LISTENING.equals(state);
    }

    public static boolean isBusy() {
        return MoonbearCarAudioService.STATE_THINKING.equals(state)
                || MoonbearCarAudioService.STATE_SPEAKING.equals(state);
    }
}
