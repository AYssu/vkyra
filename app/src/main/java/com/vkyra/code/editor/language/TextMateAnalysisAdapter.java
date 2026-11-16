package com.vkyra.code.editor.language;

import android.os.Bundle;

import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.analysis.StyleReceiver;
import io.github.rosemoe.sora.lang.analysis.StyleUpdateRange;
import io.github.rosemoe.sora.lang.brackets.BracketsProvider;
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.lang.styling.inlayHint.InlayHintsContainer;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;

/** Keeps upstream incremental analysis; adds scope filtering to its standard bracket matcher. */
final class TextMateAnalysisAdapter implements AnalyzeManager {
    private final AnalyzeManager delegate;
    private volatile StyleReceiver receiver;
    private volatile Styles styles;
    private volatile boolean bracketsReady;

    TextMateAnalysisAdapter(AnalyzeManager delegate) { this.delegate = delegate; }

    @Override public void setReceiver(StyleReceiver receiver) {
        this.receiver = receiver;
        delegate.setReceiver(receiver == null ? null : new StyleReceiver() {
            @Override public void setStyles(AnalyzeManager source, Styles value) {
                styles = value;
                StyleReceiver current = TextMateAnalysisAdapter.this.receiver;
                if (current != null) current.setStyles(TextMateAnalysisAdapter.this, value);
            }
            @Override public void setStyles(AnalyzeManager source, Styles value, Runnable action) {
                styles = value;
                StyleReceiver current = TextMateAnalysisAdapter.this.receiver;
                if (current != null) current.setStyles(TextMateAnalysisAdapter.this, value, action);
            }
            @Override public void updateStyles(AnalyzeManager source, Styles value, StyleUpdateRange range) {
                styles = value;
                StyleReceiver current = TextMateAnalysisAdapter.this.receiver;
                if (current != null) current.updateStyles(TextMateAnalysisAdapter.this, value, range);
            }
            @Override public void setDiagnostics(AnalyzeManager source, DiagnosticsContainer value) {
                StyleReceiver current = TextMateAnalysisAdapter.this.receiver;
                if (current != null) current.setDiagnostics(TextMateAnalysisAdapter.this, value);
            }
            @Override public void setInlayHints(AnalyzeManager source, InlayHintsContainer value) {
                StyleReceiver current = TextMateAnalysisAdapter.this.receiver;
                if (current != null) current.setInlayHints(TextMateAnalysisAdapter.this, value);
            }
            @Override public void updateBracketProvider(AnalyzeManager source, BracketsProvider provider) {
                bracketsReady = true;
                StyleReceiver current = TextMateAnalysisAdapter.this.receiver;
                if (current != null) current.updateBracketProvider(TextMateAnalysisAdapter.this, (text, index) -> {
                    Styles latest = styles;
                    return bracketsReady && latest != null
                            ? new ScopedBracketsProvider(provider, latest.spans).getPairedBracketAt(text, index) : null;
                });
            }
        });
    }

    @Override public void reset(ContentReference content, Bundle arguments) {
        bracketsReady = false;
        styles = null;
        delegate.reset(content, arguments);
    }
    @Override public void insert(CharPosition start, CharPosition end, CharSequence text) {
        bracketsReady = false;
        delegate.insert(start, end, text);
    }
    @Override public void delete(CharPosition start, CharPosition end, CharSequence text) {
        bracketsReady = false;
        delegate.delete(start, end, text);
    }
    @Override public void rerun() { bracketsReady = false; delegate.rerun(); }
    @Override public void destroy() {
        receiver = null;
        styles = null;
        bracketsReady = false;
        delegate.setReceiver(null);
        delegate.destroy();
    }
}
