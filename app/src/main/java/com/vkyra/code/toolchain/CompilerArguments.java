package com.vkyra.code.toolchain;

import java.util.ArrayList;
import java.util.List;

/** Parses command-line words without shell expansion or command execution. */
public final class CompilerArguments {
    private CompilerArguments() { }
    public static List<String> parse(String arguments) {
        List<String> words = new ArrayList<>();
        if (arguments == null) return words;
        StringBuilder word = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        boolean started = false;
        for (int i = 0; i < arguments.length(); i++) {
            char c = arguments.charAt(i);
            if (escaped) { word.append(c); escaped = false; started = true; }
            else if (c == '\\' && quote != '\'') {
                if (quote == '"' && i + 1 < arguments.length()
                        && "\"\\$`\n".indexOf(arguments.charAt(i + 1)) < 0) word.append(c);
                else escaped = true;
                started = true;
            }
            else if (quote != 0) {
                if (c == quote) quote = 0;
                else word.append(c);
            } else if (c == '\'' || c == '"') { quote = c; started = true; }
            else if (Character.isWhitespace(c)) {
                if (started) { words.add(word.toString()); word.setLength(0); started = false; }
            } else { word.append(c); started = true; }
        }
        if (quote != 0 || escaped) throw new IllegalArgumentException("编译参数含未闭合的引号或转义");
        if (started) words.add(word.toString());
        return words;
    }
    public static String shellQuote(String word) { return "'" + word.replace("'", "'\\''") + "'"; }
    public static String shellCommand(List<String> words) {
        StringBuilder command = new StringBuilder();
        for (String word : words) {
            if (command.length() > 0) command.append(' ');
            command.append(shellQuote(word));
        }
        return command.toString();
    }
}
