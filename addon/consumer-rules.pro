# QuietSemantics reads these two Compose fields by name (reflection)
-keepclassmembers class androidx.compose.ui.platform.AndroidComposeView {
    private final androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat composeAccessibilityDelegate;
}
-keepclassmembers class androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat {
    private java.util.List _enabledServices;
}
