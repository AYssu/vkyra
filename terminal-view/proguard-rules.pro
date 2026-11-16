# Standard R8 rules for the embedded terminal library.
# The app owns final shrinking; no custom dictionaries or obfuscation passes.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
# Selection coordinates are accessed reflectively by terminal integrations.
-keepclassmembers class com.termux.view.textselection.TextSelectionCursorController {
    private int mSelX1;
    private int mSelY1;
    private int mSelX2;
    private int mSelY2;
}
