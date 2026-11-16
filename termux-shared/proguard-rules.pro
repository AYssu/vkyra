# Standard R8 rules for the embedded terminal library.
# The app owns final shrinking; no custom dictionaries or obfuscation passes.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
