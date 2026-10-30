package com.google.android.accessibility.talkback;

import static android.accessibilityservice.AccessibilityService.GESTURE_DOUBLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_DOUBLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_DOWN_AND_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_DOWN_AND_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_DOWN_AND_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_LEFT_AND_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_LEFT_AND_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_LEFT_AND_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT_AND_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT_AND_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT_AND_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP_AND_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP_AND_RIGHT;
import static android.accessibilityservice.TouchInteractionController.STATE_CLEAR;
import static android.accessibilityservice.TouchInteractionController.STATE_DELEGATING;
import static android.accessibilityservice.TouchInteractionController.STATE_DRAGGING;
import static android.accessibilityservice.TouchInteractionController.STATE_TOUCH_EXPLORING;
import static android.accessibilityservice.TouchInteractionController.STATE_TOUCH_INTERACTING;
import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_POINTER_DOWN;
import static android.view.MotionEvent.ACTION_UP;
import static android.view.MotionEvent.INVALID_POINTER_ID;
import static com.google.android.accessibility.talkback.PrimesController.TimerAction.TOUCH_EXPLORE_DELAY_100;
import static com.google.android.accessibility.talkback.PrimesController.TimerAction.TOUCH_EXPLORE_DELAY_150;
import static com.google.android.accessibility.talkback.PrimesController.TimerAction.TOUCH_EXPLORE_DELAY_200;
import static com.google.android.accessibility.talkback.PrimesController.TimerAction.TOUCH_EXPLORE_DELAY_250;
import static com.google.android.accessibility.talkback.PrimesController.TimerAction.TOUCH_EXPLORE_DELAY_TYPING_100;
import static com.google.android.accessibility.talkback.PrimesController.TimerAction.TOUCH_EXPLORE_DELAY_TYPING_150;
import static com.google.android.accessibility.talkback.PrimesController.TimerAction.TOUCH_EXPLORE_DELAY_TYPING_200;
import static com.google.android.accessibility.talkback.PrimesController.TimerAction.TOUCH_EXPLORE_DELAY_TYPING_250;
import static com.google.android.accessibility.utils.gestures.GestureAnalyticsEvent.EVENT_TAP_TO_TOUCH_EXPLORE;
import static com.google.android.accessibility.utils.gestures.GestureManifold.GESTURE_2_FINGER_ROTATE_CLOCKWISE;
import static com.google.android.accessibility.utils.gestures.GestureManifold.GESTURE_2_FINGER_ROTATE_COUNTERCLOCKWISE;
import static com.google.android.accessibility.utils.gestures.GestureManifold.GESTURE_FAKED_SPLIT_TYPING;
import static com.google.android.accessibility.utils.gestures.GestureManifold.GESTURE_FAKED_SPLIT_TYPING_AND_HOLD;
import static com.google.android.accessibility.utils.gestures.GestureManifold.GESTURE_TAP_UP_TOUCH_EXPLORE;
import static com.google.android.accessibility.utils.gestures.GestureManifold.GESTURE_TOUCH_EXPLORE;

import android.accessibilityservice.AccessibilityGestureEvent;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.TouchInteractionController;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.PrimesController.TimerAction;
import com.google.android.accessibility.talkback.actor.DirectClick;
import com.google.android.accessibility.talkback.contextmenu.ListMenuManager;
import com.google.android.accessibility.talkback.flags.FeatureFlagReader;
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration;
import com.google.android.accessibility.talkback.gesture.Edge;
import com.google.android.accessibility.talkback.gesture.EdgeGesture;
import com.google.android.accessibility.talkback.gesture.EdgeGestureSettings;
import com.google.android.accessibility.talkback.gesture.EdgeMenu;
import com.google.android.accessibility.talkback.gesture.EdgeMenuTouch;
import com.google.android.accessibility.talkback.gesture.ScreenPoint;
import com.google.android.accessibility.talkback.gesture.TwoFingerRotationTracker;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.AccessibilityServiceCompatUtils;
import com.google.android.accessibility.utils.AccessibilityWindowInfoUtils;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.Performance;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.gestures.GestureConfiguration;
import com.google.android.accessibility.utils.gestures.GestureManifold;
import com.google.android.accessibility.utils.gestures.GestureManifold.GestureConfigProvider;
import com.google.android.accessibility.utils.gestures.GestureMatcher.AnalyticsEventLogger;
import com.google.android.accessibility.utils.gestures.GestureUtils;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import com.google.common.collect.EvictingQueue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * This class receives motion events from the framework for the purposes of figuring out whether an
 * interaction is a gesture, touch exploration, or passthrough . If the gesture detector clasifies
 * an interaction as a gesture this class will relay that back to the service. If an interaction
 * qualifies as touch exploration or a passthrough this class will relay that to the framework.
 *
 * <p>All of this runs on the service's main thread: the controller calls this class there as each
 * motion event and state change comes in, and the gesture matchers and the touch exploration delay
 * count their timeouts there too. Android passes motion events to the service through its main
 * thread anyway, so another thread would not get them sooner, and a timeout counted there could run
 * out before an event that came in earlier but was still waiting for the busy main thread.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class TouchInteractionMonitor
    implements TouchInteractionController.Callback,
        GestureManifold.Listener,
        ComponentCallbacks,
        UserInterface.UserInputEventListener {
  private static final String LOG_TAG = "TouchInteractionMonitor";
  private static final String DEBUG_GESTURE_TAG = "BacktalkGesture";
  private static final float MAX_DRAGGING_ANGLE_COS = 0.525321989f; // cos(pi/4)
  // The height of the top and bottom edges for  edge-swipes.
  // For now this is only used to allow three-finger edge-swipes from the bottom.
  private static final float EDGE_SWIPE_HEIGHT_CM = 0.25f;

  // Queue size to hold the most recent change requests of controller state.
  private static final int MAX_CHANGE_REQUEST_SIZE = 5;
  /** Time after a swipe's start limit for its last motion event to arrive. */
  private static final int SWIPE_START_MARGIN_MS = 10;

  private static final ImmutableSet<Integer> TOUCH_EXPLORE_GATE =
      ImmutableSet.of(
          GESTURE_DOUBLE_TAP,
          GESTURE_DOUBLE_TAP_AND_HOLD,
          GESTURE_SWIPE_RIGHT,
          GESTURE_SWIPE_LEFT,
          GESTURE_SWIPE_UP,
          GESTURE_SWIPE_DOWN,
          GESTURE_SWIPE_LEFT_AND_RIGHT,
          GESTURE_SWIPE_LEFT_AND_UP,
          GESTURE_SWIPE_LEFT_AND_DOWN,
          GESTURE_SWIPE_RIGHT_AND_UP,
          GESTURE_SWIPE_RIGHT_AND_DOWN,
          GESTURE_SWIPE_RIGHT_AND_LEFT,
          GESTURE_SWIPE_DOWN_AND_UP,
          GESTURE_SWIPE_DOWN_AND_LEFT,
          GESTURE_SWIPE_DOWN_AND_RIGHT,
          GESTURE_SWIPE_UP_AND_DOWN,
          GESTURE_SWIPE_UP_AND_LEFT,
          GESTURE_SWIPE_UP_AND_RIGHT);

  private boolean stateInActive = true;
  private int state;
  private int previousState;
  private final TouchInteractionController controller;
  private Context context;
  private final Display display;
  private final ReceivedPointerTracker receivedPointerTracker;
  private int draggingPointerId = INVALID_POINTER_ID;
  private final TalkBackService service;
  private final Handler mainHandler;
  private final GestureManifold gestureDetector;
  private boolean gestureStarted = false;
  // Whether a gesture was performed while fingers were still down, such as a double tap and hold,
  // so that the rest of the touch follows the circle menu instead of being a gesture. The offset
  // keeps the followed point from jumping when one of the fingers lifts.
  private boolean followingHeldTouch = false;
  private float heldTouchOffsetX;
  private float heldTouchOffsetY;

  // Whether a finger is on the screen, from the raw touch, for any thread. Unlike the touch
  // interaction, it ends as soon as the last finger lifts, not after gesture detection gives up.
  private static volatile boolean fingerDown = false;

  /** Returns whether a finger is on the screen, as far as gesture detection has seen. */
  public static boolean isFingerDown() {
    return fingerDown;
  }

  // Direct click on the main display, whose presses are neither a finger nor a gesture, and which
  // gesture detection hands to the app.
  private @Nullable DirectClick directClick;
  private final BooleanSupplier directClickHandOver = this::handOverDirectClick;
  // A gesture was dispatched before the controller cleared the interaction. The rest of that
  // gesture's events cancel the gesture matchers, so a new first finger down in the same
  // interaction must clear them again.
  private boolean clearGestureDetectorOnNextDown = false;
  // Recognizes two fingers turning around each other. A recognized rotation owns the whole touch.
  private final TwoFingerRotationTracker rotationTracker;
  // The edge that the current touch started on, if edge gestures are on, or null.
  private @Nullable Edge touchEdge;
  // The hidden menu of an edge, which owns the touch from when the finger slides toward the middle.
  private final EdgeMenu edgeMenu;
  // The finger rests on an edge with a menu. Touch exploration would stop the motion events the
  // menu needs, so this monitor keeps the touch until the finger slides inward or along the edge.
  private boolean edgeRested = false;
  // Where the finger rested on the edge, and how many fingers went down in this interaction.
  private float edgeRestX;
  private float edgeRestY;
  private float lastTouchX;
  private float lastTouchY;
  private int downsInInteraction = 0;
  // Read again on each touch, as the user may change it.
  private float edgeWidthPixels;
  private final float pixelsPerCm;
  private final float edgeMenuMaxStepPixels;
  private final float edgeMenuStartPixels;
  // A double tap on an edge whose triple tap has an action waits for a third tap. While it waits,
  // the double tap is kept here and a third tap close to it is taken by this monitor.
  private static final int THIRD_TAP_NONE = 0;
  private static final int THIRD_TAP_WAITING = 1;
  private static final int THIRD_TAP_DOWN = 2;
  // The third touch was no tap, so the double tap went ahead; ignore the rest of the touch.
  private static final int THIRD_TAP_SWALLOWING = 3;
  private int thirdTapState = THIRD_TAP_NONE;
  private @Nullable Edge pendingTripleTapEdge;
  private float pendingTapX;
  private float pendingTapY;
  private long thirdTapDownTime;
  private final int doubleTapSlop;
  private final int tapSlop;
  private final Runnable pendingDoubleTapTimeout = this::flushPendingDoubleTap;
  // Whether double tap and double tap and hold will be dispatched to the service or handled in
  // the framework.
  private boolean serviceHandlesDoubleTap = false;
  // The acceptable distance the pointer can move and still count as a tap.
  private final int passthroughTotalSlop;
  // The calculated edge height for the top and bottom edges.
  private final float edgeSwipeHeightPixels;
  // The time we take to determine what the user is doing.
  // We reduce it by 50 ms in order that touch exploration start doesn't arrive in the framework
  // after the finger has been lifted.
  // This happens because of the time overhead of IPCs.
  private final int determineUserIntentTimeout = GestureConfiguration.DOUBLE_TAP_TIMEOUT_MS;
  /** How long after touch down a one-finger swipe can start: the swipe matchers' start limit. */
  private final int swipeStartWindowMs;
  private int typingFocusUserIntentTimeout;
  private int touchFocusUserIntentTimeout;

  private final RequestTouchExplorationDelayed requestTouchExplorationDelayed;
  // A list of motion events that should be queued until a pending transition has taken place.
  private final Queue<MotionEvent> queuedMotionEvents = new LinkedList<>();
  // Whether this monitor is waiting for a state transition.
  // Motion events will be queued and sent to listeners after the transition has taken place.
  private boolean stateChangeRequested = false;
  // This is used to monitor whether all 1-finger gesture detectors have failed(cancelled).
  // If the condition met, we can request touch control for touch explore state transition.
  // Before it happens, if
  // 1. 2 or more fingers were detected.
  // 2. Any (1-finger) gesture's detected.
  // 3. Has done requestTouchExploration.
  // Then the monitor is off.
  private boolean keepMonitorTouchExplore = true;
  // To record any 1-finger gestures are still trying to match.
  private final Set<Integer> touchExploreGate = new HashSet<>(32);
  // To enable the MotionEvent log, issue the following command.
  // adb shell setprop log.tag.MotionEventLog VERBOSE
  // and re-enable TalkBack
  private final boolean logMotionEvent;
  // In general, the 1st MotionEvent#ACTION_DOWN after entering STATE_TOUCH_INTERACTING is the
  // firstDownTime.
  private boolean waitFirstMotionEvent;
  private EventId eventId;
  private final int displayId;
  private final PrimesController primesController;
  private final Queue<CallerInfo> callerInfos;
  private int latestDensityDpi;
  private final AnalyticsEventLogger logger;
  private boolean isLiftToType;
  private final SharedPreferences sharedPreferences;
  @VisibleForTesting final TouchExplorationModeFailureReporter touchExplorationModeFailureReporter;
  private final boolean typingFocusTimeout;
  private final boolean touchFocusTimeout;
  private int typingMethod;
  TouchInteractionIdlePerformer idlePerformer;
  // To keep the TouchExplore state change request;
  // 1. Whether a change request is pending: waitToChange
  // 2. The requested state is either on or off: queuedState
  boolean queuedState;
  boolean waitToChange;

  /**
   * Interface for {@link TouchInteractionMonitor} to notify a listener when the {@link
   * TouchInteractionController} is idle, allowing for state changes that should only occur at that
   * time.
   */
  public interface TouchInteractionIdlePerformer {
    /**
     * Enables or disables touch exploration.
     *
     * <p>This method is called when the {@link TouchInteractionController} is in the {@link
     * TouchInteractionController#STATE_CLEAR} state.
     *
     * @param state {@code true} to enable touch exploration, {@code false} to disable.
     */
    void provisionTouchExploreFeature(boolean state);
  }

  @Override
  public void onConfigurationChanged(@NonNull Configuration newConfig) {
    if (newConfig.densityDpi != latestDensityDpi) {
      // The display context should be recreated after display density changed. While the original
      // component callback is registered by the old context, it's more safer to register the
      // component callback by the new display context.
      context.unregisterComponentCallbacks(this);
      context = service.createDisplayContext(display);
      context.registerComponentCallbacks(this);
      latestDensityDpi = newConfig.densityDpi;
      gestureDetector.onConfigurationChanged(context);
    }
  }

  @Override
  public void onLowMemory() {}

  public void onHoverEntered() {
    touchExplorationModeFailureReporter.onHoverEntered();
  }

  @Override
  public void newItemFocused(
      AccessibilityNodeInfo nodeInfo, Interpretation.AccessibilityFocused axFocused) {
    if (nodeInfo == null || !FeatureFlagReader.typingFocusTimeout(context)) {
      setSupportLiftToType(/* isLiftToType= */ false);
      return;
    }
    setSupportLiftToType(supportsLiftToType(AccessibilityNodeInfoCompat.wrap(nodeInfo)));
  }

  private boolean supportsLiftToType(AccessibilityNodeInfoCompat accessibilityNodeInfoCompat) {
    if (!AccessibilityWindowInfoUtils.isImeWindow(
        AccessibilityNodeInfoUtils.getWindow(accessibilityNodeInfoCompat))) {
      return false;
    }

    return FocusProcessorForTapAndTouchExploration.liftsToType(
        typingMethod, accessibilityNodeInfoCompat);
  }
  private static class CallerInfo {
    final int state;
    final String caller;
    final long thread;

    CallerInfo(int state, String caller, long thread) {
      this.state = state;
      this.caller = caller;
      this.thread = thread;
    }

    @Override
    public String toString() {
      return "state:" + state + ", caller:" + caller + ", thread:" + thread;
    }
  }

  public TouchInteractionMonitor(
      Display display,
      SharedPreferences prefs,
      TouchInteractionController controller,
      TalkBackService service,
      PrimesController primesController,
      TouchExplorationModeFailureReporter touchExplorationModeFailureReporter,
      TouchInteractionIdlePerformer idlePerformer,
      AnalyticsEventLogger logger) {
    context = service.createDisplayContext(display);
    latestDensityDpi = context.getResources().getConfiguration().densityDpi;
    this.display = display;
    context.registerComponentCallbacks(this);
    this.controller = controller;
    receivedPointerTracker = new ReceivedPointerTracker();
    this.service = service;
    this.primesController = primesController;
    this.idlePerformer = idlePerformer;
    this.logger = logger;
    displayId = context.getDisplay().getDisplayId();
    mainHandler = new Handler(context.getMainLooper());
    this.touchExplorationModeFailureReporter = touchExplorationModeFailureReporter;
    gestureDetector =
        new GestureManifold(
            context,
            this,
            new GestureConfigProvider() {
              @Override
              public float getDoubleTapSlopMultiplier() {
                return FeatureFlagReader.gestureDoubleTapSlopMultiplier(service);
              }

              @Override
              public boolean getSpeedUpTouchExploreState() {
                return FeatureFlagReader.speedUpTouchExploreState(service);
              }

              @Override
              public boolean invalidSwipeGestureEarlyDetection() {
                return FeatureFlagReader.invalidSwipeGestureEarlyDetection(service);
              }

              @Override
              public boolean useMultipleGestureSet() {
                return FeatureFlagReader.useMultipleGestureSet(service);
              }

              @Override
              public boolean enableSplitTapAndHold() {
                return FeatureFlagReader.enableSplitTapAndHold(service);
              }
            },
            logger,
            displayId,
            ImmutableList.copyOf(
                context.getResources().getStringArray(R.array.service_detected_gesture_list)));
    int touchSlop =
        ViewConfiguration.get(context).getScaledTouchSlop()
            * context.getResources().getInteger(R.integer.config_slop_default_multiplier);
    int passthroughSlopMultiplier =
        context.getResources().getInteger(R.integer.config_passthrough_slop_multiplier);
    passthroughTotalSlop = passthroughSlopMultiplier * touchSlop;
    rotationTracker =
        new TwoFingerRotationTracker(passthroughTotalSlop, touchSlop, this::onRotationStep);
    tapSlop = touchSlop;
    doubleTapSlop =
        (int)
            (ViewConfiguration.get(context).getScaledDoubleTapSlop()
                * FeatureFlagReader.gestureDoubleTapSlopMultiplier(service));
    edgeMenu =
        new EdgeMenu(
            new EdgeMenu.Listener() {
              @Override
              public void onItemFocused(@NonNull String action) {
                mainHandler.post(() -> service.onEdgeMenuItem(action));
              }

              @Override
              public void onCancelled() {
                mainHandler.post(() -> service.onEdgeMenuItem(null));
              }

              @Override
              public void onItemChosen(@NonNull String action) {
                mainHandler.post(() -> service.performEdgeAction(action));
              }
            });
    sharedPreferences = prefs;
    typingFocusTimeout = FeatureFlagReader.typingFocusTimeout(service);
    touchFocusTimeout = FeatureFlagReader.touchFocusTimeout(service);

    typingFocusUserIntentTimeout =
        SharedPreferencesUtils.getIntFromStringPref(
                prefs,
                context.getResources(),
                R.string.pref_typing_focus_time_out_key,
                R.string.pref_touch_explore_time_out_default)
            - 50;
    LogUtils.v(LOG_TAG, "IME Touch explore time out value:%d", typingFocusUserIntentTimeout);
    touchFocusUserIntentTimeout =
        SharedPreferencesUtils.getIntFromStringPref(
                prefs,
                context.getResources(),
                R.string.pref_touch_focus_time_out_key,
                R.string.pref_touch_focus_time_out_default)
            - 50;

    LogUtils.v(LOG_TAG, "Non IME Touch focus time out value:%d", touchFocusUserIntentTimeout);
    updateMultiTapTimeout(prefs);
    requestTouchExplorationDelayed = new RequestTouchExplorationDelayed(determineUserIntentTimeout);
    swipeStartWindowMs =
        (int)
                (context
                        .getResources()
                        .getInteger(
                            com.google.android.accessibility.utils.R.integer
                                .config_max_time_to_start_swipe_ms_per_cm)
                    * context
                        .getResources()
                        .getFloat(
                            com.google.android.accessibility.utils.R.dimen
                                .config_gesture_confirm_distance_cm))
            + SWIPE_START_MARGIN_MS;
    DisplayMetrics metrics = context.getResources().getDisplayMetrics();
    edgeSwipeHeightPixels = metrics.ydpi / GestureUtils.CM_PER_INCH * EDGE_SWIPE_HEIGHT_CM;
    pixelsPerCm = metrics.xdpi / GestureUtils.CM_PER_INCH;
    edgeWidthPixels = pixelsPerCm * EdgeGestureSettings.widthCm(prefs);
    edgeMenuMaxStepPixels = pixelsPerCm * EdgeGestureSettings.MAX_MENU_STEP_CM;
    edgeMenuStartPixels = pixelsPerCm * EdgeGestureSettings.MENU_START_CM;

    LogUtils.v(LOG_TAG, "Touch Slop: %s", touchSlop);
    previousState = STATE_CLEAR;
    logMotionEvent = Log.isLoggable("MotionEventLog", Log.VERBOSE);
    if (logMotionEvent) {
      LogUtils.v(LOG_TAG, "MotionEventLog is VERBOSE");
      gestureDetector.enableLogMotionEvent();
    }
    if (typingFocusTimeout || touchFocusTimeout) {
      prefs.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
    }
    callerInfos = EvictingQueue.create(MAX_CHANGE_REQUEST_SIZE);
    typingMethod =
        SharedPreferencesUtils.getIntFromStringPref(
            prefs,
            context.getResources(),
            R.string.pref_typing_confirmation_key,
            R.string.pref_typing_confirmation_default);
    clear();
  }

  /**
   * When requesting to change the Touch Explore feature flag, we must coordinate with the state of
   * TouchInteractionController.
   *
   * @param enabled the requested state of Touch Explore status.
   */
  public void requestA11yTouchExploreState(boolean enabled) {
    if (enabled || state == STATE_CLEAR) {
      waitToChange = false;
      idlePerformer.provisionTouchExploreFeature(enabled);
    } else {
      waitToChange = true;
      queuedState = enabled;
    }
  }

  public void stop() {
    stateInActive = false;
    mainHandler.removeCallbacks(pendingDoubleTapTimeout);
    thirdTapState = THIRD_TAP_NONE;
    edgeMenu.clear();
    edgeRested = false;
    context.unregisterComponentCallbacks(this);
    if (typingFocusTimeout || touchFocusTimeout) {
      sharedPreferences.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
    }
  }

  private void updateMultiTapTimeout(SharedPreferences prefs) {
    GestureConfiguration.setMultiTapTimeoutMs(
        SharedPreferencesUtils.getIntFromStringPref(
            prefs,
            context.getResources(),
            R.string.pref_multi_tap_timeout_key,
            R.string.pref_multi_tap_timeout_default));
  }

  private final OnSharedPreferenceChangeListener sharedPreferenceChangeListener =
      (prefs, key) -> {
        if (context.getString(R.string.pref_typing_focus_time_out_key).equals(key)) {
          typingFocusUserIntentTimeout =
              SharedPreferencesUtils.getIntFromStringPref(
                      prefs,
                      context.getResources(),
                      R.string.pref_typing_focus_time_out_key,
                      R.string.pref_touch_explore_time_out_default)
                  - 50;
        } else if (context.getString(R.string.pref_touch_focus_time_out_key).equals(key)) {
          touchFocusUserIntentTimeout =
              SharedPreferencesUtils.getIntFromStringPref(
                      prefs,
                      context.getResources(),
                      R.string.pref_touch_focus_time_out_key,
                      R.string.pref_touch_focus_time_out_default)
                  - 50;
        } else if (context.getString(R.string.pref_multi_tap_timeout_key).equals(key)) {
          updateMultiTapTimeout(prefs);
        } else if (context.getString(R.string.pref_typing_confirmation_key).equals(key)) {
          typingMethod =
              SharedPreferencesUtils.getIntFromStringPref(
                  prefs,
                  context.getResources(),
                  R.string.pref_typing_confirmation_key,
                  R.string.pref_typing_confirmation_default);
        }
      };

  @MainThread
  @SuppressWarnings("Override")
  @Override
  public void onMotionEvent(MotionEvent event) {
    if (event != null
        && directClick != null
        && directClick.claim(event, directClickHandOver)) {
      return;
    }
    if (event != null) {
      int action = event.getActionMasked();
      if (action == ACTION_DOWN) {
        fingerDown = true;
        // A new touch: touch exploration may handle it again, whatever the last one was.
        EdgeMenuTouch.setOwned(false);
      } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
        fingerDown = false;
      }
    }
    if (event != null) {
      if (logMotionEvent) {
        LogUtils.v(LOG_TAG, "Received motion event : %s", event.toString());
      }
    } else {
      LogUtils.e(LOG_TAG, "Event is null.");
      return;
    }
    if (handleThirdTap(event)) {
      return;
    }
    if (followingHeldTouch) {
      followHeldTouch(event);
      return;
    }
    if (event.getActionMasked() == ACTION_POINTER_DOWN) {
      keepMonitorTouchExplore = false;
    }
    if (stateChangeRequested) {
      queuedMotionEvents.add(event);
      return;
    }
    receivedPointerTracker.onMotionEvent(event);
    if (handleEdgeMenu(event)) {
      return;
    }
    if (handleRotation(event)) {
      return;
    }
    if (shouldPerformGestureDetection()) {
      if (waitFirstMotionEvent && event.getActionMasked() == ACTION_POINTER_DOWN) {
        // The split-typing gesture expect no action-down when re-entering the touch exploration
        // state. This must be placed before the dispatching the event to gesture detectors.
        eventId = Performance.getInstance().onGestureEventReceived(displayId, event);
        waitFirstMotionEvent = false;
      }
      if (clearGestureDetectorOnNextDown && event.getActionMasked() == ACTION_DOWN) {
        if (LogUtils.shouldLog(Log.DEBUG)) {
          Log.d(DEBUG_GESTURE_TAG, "New touch after a dispatched gesture, clearing matchers");
        }
        clearGestureDetectorOnNextDown = false;
        gestureDetector.clear();
        eventId = Performance.getInstance().onGestureEventReceived(displayId, event);
      }
      gestureDetector.onMotionEvent(eventId, event);
    }
    if (!gestureStarted) {
      switch (state) {
        case STATE_TOUCH_INTERACTING -> handleMotionEventStateTouchInteracting(event);
        case STATE_DRAGGING -> handleMotionEventStateDragging(event);
        default -> {}
      }
    }
  }

  public void handleMotionEventStateTouchInteracting(MotionEvent event) {
    switch (event.getActionMasked()) {
      case ACTION_DOWN -> {
        if (waitFirstMotionEvent) {
          eventId = Performance.getInstance().onGestureEventReceived(displayId, event);
          waitFirstMotionEvent = false;
        }
        if (requestTouchExplorationDelayed.isPending()) {
          // The touch explore delay timer should be restart each time received Action-down event.
          // For multi-tap gestures, system will detect the ACTION_DOWN events multiple times. If
          // their is already a pending runnable and starting a new one, the older one will be
          // executed earlier than we expected.
          requestTouchExplorationDelayed.cancel();
        }
        requestTouchExplorationDelayed.post();
      }
      case ACTION_MOVE -> {
        switch (event.getPointerCount()) {
          case 1:
            // Do nothing. Touch exploration will fire on a delay.
            break;
          case 2:
            if (gestureDetector.isTwoFingerPassthroughEnabled()) {
              // Each finger must move passthroughTotalSlop (3x touchSlop) before the touch goes
              // to the app. MultiFingerSwipe lets the first fingers of a 3-finger swipe move up to
              // fingerCount x touchSlop (the same touchSlop) before the last finger lands, and
              // only cancels itself once a finger moves that far with too few fingers down; a
              // 3-finger tap (MultiFingerMultiTap) likewise allows 3x touchSlop. Dragging stops
              // gesture detection, so passing the touch on any sooner would turn those gestures
              // into scrolls.
              // A scroll's slower finger can lag well behind, though. So once both fingers have
              // moved the same way (rotationTracker.isScroll()) and the faster one has moved
              // passthroughTotalSlop, by which point the 3-finger swipe has already cancelled
              // itself (the gesture detector saw this event first), the touch is a scroll: pass
              // it on without waiting for the slower finger.
              double maxMoveDelta = 0;
              double minMoveDelta = Double.MAX_VALUE;
              for (int index = 0; index < event.getPointerCount(); ++index) {
                int id = event.getPointerId(index);
                if (!receivedPointerTracker.isReceivedPointerDown(id)) {
                  // Something is wrong with the event stream.
                  LogUtils.e(LOG_TAG, "Invalid pointer id: %d", id);
                  return;
                }
                final float deltaX =
                    receivedPointerTracker.getReceivedPointerDownX(id) - event.getX(index);
                final float deltaY =
                    receivedPointerTracker.getReceivedPointerDownY(id) - event.getY(index);
                final double moveDelta = Math.hypot(deltaX, deltaY);
                maxMoveDelta = Math.max(maxMoveDelta, moveDelta);
                minMoveDelta = Math.min(minMoveDelta, moveDelta);
              }
              boolean scrollPastSwipeSlop =
                  rotationTracker.isScroll() && maxMoveDelta >= passthroughTotalSlop;
              if (minMoveDelta < passthroughTotalSlop && !scrollPastSwipeSlop) {
                return;
              }
            }
            if (rotationTracker.isPossibleRotation()) {
              // The fingers are turning around each other. Wait until it is clear whether this is
              // a rotation before scrolling or passing the touch to the app.
              return;
            }
            if (isDraggingGesture(event)) {
              computeDraggingPointerIdIfNeeded(event);
              requestDragging(draggingPointerId, "handleMotionEventStateTouchInteracting");
            } else {
              requestDelegating("handleMotionEventStateTouchInteracting-2-points");
            }
            break;
          case 3:
            if (allPointersDownOnBottomEdge(event)) {
              requestDelegating("handleMotionEventStateTouchInteracting-3-points");
            }
            break;
          default:
            break;
        }
      }
      case ACTION_POINTER_DOWN -> requestTouchExplorationDelayed.cancel();
      default -> {}
    }
  }

  public void handleMotionEventStateDragging(MotionEvent event) {
    switch (event.getActionMasked()) {
      case ACTION_MOVE -> {
        if (draggingPointerId == INVALID_POINTER_ID) {
          break;
        }
        switch (event.getPointerCount()) {
          case 1:
            // do nothing
            break;
          case 2:
            if (isDraggingGesture(event)) {
              // Do nothing. The system will continue the drag on its own.
            } else {
              // The two pointers are moving either in different directions or
              // no close enough => delegate the gesture to the view hierarchy.
              requestDelegating("handleMotionEventStateDragging-2-points");
            }
            break;
          default:
            if (!gestureDetector.isMultiFingerGesturesEnabled()) {
              requestDelegating("handleMotionEventStateDragging-3-points");
            }
        }
      }
      default -> {}
    }
  }

  @MainThread
  @SuppressWarnings("Override")
  @Override
  public void onStateChanged(int state) {
    if (LogUtils.shouldLog(Log.DEBUG)) {
      Log.d(
          DEBUG_GESTURE_TAG,
          TouchInteractionController.stateToString(this.state)
              + " -> "
              + TouchInteractionController.stateToString(state));
    }
    LogUtils.v(
        LOG_TAG,
        "%s -> %s",
        TouchInteractionController.stateToString(this.state),
        TouchInteractionController.stateToString(state));
    touchExplorationModeFailureReporter.onTouchStateChanged(state);
    if (this.state == STATE_CLEAR) {
      // Clear on transition to a new interaction
      clear();
    }
    if (state == STATE_TOUCH_INTERACTING) {
      waitFirstMotionEvent = true;
    } else if (state == STATE_DRAGGING) {
      service.onDragStarted();
    } else if (state == STATE_TOUCH_EXPLORING) {
      // Log isDefaultDisplay/gestureId/onGestureDetectedTime. The targetGestureTimeout is the
      // current time minus lastMotionEventTransmissionLatency
      Performance.getInstance().onGestureRecognized(eventId, GESTURE_TOUCH_EXPLORE);
      waitFirstMotionEvent = true;
    } else if (state == STATE_CLEAR) {
      // No finger is down once the touch has ended. A touch that Android hands straight to the
      // app, such as one in direct touch's pass-through region, reaches the service as a down
      // without its lift, so the lift can't be what says so.
      fingerDown = false;
      if (waitToChange) {
        // If there's a pending Touch Explore feature change request, this is the right time to do.
        idlePerformer.provisionTouchExploreFeature(queuedState);
        waitToChange = false;
      }
    }

    previousState = this.state;
    this.state = state;
    requestTouchExplorationDelayed.cancel();
    stateChangeRequested = false;
    if (shouldReceiveQueuedMotionEvents()) {
      while (!stateChangeRequested && !queuedMotionEvents.isEmpty()) {
        // When the onMotionEvent involve the request of gesture controller's state change, we stop
        // popping the event from the queue. Otherwise, it may cause infinite loop of onMotionEvent
        // callback.
        onMotionEvent(queuedMotionEvents.poll());
      }
    } else {
      queuedMotionEvents.clear();
    }
  }

  private void setSupportLiftToType(boolean isLiftToType) {
    LogUtils.d(LOG_TAG, "setSupportLiftToType/focused on Lift-to-type node:%B", isLiftToType);
    this.isLiftToType = isLiftToType;
  }

  private void clear() {
    gestureStarted = false;
    downsInInteraction = 0;
    clearGestureDetectorOnNextDown = false;
    rotationTracker.clear();
    stateChangeRequested = false;
    gestureDetector.clear();
    receivedPointerTracker.clear();
    requestTouchExplorationDelayed.cancel();
    queuedMotionEvents.clear();
    touchExploreGate.addAll(TOUCH_EXPLORE_GATE);
    keepMonitorTouchExplore = true;
    waitFirstMotionEvent = false;
    if (eventId != null) {
      Performance.getInstance().onGestureDetectionStopped(eventId);
      eventId = null;
    }
  }

  private boolean allPointersDownOnBottomEdge(MotionEvent event) {
    final long screenHeight = context.getResources().getDisplayMetrics().heightPixels;
    for (int i = 0; i < event.getPointerCount(); ++i) {
      final int pointerId = event.getPointerId(i);
      final float pointerDownY = receivedPointerTracker.getReceivedPointerDownY(pointerId);
      if (pointerDownY < (screenHeight - edgeSwipeHeightPixels)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Computes {@link #draggingPointerId} if it is invalid. The pointer will be the finger closet to
   * an edge of the screen.
   */
  private void computeDraggingPointerIdIfNeeded(MotionEvent event) {
    if (draggingPointerId != INVALID_POINTER_ID) {
      // If we have a valid pointer ID, we should be good
      final int pointerIndex = event.findPointerIndex(draggingPointerId);
      if (event.findPointerIndex(pointerIndex) >= 0) {
        return;
      }
    }
    LogUtils.v(LOG_TAG, "computeDraggingPointerIdIfNeeded/MotionEvent:%s", event);
    // Use the pointer that is closest to its closest edge.
    final float firstPtrX = event.getX(0);
    final float firstPtrY = event.getY(0);
    final int firstPtrId = event.getPointerId(0);
    final float secondPtrX = event.getX(1);
    final float secondPtrY = event.getY(1);
    final int secondPtrId = event.getPointerId(1);
    draggingPointerId =
        (getDistanceToClosestEdge(firstPtrX, firstPtrY)
                < getDistanceToClosestEdge(secondPtrX, secondPtrY))
            ? firstPtrId
            : secondPtrId;
    if (draggingPointerId < 0) {
      LogUtils.e(
          LOG_TAG,
          "computeDraggingPointerIdIfNeeded: incorrect dragging pointer of event:%s",
          event);
    }
  }

  private float getDistanceToClosestEdge(float x, float y) {
    final long width = this.context.getResources().getDisplayMetrics().widthPixels;
    final long height = this.context.getResources().getDisplayMetrics().heightPixels;
    float distance = Float.MAX_VALUE;
    if (x < (width - x)) {
      distance = x;
    } else {
      distance = width - x;
    }
    if (distance > y) {
      distance = y;
    }
    if (distance > (height - y)) {
      distance = (height - y);
    }
    return distance;
  }
  /**
   * Determines whether a two pointer gesture is a dragging one.
   *
   * @param event The event with the pointer data.
   * @return True if the gesture is a dragging one.
   */
  private boolean isDraggingGesture(MotionEvent event) {

    final float firstPtrX = event.getX(0);
    final float firstPtrY = event.getY(0);
    final float secondPtrX = event.getX(1);
    final float secondPtrY = event.getY(1);

    final float firstPtrDownX = receivedPointerTracker.getReceivedPointerDownX(0);
    final float firstPtrDownY = receivedPointerTracker.getReceivedPointerDownY(0);
    final float secondPtrDownX = receivedPointerTracker.getReceivedPointerDownX(1);
    final float secondPtrDownY = receivedPointerTracker.getReceivedPointerDownY(1);

    return GestureUtils.isDraggingGesture(
        firstPtrDownX,
        firstPtrDownY,
        secondPtrDownX,
        secondPtrDownY,
        firstPtrX,
        firstPtrY,
        secondPtrX,
        secondPtrY,
        MAX_DRAGGING_ANGLE_COS);
  }
  /** This class tracks where and when a pointer went down. It does not track its movement. */
  class ReceivedPointerTracker {

    private final PointerDownInfo[] mReceivedPointers;

    // Which pointers are down.
    private int mReceivedPointersDown;

    ReceivedPointerTracker() {
      mReceivedPointers = new PointerDownInfo[controller.getMaxPointerCount()];
      clear();
    }

    /** Clears the internals state. */
    public void clear() {
      mReceivedPointersDown = 0;
      for (int i = 0; i < controller.getMaxPointerCount(); ++i) {
        mReceivedPointers[i] = new PointerDownInfo();
      }
    }

    /**
     * Processes a received {@link MotionEvent} event.
     *
     * @param event The event to process.
     */
    public void onMotionEvent(MotionEvent event) {
      final int action = event.getActionMasked();
      switch (action) {
        case MotionEvent.ACTION_DOWN -> handleReceivedPointerDown(event.getActionIndex(), event);
        case MotionEvent.ACTION_POINTER_DOWN ->
            handleReceivedPointerDown(event.getActionIndex(), event);
        case MotionEvent.ACTION_UP -> handleReceivedPointerUp(event.getActionIndex(), event);
        case MotionEvent.ACTION_POINTER_UP ->
            handleReceivedPointerUp(event.getActionIndex(), event);
        default -> {}
      }
    }

    /**
     * @return The number of received pointers that are down.
     */
    public int getReceivedPointerDownCount() {
      return Integer.bitCount(mReceivedPointersDown);
    }

    /**
     * Whether an received pointer is down.
     *
     * @param pointerId The unique pointer id.
     * @return True if the pointer is down.
     */
    public boolean isReceivedPointerDown(int pointerId) {
      final int pointerFlag = (1 << pointerId);
      return (mReceivedPointersDown & pointerFlag) != 0;
    }

    /**
     * @param pointerId The unique pointer id.
     * @return The X coordinate where the pointer went down.
     */
    public float getReceivedPointerDownX(int pointerId) {
      return mReceivedPointers[pointerId].mX;
    }

    /**
     * @param pointerId The unique pointer id.
     * @return The Y coordinate where the pointer went down.
     */
    public float getReceivedPointerDownY(int pointerId) {
      return mReceivedPointers[pointerId].mY;
    }

    /**
     * @param pointerId The unique pointer id.
     * @return The time when the pointer went down.
     */
    public long getReceivedPointerDownTime(int pointerId) {
      return mReceivedPointers[pointerId].mTime;
    }

    /**
     * Handles a received pointer down event.
     *
     * @param pointerIndex The index of the pointer that has changed.
     * @param event The event to be handled.
     */
    private void handleReceivedPointerDown(int pointerIndex, MotionEvent event) {
      final int pointerId = event.getPointerId(pointerIndex);
      final int pointerFlag = (1 << pointerId);
      mReceivedPointersDown |= pointerFlag;
      mReceivedPointers[pointerId].set(
          event.getX(pointerIndex), event.getY(pointerIndex), event.getEventTime());
    }

    /**
     * Handles a received pointer up event.
     *
     * @param pointerIndex The index of the pointer that has changed.
     * @param event The event to be handled.
     */
    private void handleReceivedPointerUp(int pointerIndex, MotionEvent event) {
      final int pointerId = event.getPointerId(pointerIndex);
      final int pointerFlag = (1 << pointerId);
      mReceivedPointersDown &= ~pointerFlag;
      mReceivedPointers[pointerId].clear();
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append("=========================");
      builder.append("\nDown pointers #");
      builder.append(getReceivedPointerDownCount());
      builder.append(" [ ");
      for (int i = 0; i < controller.getMaxPointerCount(); i++) {
        if (isReceivedPointerDown(i)) {
          builder.append(i);
          builder.append(" ");
        }
      }
      builder.append("]");
      builder.append(" ]");
      builder.append("\n=========================");
      return builder.toString();
    }
  }

  /**
   * This class tracks where and when an individual pointer went down. Note that it does not track
   * when it went up.
   */
  static class PointerDownInfo {
    private float mX;
    private float mY;
    private long mTime;

    public void set(float x, float y, long time) {
      mX = x;
      mY = y;
      mTime = time;
    }

    public void clear() {
      mX = 0;
      mY = 0;
      mTime = 0;
    }
  }

  @Override
  public void onGestureCompleted(AccessibilityGestureEvent gestureEvent) {
    if (LogUtils.shouldLog(Log.DEBUG)) {
      Log.d(
          DEBUG_GESTURE_TAG,
          "Gesture "
              + AccessibilityServiceCompatUtils.gestureIdToString(gestureEvent.getGestureId())
              + " in state "
              + TouchInteractionController.stateToString(state)
              + ", previous "
              + TouchInteractionController.stateToString(previousState));
    }
    LogUtils.v(
        LOG_TAG,
        "TalkBack gesture id:%s detected",
        AccessibilityServiceCompatUtils.gestureIdToString(gestureEvent.getGestureId()));
    keepMonitorTouchExplore = false;
    // As the state is controlled in controller, it could be switched to CLEAR by its internal
    // handling. We have to honor the gesture detection in CLEAR state unless the previous state is
    // dragging or delegating.
    if (state == STATE_DRAGGING
        || state == STATE_DELEGATING
        || (state == STATE_CLEAR && (previousState == STATE_DRAGGING)
            || (previousState == STATE_DELEGATING))) {
      // Gestures are expected when controller's state is either interacting or touch exploring.
      LogUtils.w(
          LOG_TAG,
          "Gesture %s dropped in state %s , previous state %s",
          gestureEvent,
          TouchInteractionController.stateToString(state),
          TouchInteractionController.stateToString(previousState));
      return;
    }
    int gestureId = gestureEvent.getGestureId();
    if (gestureId == GESTURE_TOUCH_EXPLORE || gestureId == GESTURE_TAP_UP_TOUCH_EXPLORE) {
      requestTouchExplorationDelayed.cancel();
      requestTouchExploration("onGestureCompleted");
      int savedTimeMs = requestTouchExplorationDelayed.remainingTime();
      if (savedTimeMs > 0) {
        logger.logAnalyticsEvent(
            new GestureManifold.TapToTouchExploreAnalyticsEvent(
                EVENT_TAP_TO_TOUCH_EXPLORE, GESTURE_TOUCH_EXPLORE, savedTimeMs));
      }
      return;
    }
    // Log isDefaultDisplay/gestureId/onGestureDetectedTime. The targetGestureTimeout is the current
    // time minus lastMotionEventTransmissionLatency
    Performance.getInstance().onGestureRecognized(eventId, gestureId);
    @Nullable String edgeSwipe = edgeSwipeAction(gestureId);
    if (edgeSwipe != null) {
      dispatchEdgeActionAndClear(edgeSwipe);
      return;
    }
    if (gestureId == AccessibilityService.GESTURE_DOUBLE_TAP
        && serviceHandlesDoubleTap
        && touchEdge != null
        && edgeAction(touchEdge, EdgeGesture.TRIPLE_TAP) != null) {
      deferDoubleTap(gestureEvent, touchEdge);
      return;
    }
    if (gestureId == AccessibilityService.GESTURE_DOUBLE_TAP) {
      if (serviceHandlesDoubleTap) {
        dispatchGestureToMainThreadAndClear(gestureEvent);
      } else {
        controller.performClick();
      }
    } else if (gestureId == AccessibilityService.GESTURE_DOUBLE_TAP_AND_HOLD) {
      // Double-tap and Double-tap-and-hold are in pair which can be (configurable) handled by
      // framework or by service. The Double-tap-and-hold gesture may involve not only the long
      // click but also the dragging gesture.
      // TalkBack cannot handle it till now and pass back to controller for the gesture integrity.
      controller.performLongClickAndStartDrag();
    } else if (gestureId == GESTURE_FAKED_SPLIT_TYPING) {
      dispatchGestureToMainThread(
          new AccessibilityGestureEvent(
              GESTURE_FAKED_SPLIT_TYPING, displayId, new ArrayList<MotionEvent>()));
      // log firstDownTime with display id
      eventId = Performance.getInstance().onGestureEventReceived(displayId, null);
    } else if (gestureId == GESTURE_FAKED_SPLIT_TYPING_AND_HOLD) {
      dispatchGestureToMainThread(
          new AccessibilityGestureEvent(
              GESTURE_FAKED_SPLIT_TYPING_AND_HOLD, displayId, new ArrayList<MotionEvent>()));
      // log firstDownTime with display id
      eventId = Performance.getInstance().onGestureEventReceived(displayId, null);
    } else {
      dispatchGestureToMainThreadAndClear(gestureEvent);
    }
  }

  /** Dispatch a gesture event to the main thread of the service. */
  private void dispatchGestureToMainThreadAndClear(AccessibilityGestureEvent gestureEvent) {
    mainHandler.post(
        () -> {
          boolean unused = service.onGesture(gestureEvent);
        });
    clear();
    clearGestureDetectorOnNextDown = true;
    if (fingerDown) {
      followingHeldTouch = true;
      heldTouchOffsetX = 0;
      heldTouchOffsetY = 0;
    }
  }

  /**
   * Passes the rest of a touch that performed a gesture before lifting to the circle menu, which
   * the gesture may have opened, so that the user can slide straight to an item. The menu follows
   * the middle of the fingers that are down.
   */
  private void followHeldTouch(MotionEvent event) {
    int action = event.getActionMasked();
    if (action == MotionEvent.ACTION_CANCEL) {
      followingHeldTouch = false;
      return;
    }
    boolean lifted = action == MotionEvent.ACTION_UP;
    if (lifted) {
      followingHeldTouch = false;
    }
    if (action == MotionEvent.ACTION_POINTER_UP) {
      // Carry on from where the middle was, rather than jumping to the fingers still down.
      int liftingIndex = event.getActionIndex();
      heldTouchOffsetX += middle(event, -1, true) - middle(event, liftingIndex, true);
      heldTouchOffsetY += middle(event, -1, false) - middle(event, liftingIndex, false);
    }
    int skipIndex = action == MotionEvent.ACTION_POINTER_UP ? event.getActionIndex() : -1;
    float x = middle(event, skipIndex, true) + heldTouchOffsetX;
    float y = middle(event, skipIndex, false) + heldTouchOffsetY;
    mainHandler.post(
        () -> {
          ListMenuManager menuManager = service.getMenuManager();
          if (menuManager != null) {
            menuManager.followHeldTouch(x, y, lifted);
          }
        });
  }

  /** Returns the middle of the pointers of {@code event} on one axis, without {@code skipIndex}. */
  private static float middle(MotionEvent event, int skipIndex, boolean alongX) {
    float sum = 0;
    int count = 0;
    for (int index = 0; index < event.getPointerCount(); index++) {
      if (index != skipIndex) {
        sum += alongX ? event.getX(index) : event.getY(index);
        count++;
      }
    }
    return count == 0 ? 0 : sum / count;
  }

  /**
   * Passes the event to the two-finger rotation tracker. Returns true if the event belongs to a
   * rotation, so nothing else may handle it.
   */
  private boolean handleRotation(MotionEvent event) {
    boolean wasRotating = rotationTracker.isRotating();
    if (!wasRotating
        && (state != STATE_TOUCH_INTERACTING
            || gestureStarted
            || !gestureDetector.isMultiFingerGesturesEnabled())) {
      if (LogUtils.shouldLog(Log.DEBUG) && event.getActionMasked() == ACTION_POINTER_DOWN) {
        Log.d(
            DEBUG_GESTURE_TAG,
            "Rotation not possible: state="
                + TouchInteractionController.stateToString(state)
                + " gestureStarted="
                + gestureStarted);
      }
      rotationTracker.clear();
      return false;
    }
    if (!rotationTracker.onMotionEvent(event)) {
      return false;
    }
    if (!wasRotating) {
      // The rotation starts. No other gesture, touch exploration or passthrough for this touch.
      if (LogUtils.shouldLog(Log.DEBUG)) {
        Log.d(DEBUG_GESTURE_TAG, "Two-finger rotation started");
      }
      keepMonitorTouchExplore = false;
      requestTouchExplorationDelayed.cancel();
      gestureDetector.clear();
      // The matchers miss the rest of this touch, so a new touch in this interaction starts them
      // over.
      clearGestureDetectorOnNextDown = true;
      service.onGestureDetectionStarted();
    }
    return true;
  }

  private void onRotationStep(boolean clockwise) {
    int gestureId =
        clockwise ? GESTURE_2_FINGER_ROTATE_CLOCKWISE : GESTURE_2_FINGER_ROTATE_COUNTERCLOCKWISE;
    if (LogUtils.shouldLog(Log.DEBUG)) {
      Log.d(
          DEBUG_GESTURE_TAG,
          "Gesture " + AccessibilityServiceCompatUtils.gestureIdToString(gestureId));
    }
    dispatchGestureToMainThread(
        new AccessibilityGestureEvent(gestureId, displayId, new ArrayList<MotionEvent>()));
  }

  /**
   * Passes the touch to the edge menu once the finger has rested on the edge and slid inward. A
   * quick swipe off the edge doesn't rest, so it stays a swipe. Returns true if the event belongs
   * to the menu or to the resting finger.
   */
  private boolean handleEdgeMenu(MotionEvent event) {
    int action = event.getActionMasked();
    if (action == ACTION_DOWN) {
      edgeMenu.clear();
      edgeRested = false;
      downsInInteraction++;
      edgeWidthPixels = pixelsPerCm * EdgeGestureSettings.widthCm(sharedPreferences);
      @Nullable Edge edge =
          EdgeGestureSettings.isEnabled(sharedPreferences) && !FormFactorUtils.isAndroidWear()
              ? EdgeGestureSettings.edgeAt(event.getX(), screenWidth(), edgeWidthPixels)
              : null;
      // The keyboard's keys on the edges type when the finger lifts, which takes touch exploration.
      touchEdge = edge != null && isOnKeyboard(event.getX(), event.getY()) ? null : edge;
    }
    lastTouchX = event.getX();
    lastTouchY = event.getY();
    if (action == ACTION_DOWN) {
      return false;
    }
    if (edgeMenu.isOpen()) {
      switch (action) {
        case ACTION_MOVE -> edgeMenu.move(event.getX());
        case ACTION_POINTER_DOWN -> edgeMenu.abandon();
        case ACTION_UP -> edgeMenu.lift();
        case ACTION_CANCEL -> edgeMenu.clear();
        default -> {}
      }
      return true;
    }
    if (!edgeRested || touchEdge == null) {
      return false;
    }
    switch (action) {
      case ACTION_MOVE -> {
        if (edgeMenu.open(
            touchEdge,
            event.getX(),
            EdgeGestureSettings.menuActions(sharedPreferences, touchEdge, unassignedAction()),
            screenWidth(),
            edgeWidthPixels,
            edgeRestX,
            edgeMenuStartPixels,
            edgeMenuMaxStepPixels)) {
          edgeRested = false;
          if (LogUtils.shouldLog(Log.DEBUG)) {
            Log.d(DEBUG_GESTURE_TAG, "Edge menu opened on " + touchEdge);
          }
          gestureDetector.clear();
          // The matchers miss the rest of this touch, so a new touch in this interaction starts
          // them over.
          clearGestureDetectorOnNextDown = true;
          return true;
        }
        if (Math.abs(event.getY() - edgeRestY) > 2 * tapSlop) {
          // The finger explores along the edge, so the items there are explored as anywhere else.
          edgeRested = false;
          requestTouchExploration("edgeExplore");
          return true;
        }
      }
      // A second finger landed: the touch is handled as anywhere else, as a gesture, a scroll or a
      // drag. A lift leaves the item that the finger rested on focused.
      case ACTION_POINTER_DOWN, ACTION_UP, ACTION_CANCEL -> edgeRested = false;
      default -> {}
    }
    // The gesture matchers follow the resting finger, for a second finger that may land.
    return false;
  }

  /**
   * Keeps a finger resting on an edge with a menu from starting touch exploration, which would stop
   * the motion events the menu needs, and focuses the item under it instead. Returns true if it
   * did.
   */
  private boolean restOnEdge() {
    if (touchEdge == null
        || state != STATE_TOUCH_INTERACTING
        || !fingerDown
        || downsInInteraction != 1
        || receivedPointerTracker.getReceivedPointerDownCount() != 1
        || EdgeGestureSettings.edgeAt(lastTouchX, screenWidth(), edgeWidthPixels) != touchEdge
        || EdgeGestureSettings.menuActions(sharedPreferences, touchEdge, unassignedAction())
            .isEmpty()) {
      return false;
    }
    if (LogUtils.shouldLog(Log.DEBUG)) {
      Log.d(DEBUG_GESTURE_TAG, "Finger rests on " + touchEdge);
    }
    edgeRested = true;
    edgeRestX = lastTouchX;
    edgeRestY = lastTouchY;
    requestTouchExplorationDelayed.cancel();
    float x = lastTouchX;
    float y = lastTouchY;
    mainHandler.post(() -> service.onEdgeRest(x, y));
    return true;
  }

  private boolean isOnKeyboard(float x, float y) {
    List<AccessibilityWindowInfo> windows = service.getWindows();
    return windows != null && ScreenPoint.isOnKeyboard(windows, (int) x, (int) y);
  }

  /** Returns the action that the edge where the touch started has for {@code gestureId}. */
  private @Nullable String edgeSwipeAction(int gestureId) {
    if (touchEdge == null) {
      return null;
    }
    return switch (gestureId) {
      case GESTURE_SWIPE_UP -> edgeAction(touchEdge, EdgeGesture.SWIPE_UP);
      case GESTURE_SWIPE_DOWN -> edgeAction(touchEdge, EdgeGesture.SWIPE_DOWN);
      default -> null;
    };
  }

  private @Nullable String edgeAction(Edge edge, EdgeGesture gesture) {
    if (!EdgeGestureSettings.isEnabled(sharedPreferences)) {
      return null;
    }
    return EdgeGestureSettings.action(sharedPreferences, edge, gesture, unassignedAction());
  }

  private String unassignedAction() {
    return context.getString(R.string.shortcut_value_unassigned);
  }

  private float screenWidth() {
    return context.getResources().getDisplayMetrics().widthPixels;
  }

  /** Performs the action of an edge gesture in place of the gesture's own action. */
  private void dispatchEdgeActionAndClear(String action) {
    mainHandler.post(() -> service.performEdgeAction(action));
    clear();
    clearGestureDetectorOnNextDown = true;
  }

  /**
   * Holds back a double tap on {@code edge}, whose triple tap has an action, for the time allowed
   * between taps. A third tap close to it in that time performs the triple tap's action instead.
   */
  private void deferDoubleTap(AccessibilityGestureEvent gestureEvent, Edge edge) {
    List<MotionEvent> events = gestureEvent.getMotionEvents();
    if (!events.isEmpty()) {
      // The event may be recycled after this call, so only its place is kept.
      MotionEvent last = events.get(events.size() - 1);
      pendingTapX = last.getX();
      pendingTapY = last.getY();
    }
    pendingTripleTapEdge = edge;
    thirdTapState = THIRD_TAP_WAITING;
    mainHandler.removeCallbacks(pendingDoubleTapTimeout);
    mainHandler.postDelayed(pendingDoubleTapTimeout, GestureConfiguration.getMultiTapTimeoutMs());
    clear();
    clearGestureDetectorOnNextDown = true;
  }

  /** Performs the double tap that was held back for a third tap. */
  private void flushPendingDoubleTap() {
    mainHandler.removeCallbacks(pendingDoubleTapTimeout);
    if (pendingTripleTapEdge == null) {
      return;
    }
    pendingTripleTapEdge = null;
    if (thirdTapState == THIRD_TAP_WAITING) {
      thirdTapState = THIRD_TAP_NONE;
    }
    dispatchGestureToMainThread(
        new AccessibilityGestureEvent(GESTURE_DOUBLE_TAP, displayId, new ArrayList<MotionEvent>()));
  }

  /**
   * Takes the third tap after a double tap that waits for one. Returns true if the event belongs to
   * it, so nothing else may handle it.
   */
  private boolean handleThirdTap(MotionEvent event) {
    int action = event.getActionMasked();
    switch (thirdTapState) {
      case THIRD_TAP_WAITING -> {
        if (action != ACTION_DOWN) {
          return false;
        }
        if (Math.hypot(event.getX() - pendingTapX, event.getY() - pendingTapY) > doubleTapSlop) {
          // A touch somewhere else: the double tap goes ahead, and this touch is a new one.
          flushPendingDoubleTap();
          return false;
        }
        mainHandler.removeCallbacks(pendingDoubleTapTimeout);
        thirdTapState = THIRD_TAP_DOWN;
        thirdTapDownTime = event.getEventTime();
        return true;
      }
      case THIRD_TAP_DOWN -> {
        if (action == ACTION_UP) {
          thirdTapState = THIRD_TAP_NONE;
          @Nullable Edge edge = pendingTripleTapEdge;
          @Nullable String tripleTapAction =
              edge == null ? null : edgeAction(edge, EdgeGesture.TRIPLE_TAP);
          if (tripleTapAction != null
              && event.getEventTime() - thirdTapDownTime < ViewConfiguration.getLongPressTimeout()) {
            mainHandler.removeCallbacks(pendingDoubleTapTimeout);
            pendingTripleTapEdge = null;
            mainHandler.post(() -> service.performEdgeAction(tripleTapAction));
          } else {
            flushPendingDoubleTap();
          }
        } else if (action == ACTION_POINTER_DOWN
            || action == ACTION_CANCEL
            || (action == ACTION_MOVE
                && Math.hypot(event.getX() - pendingTapX, event.getY() - pendingTapY)
                    > doubleTapSlop + tapSlop)) {
          // Not a tap after all.
          thirdTapState = action == ACTION_CANCEL ? THIRD_TAP_NONE : THIRD_TAP_SWALLOWING;
          flushPendingDoubleTap();
        }
        return true;
      }
      case THIRD_TAP_SWALLOWING -> {
        if (action == ACTION_UP || action == ACTION_CANCEL) {
          thirdTapState = THIRD_TAP_NONE;
        }
        return true;
      }
      default -> {
        return false;
      }
    }
  }

  /** Dispatch a gesture event to the main thread of the service, but do not clear state. */
  private void dispatchGestureToMainThread(AccessibilityGestureEvent gestureEvent) {
    mainHandler.post(
        () -> {
          boolean unused = service.onGesture(gestureEvent);
        });
  }

  @Override
  public void onGestureCancelled(int gestureId) {
    if (touchExploreGate.remove(gestureId)) {
      if (keepMonitorTouchExplore
          && touchExploreGate.isEmpty()
          && state == STATE_TOUCH_INTERACTING) {
        keepMonitorTouchExplore = false;
        requestTouchExplorationDelayed.cancel();
        if (restOnEdge()) {
          return;
        }
        requestTouchExploration("onGestureCancelled");
      }
    }
  }

  @Override
  public void onGestureProcessing(int gestureId) {
    if (gestureId >= GESTURE_SWIPE_UP && gestureId <= GESTURE_SWIPE_DOWN_AND_RIGHT) {
      // A one-finger swipe moving its way: touch exploration waits until the swipe either starts
      // or runs out of time to, so that a swipe that starts slowly isn't explored.
      if (requestTouchExplorationDelayed.isPending() && !isLiftToType) {
        requestTouchExplorationDelayed.extendTo(swipeStartWindowMs);
      }
      return;
    }
    if (gestureId != GESTURE_DOUBLE_TAP) {
      return;
    }
    // When the touch exploration timer is pending, extend the timer to the original value to
    // accommodate the double-tap gesture detection; excluding the keyboard keys with lift-to-type.
    if (requestTouchExplorationDelayed.isPending() && !isLiftToType) {
      int remaining = requestTouchExplorationDelayed.remainingTime();
      requestTouchExplorationDelayed.cancel();
      requestTouchExplorationDelayed.post(remaining);
    }
  }

  @Override
  public void onGestureStarted(int gestureId) {
    gestureStarted = true;
    requestTouchExplorationDelayed.cancel();
    // The system will NOT send AccessibilityEvent TYPE_GESTURE_DETECTION_START and
    // TYPE_GESTURE_DETECTION_END when using TalkBack gesture detection. So the module has to
    // manually notify the service that gesture detection has been started.
    service.onGestureDetectionStarted();
  }

  public void setMultiFingerGesturesEnabled(boolean mode) {
    gestureDetector.setMultiFingerGesturesEnabled(mode);
  }

  public void setTwoFingerPassthroughEnabled(boolean mode) {
    gestureDetector.setTwoFingerPassthroughEnabled(mode);
  }

  public void setServiceHandlesDoubleTap(boolean mode) {
    serviceHandlesDoubleTap = mode;
  }

  /** Sets the direct click whose presses to take and hand to the app, or none with null. */
  public void setDirectClick(@Nullable DirectClick directClick) {
    this.directClick = directClick;
  }

  /**
   * Returns whether a direct click can press now, so that this gets the press and can hand it to
   * the app. Not while a finger is down, whose touch the press would cancel, nor in touch
   * exploration, which Android goes on with for a while after the finger lifts.
   */
  public boolean isReadyForDirectClick() {
    return directClick != null
        && stateInActive
        && !fingerDown
        && !stateChangeRequested
        && (state == STATE_CLEAR || state == STATE_TOUCH_INTERACTING);
  }

  /**
   * Hands the press of a direct click to the app, and returns whether the app gets it. Android has
   * done so itself if the press landed in a pass-through region.
   */
  private boolean handOverDirectClick() {
    if (state == STATE_DELEGATING) {
      return true;
    }
    if (state != STATE_TOUCH_INTERACTING || stateChangeRequested || !isStateTransitionAllowed()) {
      return false;
    }
    requestDelegating("handOverDirectClick");
    return true;
  }

  private void trackStateChangeRequest(int state, String caller) {
    CallerInfo newComer = new CallerInfo(state, caller, Thread.currentThread().getId());
    synchronized (callerInfos) {
      callerInfos.add(newComer);
    }
  }

  private IllegalStateException packExceptionWithCallerInfo(Exception e) {
    StringBuilder stringBuilder =
        new StringBuilder(
            String.format(
                "\nController's expected state: %s, , actual state: %s\n",
                TouchInteractionController.stateToString(state),
                TouchInteractionController.stateToString(controller.getState())));
    List<CallerInfo> callerList = new ArrayList<>(callerInfos);
    for (CallerInfo info : callerList) {
      stringBuilder.append(info).append("\n");
    }
    stringBuilder.append("\n");

    return new IllegalStateException(stringBuilder.toString(), e);
  }

  void requestTouchExploration(String caller) {
    try {
      if (isStateTransitionAllowed()) {
        trackStateChangeRequest(STATE_TOUCH_EXPLORING, caller);
        touchExplorationModeFailureReporter.onRequestTouchExploration();
        controller.requestTouchExploration();
      }
    } catch (IllegalStateException e) {
      throw packExceptionWithCallerInfo(e);
    }
    stateChangeRequested = true;
  }

  protected void requestDragging(int pointerId, String caller) {
    LogUtils.v(LOG_TAG, "requestDragging");
    try {
      if (isStateTransitionAllowed()) {
        trackStateChangeRequest(STATE_DRAGGING, caller);
        controller.requestDragging(pointerId);
      }
      gestureDetector.clear();

    } catch (IllegalStateException e) {
      throw packExceptionWithCallerInfo(e);
    }
    stateChangeRequested = true;
  }

  protected void requestDelegating(String caller) {
    LogUtils.v(LOG_TAG, "requestDelegating");
    try {
      if (isStateTransitionAllowed()) {
        trackStateChangeRequest(STATE_DELEGATING, caller);
        controller.requestDelegating();
      }
      gestureDetector.clear();
    } catch (IllegalStateException e) {
      throw packExceptionWithCallerInfo(e);
    }
    stateChangeRequested = true;
  }

  private boolean shouldPerformGestureDetection() {
    if (this.state == STATE_TOUCH_INTERACTING || this.state == STATE_TOUCH_EXPLORING) {
      return true;
    }
    return false;
  }

  private boolean shouldReceiveQueuedMotionEvents() {
    if (this.state == STATE_TOUCH_INTERACTING || this.state == STATE_DRAGGING) {
      return true;
    } else {
      return false;
    }
  }

  /** As the controller does not allow to change state in some situations, check it in advance. */
  private boolean isStateTransitionAllowed() {
    if (!stateInActive) {
      LogUtils.w(LOG_TAG, "isStateTransitionAllowed-state is inactive.");
      return false;
    }
    int controllerState = controller.getState();
    return controllerState != STATE_DELEGATING && controllerState != STATE_TOUCH_EXPLORING;
  }

  private class RequestTouchExplorationDelayed implements Runnable {
    private final int mDelay;
    private long postTime;
    private int actualDelay;
    private long startTime;
    @Nullable private TimerAction timerAction;

    enum PostTimerClass {
      LEGACY,
      IME,
      NON_IME
    }

    PostTimerClass postTimerClass;

    public RequestTouchExplorationDelayed(int delay) {
      mDelay = delay;
    }

    public void cancel() {
      mainHandler.removeCallbacks(this);
    }

    public void post(int remaining) {
      postTime = SystemClock.uptimeMillis();
      if (typingFocusTimeout || touchFocusTimeout) {
        if (isLiftToType) {
          postTimerClass = typingFocusTimeout ? PostTimerClass.IME : PostTimerClass.LEGACY;
        } else {
          postTimerClass = touchFocusTimeout ? PostTimerClass.NON_IME : PostTimerClass.LEGACY;
        }
      } else {
        postTimerClass = PostTimerClass.LEGACY;
      }
      actualDelay =
          switch (postTimerClass) {
            case LEGACY -> mDelay;
            case IME -> typingFocusUserIntentTimeout;
            case NON_IME -> touchFocusUserIntentTimeout;
          };
      if (remaining != 0) {
        actualDelay = mDelay - (actualDelay - remaining);
      } else {
        startTime = postTime;
        if (postTimerClass == PostTimerClass.IME) {
          timerAction =
              switch (typingFocusUserIntentTimeout) {
                case 100 -> TOUCH_EXPLORE_DELAY_TYPING_100;
                case 150 -> TOUCH_EXPLORE_DELAY_TYPING_150;
                case 200 -> TOUCH_EXPLORE_DELAY_TYPING_200;
                case 250 -> TOUCH_EXPLORE_DELAY_TYPING_250;
                default -> TOUCH_EXPLORE_DELAY_TYPING_250;
              };
        } else if (postTimerClass == PostTimerClass.NON_IME) {
          timerAction =
              switch (touchFocusUserIntentTimeout) {
                case 100 -> TOUCH_EXPLORE_DELAY_100;
                case 150 -> TOUCH_EXPLORE_DELAY_150;
                case 200 -> TOUCH_EXPLORE_DELAY_200;
                case 250 -> TOUCH_EXPLORE_DELAY_250;
                default -> TOUCH_EXPLORE_DELAY_250;
              };
        } else {
          timerAction = TOUCH_EXPLORE_DELAY_250;
        }
      }
      mainHandler.postDelayed(this, actualDelay);
    }

    public void post() {
      post(0);
    }

    /** Fires no sooner than [totalMs] after it was posted. */
    public void extendTo(int totalMs) {
      int elapsed = (int) (SystemClock.uptimeMillis() - postTime);
      if (totalMs <= actualDelay) {
        return;
      }
      mainHandler.removeCallbacks(this);
      actualDelay = totalMs;
      mainHandler.postDelayed(this, Math.max(0, totalMs - elapsed));
    }

    public int remainingTime() {
      return actualDelay - (int) (SystemClock.uptimeMillis() - postTime);
    }

    public boolean isPending() {
      return mainHandler.hasCallbacks(this);
    }

    @Override
    public void run() {
      if (restOnEdge()) {
        return;
      }
      if (timerAction != null) {
        primesController.recordDuration(timerAction, startTime, SystemClock.uptimeMillis());
      }
      requestTouchExploration("RequestTouchExplorationDelayed");
      int savedTimeMs = (int) (SystemClock.uptimeMillis() - postTime);
      if (savedTimeMs > 0) {
        logger.logAnalyticsEvent(
            new GestureManifold.TapToTouchExploreAnalyticsEvent(
                EVENT_TAP_TO_TOUCH_EXPLORE, GESTURE_TOUCH_EXPLORE, savedTimeMs));
      }
    }
  }
}
