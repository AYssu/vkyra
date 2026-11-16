package com.vkyra.code.ui;

import android.content.Context;
import android.text.Spanned;
import android.text.style.StrikethroughSpan;
import io.noties.markwon.core.spans.StrongEmphasisSpan;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MarkdownRenderingTest {
    @Test public void commonMarkAndGithubExtensionsProduceRealSpans() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Spanned result = MarkdownUtils.render(context,
                "# 标题\n\n**粗体** and ~~删除~~\n\n- [x] 完成\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n```cpp\nint main() {}\n```\n");
        assertTrue(result.toString().contains("标题"));
        assertFalse(result.toString().contains("**"));
        assertTrue(result.getSpans(0, result.length(), StrongEmphasisSpan.class).length > 0);
        assertTrue(result.getSpans(0, result.length(), StrikethroughSpan.class).length > 0);
        boolean table = false;
        boolean task = false;
        for (Object span : result.getSpans(0, result.length(), Object.class)) {
            table |= span.getClass().getName().startsWith("io.noties.markwon.ext.tables.");
            task |= span.getClass().getName().startsWith("io.noties.markwon.ext.tasklist.");
        }
        assertTrue("table plugin", table);
        assertTrue("task plugin", task);
    }

    @Test public void nullMarkdownClearsPreviousText() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            TextView view = new TextView(InstrumentationRegistry.getInstrumentation().getTargetContext());
            view.setText("old");
            MarkdownUtils.setMarkdown(view, null);
            assertEquals("", view.getText().toString());
        });
    }
}
