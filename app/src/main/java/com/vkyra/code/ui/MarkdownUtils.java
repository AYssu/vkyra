package com.vkyra.code.ui;

import android.content.Context;
import android.text.Spanned;
import android.widget.TextView;

import io.noties.markwon.Markwon;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tasklist.TaskListPlugin;

/** CommonMark rendering shared by previews and release notes. Keeps application Context only. */
public final class MarkdownUtils {
    private static volatile Markwon renderer;

    private MarkdownUtils() { }

    public static Markwon renderer(Context context) {
        Markwon result = renderer;
        if (result == null) {
            synchronized (MarkdownUtils.class) {
                result = renderer;
                if (result == null) {
                    Context application = context.getApplicationContext();
                    result = Markwon.builder(application != null ? application : context)
                            .usePlugin(StrikethroughPlugin.create())
                            .usePlugin(TablePlugin.create(application != null ? application : context))
                            .usePlugin(TaskListPlugin.create(application != null ? application : context))
                            .build();
                    renderer = result;
                }
            }
        }
        return result;
    }

    public static Spanned render(Context context, String markdown) {
        return renderer(context).toMarkdown(markdown == null ? "" : markdown);
    }

    public static void setMarkdown(TextView textView, String markdown) {
        if (textView != null) renderer(textView.getContext()).setMarkdown(textView, markdown == null ? "" : markdown);
    }

    public static void setMarkdownWithCodeBlocks(TextView textView, String markdown, boolean isDarkTheme) {
        setMarkdown(textView, markdown);
    }
}
